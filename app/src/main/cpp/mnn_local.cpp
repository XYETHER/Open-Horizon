#include <jni.h>
#include <android/log.h>
#include <llm/llm.hpp>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <unordered_map>
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;
namespace {
struct Run { std::atomic<bool> cancelled{false}; };
std::mutex handlesMutex, runtimeMutex;
std::unordered_map<jlong,std::shared_ptr<Run>> handles;
std::atomic<jlong> nextHandle{1};
struct DeleteLlm { void operator()(Llm* p) const { if(p) Llm::destroy(p); } };
std::unique_ptr<Llm,DeleteLlm> cached;
std::string cachedKey, info="MNN optimized ARM64 CPU / ARM82 / INT4 + OpenCL compiled. NPU not configured.";
std::shared_ptr<Run> get(jlong id) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto it=handles.find(id); if(it==handles.end())throw std::runtime_error("Invalid inference handle"); return it->second;
}
void fail(JNIEnv* e,const std::string& message) { if(!e->ExceptionCheck())e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),message.c_str()); }
std::string bytes(JNIEnv* e,jbyteArray a) {
    if(!a)return {}; std::string s(e->GetArrayLength(a),'\0'); e->GetByteArrayRegion(a,0,s.size(),reinterpret_cast<jbyte*>(s.data()));return s;
}
std::string string(JNIEnv* e,jstring a) {
    if(!a)return {}; const char* p=e->GetStringUTFChars(a,nullptr);std::string s=p?p:"";if(p)e->ReleaseStringUTFChars(a,p);return s;
}
std::string quote(const std::string& s) {
    std::string out="\"";for(unsigned char c:s){if(c=='"'||c=='\\'){out+='\\';out+=c;}else if(c<32)out+=' ';else out+=c;}return out+'"';
}
bool emit(JNIEnv* e,jobject cb,jmethodID method,const std::string& text,std::shared_ptr<Run> run) {
    if(run->cancelled)return false;if(text.empty())return true;
    auto a=e->NewByteArray(text.size());if(!a)return false;
    e->SetByteArrayRegion(a,0,text.size(),reinterpret_cast<const jbyte*>(text.data()));
    bool ok=e->CallBooleanMethod(cb,method,a);e->DeleteLocalRef(a);
    if(!ok||e->ExceptionCheck())run->cancelled=true;return ok&&!e->ExceptionCheck();
}
struct CallbackBuffer:std::streambuf {
    JNIEnv* env;jobject callback;jmethodID method;std::shared_ptr<Run> run;
    CallbackBuffer(JNIEnv* e,jobject c,jmethodID m,std::shared_ptr<Run> r):env(e),callback(c),method(m),run(std::move(r)){}
    std::streamsize xsputn(const char* p,std::streamsize n) override {emit(env,callback,method,std::string(p,n),run);return n;}
    int overflow(int c) override {if(c!=traits_type::eof()){char b=c;xsputn(&b,1);}return traits_type::not_eof(c);}
};
std::string boundarySafe(std::string text) {
    for(auto marker:{"<|im_start|>","<|im_end|>"}) {
        size_t at=0;while((at=text.find(marker,at))!=std::string::npos){text.replace(at,std::char_traits<char>::length(marker),"[message boundary]");at+=18;}
    } return text;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_androidharness_app_local_LocalNative_create(JNIEnv*,jobject){
    std::lock_guard<std::mutex> lock(handlesMutex);auto id=nextHandle++;handles[id]=std::make_shared<Run>();return id;
}
extern "C" JNIEXPORT void JNICALL Java_com_androidharness_app_local_LocalNative_cancel(JNIEnv* e,jobject,jlong id){try{get(id)->cancelled=true;}catch(const std::exception& x){fail(e,x.what());}}
extern "C" JNIEXPORT void JNICALL Java_com_androidharness_app_local_LocalNative_destroy(JNIEnv*,jobject,jlong id){std::lock_guard<std::mutex> lock(handlesMutex);handles.erase(id);}
extern "C" JNIEXPORT void JNICALL Java_com_androidharness_app_local_LocalNative_evictSession(JNIEnv*,jobject){std::lock_guard<std::mutex> lock(runtimeMutex);cached.reset();cachedKey.clear();}
extern "C" JNIEXPORT void JNICALL Java_com_androidharness_app_local_LocalNative_configureVulkanCompatibility(JNIEnv*,jobject,jboolean){}
extern "C" JNIEXPORT jstring JNICALL Java_com_androidharness_app_local_LocalNative_runtimeInfo(JNIEnv* e,jobject){std::lock_guard<std::mutex> lock(runtimeMutex);return e->NewStringUTF(info.c_str());}
extern "C" JNIEXPORT jintArray JNICALL Java_com_androidharness_app_local_LocalNative_generate(JNIEnv* e,jobject,jlong id,jbyteArray path,
 jobjectArray roles,jobjectArray contents,jint context,jint input,jint output,jint threads,jstring kv,jstring effort,
 jbyteArray projector,jobjectArray images,jobject callback,jint,jboolean,jboolean,jstring backend,jstring precision,jstring memory,jint attentionMode,jintArray preparedTokens){
    try {
        auto run=get(id);std::lock_guard<std::mutex> lock(runtimeMutex);
        if(run->cancelled)throw std::runtime_error("Inference cancelled");
        std::string model=bytes(e,path), device=string(e,backend), prec=string(e,precision), mem=string(e,memory);
        if(device!="cpu"&&device!="opencl")throw std::runtime_error("Choose CPU or Adreno OpenCL");
        if(prec!="low"&&prec!="normal")throw std::runtime_error("Unsupported precision");
        if(mem!="low"&&mem!="normal")throw std::runtime_error("Unsupported memory mode");
        if(string(e,kv)!="Q8_0")throw std::runtime_error("MNN has no Q5_0 KV cache. CPU uses INT8; OpenCL uses FP16.");
        if(attentionMode!=8&&attentionMode!=10)throw std::runtime_error("Unsupported attention mode");
        if(context<3072||context>131072||input<128||output<16||output>4096||input+output>context||threads<1||threads>8)throw std::runtime_error("Invalid context or token/thread budget");
        if(!bytes(e,projector).empty()||(images&&e->GetArrayLength(images)))throw std::runtime_error("This MNN experiment is text only");
        if(!std::filesystem::is_regular_file(model)||std::filesystem::path(model).filename()!="config.json")throw std::runtime_error("Download the complete MNN model bundle first");
        auto key=model+device+prec+mem+std::to_string(threads)+std::to_string(attentionMode);
        if(!cached||key!=cachedKey){
            cached.reset();cachedKey.clear();
            std::unique_ptr<Llm,DeleteLlm> candidate(Llm::createLLM(model));if(!candidate)throw std::runtime_error("MNN model creation failed");
            auto cacheDir=std::filesystem::path(model).parent_path()/"runtime-cache"/(device+"-"+prec+"-"+mem);std::filesystem::create_directories(cacheDir);
            std::ostringstream cfg;
            cfg<<"{\"backend_type\":"<<quote(device)<<",\"thread_num\":"<<threads<<",\"precision\":"<<quote(prec)
               <<",\"memory\":"<<quote(mem)<<",\"power\":\"high\",\"attention_mode\":"<<(device=="cpu"?attentionMode:8)
               <<",\"use_mmap\":true,\"use_cached_mmap\":false,\"kvcache_mmap\":false,\"reuse_kv\":false,\"sampler_type\":\"greedy\",\"tmp_path\":"<<quote(cacheDir.string())
               <<",\"prefix_cache_path\":"<<quote(cacheDir.string())<<",\"max_new_tokens\":"<<output<<"}";
            if(!candidate->set_config(cfg.str())||!candidate->load())throw std::runtime_error("MNN failed to load requested backend");
            __android_log_print(ANDROID_LOG_INFO,"HorizonMNN","Loaded backend=%s threads=%d precision=%s memory=%s CPU-attention=%d mmap=true",device.c_str(),threads,prec.c_str(),mem.c_str(),device=="cpu"?attentionMode:8);
            cached=std::move(candidate);cachedKey=key;
        }
        cached->reset();
        bool thinking=string(e,effort)!="low";
        std::string prompt;int count=e->GetArrayLength(roles);
        if(count!=e->GetArrayLength(contents)||count<1||count>256)throw std::runtime_error("Invalid messages");
        for(int i=0;i<count;i++) {
            auto r=static_cast<jstring>(e->GetObjectArrayElement(roles,i));auto c=static_cast<jbyteArray>(e->GetObjectArrayElement(contents,i));
            auto role=string(e,r),text=boundarySafe(bytes(e,c));e->DeleteLocalRef(r);e->DeleteLocalRef(c);
            if(role=="tool")prompt+="<|im_start|>user\n<tool_response>\n"+text+"\n</tool_response><|im_end|>\n";
            else if(role=="user"||role=="system"||role=="assistant")prompt+="<|im_start|>"+role+"\n"+(role=="assistant"?"<think>\n\n</think>\n\n":"")+text+"<|im_end|>\n";
            else throw std::runtime_error("Invalid message role");
        }
        bool k2=preparedTokens!=nullptr;
        std::string opening=thinking?"<think>\n":"<think>\n\n</think>\n\n";
        prompt+="<|im_start|>assistant\n"+opening;
        if(k2) opening=string(e,effort)=="high"?"<ifm|think>\n":(string(e,effort)=="low"?"<ifm|think_faster>\n":"<ifm|think_fast>\n");
        std::vector<int> tokens;
        if(k2) {
            auto n=e->GetArrayLength(preparedTokens);tokens.resize(n);
            e->GetIntArrayRegion(preparedTokens,0,n,reinterpret_cast<jint*>(tokens.data()));
            for(auto token:tokens)if(token<0)throw std::runtime_error("Invalid prepared token ID");
        } else tokens=cached->tokenizer_encode(prompt);
        if(tokens.empty()||tokens.size()>static_cast<size_t>(input)||tokens.size()+output>static_cast<size_t>(context))throw std::runtime_error("Tokenized prompt exceeds selected context/input budget");
        auto cls=e->GetObjectClass(callback);auto method=e->GetMethodID(cls,"onToken","([B)Z");auto tokenMethod=k2?e->GetMethodID(cls,"onTokenId","(I)Z"):nullptr;e->DeleteLocalRef(cls);if(!method || (k2&&!tokenMethod)) return nullptr;
        emit(e,callback,method,opening,run);
        if(run->cancelled){jint counts[]={static_cast<jint>(tokens.size()),0,2,0};auto result=e->NewIntArray(4);e->SetIntArrayRegion(result,0,4,counts);return result;}
        CallbackBuffer buffer(e,callback,method,run);std::ostream stream(&buffer);
        // MNN pipeline decoding strips special tokens. Preserve K2's reasoning/tool markers.
        std::ostringstream ignored;size_t emitted=0;bool messageFinished=false;
        auto emitIds=[&]() {
            if(!k2)return;
            const auto& ids=cached->getContext()->output_tokens;
            while(emitted<ids.size()&&!run->cancelled) {
                int token=ids[emitted++];
                // Original pinned K2 tokenizer: <|ifm|im_end|> = 64019.
                if(token==64019){messageFinished=true;break;}
                if(cached->is_stop(token))continue;
                bool ok=e->CallBooleanMethod(callback,tokenMethod,static_cast<jint>(token));
                if(!ok||e->ExceptionCheck())run->cancelled=true;
            }
        };
        cached->response(tokens,k2?static_cast<std::ostream*>(&ignored):&stream,"",1);
        emitIds();
        int generated=0;
        while(!run->cancelled) {
            const auto ctx=cached->getContext();generated=static_cast<int>(ctx->output_tokens.size());
            if(messageFinished||cached->stoped()||ctx->status==LlmStatus::NORMAL_FINISHED||ctx->status==LlmStatus::INTERNAL_ERROR||ctx->status==LlmStatus::TIMEOUT||generated>=output)break;
            int before=generated;cached->generate(1);emitIds();
            if(cached->getContext()->output_tokens.size()==static_cast<size_t>(before)&&!cached->stoped())throw std::runtime_error("MNN made no generation progress");
        }
        auto ctx=cached->getContext();generated=static_cast<int>(ctx->output_tokens.size());
        if(ctx->status==LlmStatus::INTERNAL_ERROR||ctx->status==LlmStatus::TIMEOUT)throw std::runtime_error("MNN execution failed; inspect runtime/backend logs");
        int finish=run->cancelled?2:(messageFinished||cached->stoped()||ctx->status==LlmStatus::NORMAL_FINISHED?0:1);
        std::ostringstream metrics;
        metrics<<"MNN CPU/ARM82 + OpenCL · requested "<<device<<(device=="cpu"?" · CPU threads ":" · OpenCL tuning flags ")<<threads<<" · precision "<<prec<<" · memory "<<mem<<" · "
               <<(device=="cpu"?(attentionMode==10?"INT8 K+V":(prec=="low"?"FP16 K+V":"FP32 K+V")):"FP16 K+V")<<" · prompt "<<tokens.size()<<" · generated "<<generated
               <<" · prefill_us "<<ctx->prefill_us<<" · decode_us "<<ctx->decode_us;
        info=metrics.str();__android_log_print(ANDROID_LOG_INFO,"HorizonMNN","%s",info.c_str());
        if(e->ExceptionCheck())return nullptr;
        jint counts[]={static_cast<jint>(tokens.size()),generated,finish,0};auto result=e->NewIntArray(4);e->SetIntArrayRegion(result,0,4,counts);return result;
    }catch(const std::exception& x){fail(e,x.what());return nullptr;}
}
