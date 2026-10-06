#include <jni.h>
#include "llama.h"
#include "ggml-backend.h"
#if defined(__unix__) || defined(__APPLE__)
#include <dlfcn.h>
#endif
#include "k2_prompt.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <fstream>
#include <cstdlib>
#include <sys/stat.h>
#ifdef __ANDROID__
#include <sched.h>
#endif

namespace {
struct Request { std::atomic<bool> cancelled{false}; };
std::once_flag initialized;
// One serialized K2 CPU session. It is never a file capability or a disk cache.
struct CachedSession {
    std::string key;
    std::shared_ptr<llama_model> model;
    std::shared_ptr<llama_context> context;
    std::vector<llama_token> prompt;
    void clear() { context.reset(); model.reset(); prompt.clear(); key.clear(); }
};
CachedSession cachedSession;
std::mutex sessionMutex;
std::atomic<bool> evictSession{false};
struct SessionTransaction {
    bool committed=false;
    ~SessionTransaction() { if (!committed || evictSession.exchange(false)) cachedSession.clear(); }
};
struct AbortBinding {
    std::shared_ptr<llama_context> context;
    ~AbortBinding() { if(context) llama_set_abort_callback(context.get(), nullptr, nullptr); }
};
void initializeRuntime() {
    #if defined(__unix__) || defined(__APPLE__)
    Dl_info info{};
    if (dladdr(reinterpret_cast<void *>(&initializeRuntime), &info) && info.dli_fname) {
        const std::string path(info.dli_fname);
        const auto slash = path.find_last_of('/');
        if (slash != std::string::npos) ggml_backend_load_all_from_path(path.substr(0, slash).c_str());
    }
    #endif
    llama_backend_init();
}
// Optional measured CPU placement; restore the pooled JNI worker's original mask.
class CpuPlacement {
#ifdef __ANDROID__
    cpu_set_t previous{};
    bool changed = false;
#endif
public:
    CpuPlacement(int threads, bool enabled) {
#ifdef __ANDROID__
        if (!enabled || sched_getaffinity(0, sizeof(previous), &previous) != 0) return;
        std::vector<std::pair<long, int>> cores;
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (!CPU_ISSET(cpu, &previous)) continue;
            std::ifstream file("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
            long frequency = 0;
            if (!(file >> frequency) || frequency <= 0) return; // No reliable topology: keep system scheduling.
            cores.emplace_back(frequency, cpu);
        }
        if (cores.size() < static_cast<size_t>(threads)) return;
        std::sort(cores.rbegin(), cores.rend());
        cpu_set_t selected; CPU_ZERO(&selected);
        for (int i = 0; i < threads; ++i) CPU_SET(cores[i].second, &selected);
        changed = sched_setaffinity(0, sizeof(selected), &selected) == 0;
#endif
    }
    ~CpuPlacement() {
#ifdef __ANDROID__
        if (changed) sched_setaffinity(0, sizeof(previous), &previous);
#endif
    }
};
bool aborted(void * data) { return static_cast<Request *>(data)->cancelled.load(); }
bool progress(float, void * data) { return !aborted(data); }
std::string bytes(JNIEnv * env, jbyteArray value) {
    const auto size = env->GetArrayLength(value);
    std::string result(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
void fail(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

ggml_type kvCacheType(JNIEnv * env, jstring name) {
    if (!name) throw std::runtime_error("Choose Q8_0 or Q5_0 for the local KV cache.");
    const char * value = env->GetStringUTFChars(name, nullptr);
    if (!value) throw std::runtime_error("Could not read the local KV cache setting.");
    const std::string quantization(value);
    env->ReleaseStringUTFChars(name, value);
    if (quantization == "Q8_0") return GGML_TYPE_Q8_0;
    if (quantization == "Q5_0") return GGML_TYPE_Q5_0;
    throw std::runtime_error("Unsupported local KV cache. Choose Q8_0 or Q5_0.");
}

std::string readReasoningEffort(JNIEnv * env, jstring name) {
    if (!name) throw std::runtime_error("Choose low, medium or high reasoning effort.");
    const char * value = env->GetStringUTFChars(name, nullptr);
    if (!value) throw std::runtime_error("Could not read the reasoning effort setting.");
    const std::string effort(value);
    env->ReleaseStringUTFChars(name, value);
    return effort;
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_com_androidharness_app_local_LocalNative_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new Request());
}
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_cancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Request *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Request *>(handle);
    std::unique_lock<std::mutex> lock(sessionMutex, std::try_to_lock);
    if(lock.owns_lock() && evictSession.exchange(false)) cachedSession.clear();
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_androidharness_app_local_LocalNative_generate(
    JNIEnv * env, jobject, jlong handle, jbyteArray pathBytes, jobjectArray roles,
    jobjectArray contents, jint contextSize, jint inputLimit, jint outputLimit,
    jint threads, jstring kvCacheName, jstring reasoningEffortName, jbyteArray projectorBytes, jobjectArray imageBytes, jobject callback, jint gpuLayers, jboolean fastCpu, jboolean reuseSession) {
    try {
        std::unique_lock<std::mutex> sessionLock(sessionMutex);
        SessionTransaction transaction;
        if(evictSession.exchange(false) || !reuseSession) cachedSession.clear();
        auto * request = reinterpret_cast<Request *>(handle);
        std::call_once(initialized, initializeRuntime);
        if (aborted(request)) throw std::runtime_error("Local generation stopped.");
        if (contextSize < 512 || contextSize > 262144 || inputLimit < 1 || outputLimit < 1 ||
            static_cast<int64_t>(inputLimit) + outputLimit > contextSize || threads < 1 || threads > 8)
            throw std::runtime_error("Invalid local model limits.");
        const auto cacheType = kvCacheType(env, kvCacheName);
        const auto reasoningEffort = readReasoningEffort(env, reasoningEffortName);
        const auto thinkingPrefix = harness_local::k2ThinkingOpening(reasoningEffort);
        auto params = llama_model_default_params();
        if (gpuLayers < 0 || gpuLayers > 99) throw std::runtime_error("Invalid GPU layer count.");
        if (gpuLayers > 0 && !llama_supports_gpu_offload()) throw std::runtime_error("GPU inference is unavailable on this device.");
        params.n_gpu_layers = gpuLayers;
        ggml_backend_dev_t cpuOnlyDevices[] = { nullptr };
        if (gpuLayers == 0) params.devices = cpuOnlyDevices;
        CpuPlacement placement(threads, gpuLayers == 0 && fastCpu);
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.progress_callback = progress;
        params.progress_callback_user_data = request;
        const auto path = bytes(env, pathBytes);
        struct stat identity{};
        if(stat(path.c_str(), &identity)!=0) throw std::runtime_error("Model file is unavailable.");
        const auto key=path + ":" + std::to_string(identity.st_size) + ":" + std::to_string(identity.st_mtime) +
            ":" + std::to_string(identity.st_ino) + ":" + std::to_string(contextSize) + ":" +
            std::to_string(cacheType) + ":" + std::to_string(threads) + ":" + std::to_string(gpuLayers) + ":" + std::to_string(fastCpu);
        if(cachedSession.key!=key) cachedSession.clear();
        auto model=cachedSession.model;
        if(!model) model=std::shared_ptr<llama_model>(llama_model_load_from_file(path.c_str(), params), llama_model_free);
        if (!model) throw std::runtime_error("Could not load model. Free memory or reinstall the model.");
        if (aborted(request)) throw std::runtime_error("Local generation stopped.");
        if (contextSize > llama_model_n_ctx_train(model.get()))
            throw std::runtime_error("Context exceeds this model's trained limit.");
        const auto count = env->GetArrayLength(roles);
        if (count != env->GetArrayLength(contents)) throw std::runtime_error("Invalid chat messages.");
        std::vector<std::string> roleValues, textValues;
        roleValues.reserve(count);
        textValues.reserve(count);
        for (int i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            const char * value = env->GetStringUTFChars(role, nullptr);
            roleValues.emplace_back(value);
            env->ReleaseStringUTFChars(role, value);
            env->DeleteLocalRef(role);
            auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            textValues.push_back(bytes(env, content));
            env->DeleteLocalRef(content);
        }
        char architecture[64]{};
        llama_model_meta_val_str(model.get(), "general.architecture", architecture, sizeof(architecture));
        const bool isK2 = std::string(architecture) == "k2-horizon";
        char basename[128]{};
        llama_model_meta_val_str(model.get(), "general.basename", basename, sizeof(basename));
        const bool isQwen = std::string(architecture) == "qwen35";
        const bool isMiniCpm = std::string(architecture) == "llama" && std::string(basename) == "MiniCPM5";
        const bool isChatmlAgent = isMiniCpm || isQwen;
        const bool miniThinking = isChatmlAgent && reasoningEffort != "low";

        std::string prompt;
        if (isK2) {
            prompt = harness_local::k2Prompt(roleValues, textValues, reasoningEffort);
        } else if (isChatmlAgent) {
            for (int i = 0; i < count; ++i) {
                std::string text = textValues[i];
                for (const auto & boundary : {std::string("<|im_start|>"), std::string("<|im_end|>")}) {
                    size_t at = 0;
                    while ((at = text.find(boundary, at)) != std::string::npos) { text.replace(at, boundary.size(), "[message boundary]"); at += 18; }
                }
                if (roleValues[i] == "tool") prompt += "<|im_start|>user\n<tool_response>\n" + text + "\n</tool_response><|im_end|>\n";
                else prompt += "<|im_start|>" + roleValues[i] + "\n" + (roleValues[i] == "assistant" ? "<think>\n\n</think>\n\n" : "") + text + "<|im_end|>\n";
            }
            prompt += miniThinking ? "<|im_start|>assistant\n<think>\n" : "<|im_start|>assistant\n<think>\n\n</think>\n\n";
        } else {
            std::vector<llama_chat_message> messages;
            for (int i = 0; i < count; ++i) messages.push_back({roleValues[i].c_str(), textValues[i].c_str()});
            const char * chatTemplate = llama_model_chat_template(model.get(), nullptr);
            if (!chatTemplate) throw std::runtime_error("Model has no supported chat template.");
            int size = llama_chat_apply_template(chatTemplate, messages.data(), messages.size(), true, nullptr, 0);
            if (size <= 0) throw std::runtime_error("Unsupported model chat template.");
            prompt.assign(size, '\0');
            const int written = llama_chat_apply_template(chatTemplate, messages.data(), messages.size(), true, prompt.data(), size);
            if (written < 0 || written > size) throw std::runtime_error("Could not format chat.");
            prompt.resize(written);
        }
        const auto * vocab = llama_model_get_vocab(model.get());
        // K2 includes its BOS in the official template; do not insert it twice.

        const int imageCount = env->GetArrayLength(imageBytes);
        if (imageCount < 0 || imageCount > 2) throw std::runtime_error("Use up to two images per local request.");
        if (imageCount > 0 && !isQwen) throw std::runtime_error("This model does not support local image input.");
        const bool cacheEligible=reuseSession && isK2 && imageCount==0 && gpuLayers==0;
        if(!cacheEligible) cachedSession.clear();
        mtmd::context_ptr vision;
        mtmd::input_chunks_ptr chunks;
        std::vector<mtmd::bitmap_ptr> bitmaps;
        std::vector<const mtmd_bitmap *> bitmapViews;
        std::vector<llama_token> tokens;
        int tokenCount = 0;
        if (imageCount > 0) {
            const auto projectorPath = bytes(env, projectorBytes);
            if (projectorPath.empty()) throw std::runtime_error("Enable Vision and download its projector first.");
            auto visionParams = mtmd_context_params_default();
            visionParams.use_gpu = false;
            visionParams.n_threads = threads;
            visionParams.warmup = false;
            visionParams.image_max_tokens = 256;
            visionParams.progress_callback = [](float, void * opaque) { return !aborted(static_cast<Request *>(opaque)); };
            visionParams.progress_callback_user_data = request;
            visionParams.cb_eval = [](ggml_tensor *, bool ask, void * opaque) { return ask || !aborted(static_cast<Request *>(opaque)); };
            visionParams.cb_eval_user_data = request;
            vision.reset(mtmd_init_from_file(projectorPath.c_str(), model.get(), visionParams));
            if (!vision || !mtmd_support_vision(vision.get())) throw std::runtime_error("Could not load the vision projector. Free memory or reinstall vision.");
            for (int i = 0; i < imageCount; ++i) {
                auto image = static_cast<jbyteArray>(env->GetObjectArrayElement(imageBytes, i));
                if (env->GetArrayLength(image) > 16 * 1024 * 1024) { env->DeleteLocalRef(image); throw std::runtime_error("Image is too large."); }
                const auto data = bytes(env, image); env->DeleteLocalRef(image);
                auto wrapper = mtmd_helper_bitmap_init_from_buf(vision.get(), reinterpret_cast<const unsigned char *>(data.data()), data.size(), false, mtmd_helper_init_opt_default());
                if (wrapper.video_ctx) { mtmd_helper_video_free(wrapper.video_ctx); if (wrapper.bitmap) mtmd_bitmap_free(wrapper.bitmap); throw std::runtime_error("Use a still image, not video."); }
                if (!wrapper.bitmap) throw std::runtime_error("Could not decode the attached image. Use PNG or JPEG.");
                bitmaps.emplace_back(wrapper.bitmap); bitmapViews.push_back(wrapper.bitmap);
            }
            chunks.reset(mtmd_input_chunks_init());
            const mtmd_input_text inputText{prompt.data(), prompt.size(), false, true};
            if (mtmd_tokenize(vision.get(), chunks.get(), &inputText, bitmapViews.data(), bitmapViews.size()) != 0)
                throw std::runtime_error("Could not prepare image/text input.");
            const auto countWithImages = mtmd_helper_get_n_tokens(chunks.get());
            if (countWithImages > static_cast<size_t>(inputLimit) || mtmd_helper_get_n_pos(chunks.get()) > inputLimit)
                throw std::runtime_error("Image and text exceed the input context. Shorten the request or increase context.");
            tokenCount = static_cast<int>(countWithImages);
        } else {
            tokenCount = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, !isK2, true);
            if (tokenCount <= 0 || tokenCount > inputLimit)
                throw std::runtime_error("Input exceeds the local model input limit. Start a new chat, shorten the message, or increase limits in Local models.");
            tokens.resize(tokenCount);
            if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), !isK2, true) != tokenCount)
                throw std::runtime_error("Could not tokenize chat.");
        }
        auto contextParams = llama_context_default_params();
        contextParams.n_ctx = contextSize;
        contextParams.n_batch = 256;
        contextParams.n_ubatch = 128;
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
        contextParams.type_k = cacheType;
        contextParams.type_v = cacheType;
        // Quantized V requires Flash Attention. The pinned CPU backend supports
        // Q8_0/Q5_0 through its generic vec-dot/dequantization implementation.
        contextParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        contextParams.abort_callback = aborted;
        contextParams.abort_callback_data = request;
        auto context=cacheEligible ? cachedSession.context : std::shared_ptr<llama_context>{};
        int reused=0;
        if(context) {
            while(reused<std::min(static_cast<int>(cachedSession.prompt.size()), tokenCount-1) &&
                cachedSession.prompt[reused]==tokens[reused]) ++reused;
            // Preserve full original prefill blocks. Reusing a fractional batch changes
            // quantized matrix-kernel shapes and can change greedy outputs.
            reused=(reused/256)*256;
            // Re-evaluate at least the last prompt token to refresh logits; remove all
            // generated/different suffixes. Unsupported removal falls back to a fresh context.
            if(!llama_memory_seq_rm(llama_get_memory(context.get()), 0, reused, -1)) {
                context.reset(); cachedSession.context.reset(); cachedSession.prompt.clear(); reused=0;
            }
        }
        if(!context) context=std::shared_ptr<llama_context>(llama_init_from_model(model.get(), contextParams), llama_free);
        if (!context) throw std::runtime_error("Could not initialize the local context and KV cache. Lower context, free memory, or try another KV cache.");

        llama_set_abort_callback(context.get(), aborted, request);
        AbortBinding binding{context};
        if (vision) {
            llama_pos past = 0;
            if (aborted(request) || mtmd_helper_eval_chunks(vision.get(), context.get(), chunks.get(), 0, 0, 256, true, &past) != 0)
                throw std::runtime_error("Local vision evaluation stopped or failed.");
        } else {
            for (int offset = reused; offset < tokenCount; offset += 256) {
                if (aborted(request)) throw std::runtime_error("Local generation stopped.");
                auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, tokenCount - offset));
                if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Local prompt evaluation stopped or failed.");
            }
        }
        if(cacheEligible) {
            cachedSession.key=key; cachedSession.model=model; cachedSession.context=context; cachedSession.prompt=tokens;
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        const auto callbackClass = env->GetObjectClass(callback);
        const auto onToken = env->GetMethodID(callbackClass, "onToken", "([B)Z");
        if (!onToken) return nullptr;
        if (isK2 || isChatmlAgent) {
            const auto opening = isK2 ? thinkingPrefix : (miniThinking ? std::string("<think>\n") : std::string("<think>\n\n</think>\n\n"));
            // Expose the already-open prompt section so the provider can detect
            // reasoning from runtime metadata even for a renamed imported GGUF.
            // This is framing, not a sampled token, and is excluded from usage.
            auto data = env->NewByteArray(opening.size());
            env->SetByteArrayRegion(data, 0, opening.size(), reinterpret_cast<const jbyte *>(opening.data()));
            const bool keepGoing = env->CallBooleanMethod(callback, onToken, data);
            env->DeleteLocalRef(data);
            if (env->ExceptionCheck()) return nullptr;
            if (!keepGoing) throw std::runtime_error("Local generation stopped.");
        }
        int generated = 0;
        bool ended = false;
        while (generated < outputLimit) {
            if (aborted(request)) throw std::runtime_error("Local generation stopped.");
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, isK2 || isChatmlAgent);
            if (length < 0) {
                piece.resize(-length);
                length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, isK2 || isChatmlAgent);
            }
            if (length < 0) throw std::runtime_error("Could not decode output token.");
            if (isK2 && std::string(piece.data(), length) == "<|ifm|im_end|>") {
                ended = true;
                break;
            }
            auto data = env->NewByteArray(length);
            env->SetByteArrayRegion(data, 0, length, reinterpret_cast<const jbyte *>(piece.data()));
            const bool keepGoing = env->CallBooleanMethod(callback, onToken, data);
            env->DeleteLocalRef(data);
            if (env->ExceptionCheck()) return nullptr;
            if (!keepGoing) throw std::runtime_error("Local generation stopped.");
            ++generated;
            if (generated < outputLimit) {
                auto batch = llama_batch_get_one(&token, 1);
                if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Local generation stopped or failed.");
            }
        }
        jint counts[] = {tokenCount, generated, ended ? 0 : 1, reused};
        auto result = env->NewIntArray(4);
        env->SetIntArrayRegion(result, 0, 4, counts);
        transaction.committed=true;
        return result;
    } catch (const std::exception & error) {
        fail(env, error.what());
        return nullptr;
    } catch (...) {
        fail(env, "Native inference failed.");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidharness_app_local_LocalNative_runtimeInfo(JNIEnv * env, jobject) {
    std::call_once(initialized, initializeRuntime);
    return env->NewStringUTF(llama_print_system_info());
}

// Instrumentation-only compatibility experiment, before the runtime is initialized.
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_configureVulkanCompatibility(JNIEnv *, jobject, jboolean disableF16) {
#ifdef __ANDROID__
    if (disableF16) setenv("GGML_VK_DISABLE_F16", "1", 1);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_evictSession(JNIEnv *, jobject) {
    // Stop/UI calls never block behind inference. The worker clears on exit.
    evictSession.store(true);
    std::unique_lock<std::mutex> lock(sessionMutex, std::try_to_lock);
    if(lock.owns_lock()) { cachedSession.clear(); evictSession.store(false); }
}
