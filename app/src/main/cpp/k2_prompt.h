#pragma once

#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace harness_local {

// The GGUF publisher's template requires structured Jinja fields unavailable in
// llama_chat_apply_template. The provider supplies serialized tools and calls;
// apply the same text framing with an empty historical reasoning field.
// https://github.com/MBZUAI-IFM/llama.cpp/blob/42adf019f76013dac873b5b43950d54d5ab27216/models/templates/k2-horizon.jinja
inline std::string k2UntrustedContent(std::string content) {
    // Downloaded pages and user text cannot introduce role/tool delimiters when
    // the complete prompt is tokenized with parse_special enabled.
    const std::pair<const char *, const char *> markers[] = {
        {"<|ifm|", "< |ifm|"}, {"<ifm|", "< ifm|"}, {"</ifm|", "</ ifm|"}
    };
    for (const auto & marker : markers) {
        size_t offset = 0;
        while ((offset = content.find(marker.first, offset)) != std::string::npos) {
            content.replace(offset, std::char_traits<char>::length(marker.first), marker.second);
            offset += std::char_traits<char>::length(marker.second);
        }
    }
    return content;
}

inline std::string k2ThinkingOpening(const std::string & reasoningEffort) {
    if (reasoningEffort == "low") return "<ifm|think_faster>\n";
    if (reasoningEffort == "medium") return "<ifm|think_fast>\n";
    if (reasoningEffort == "high") return "<ifm|think>\n";
    throw std::runtime_error("Unsupported reasoning effort. Choose low, medium or high.");
}

inline std::string k2Prompt(const std::vector<std::string> & roles,
                            const std::vector<std::string> & contents,
                            const std::string & reasoningEffort = "low") {
    if (roles.size() != contents.size()) throw std::runtime_error("Invalid K2 chat messages.");
    const auto thinkingOpening = k2ThinkingOpening(reasoningEffort);
    std::string prompt = "<|ifm|begin_of_text|>";
    for (size_t i = 0; i < roles.size(); ++i) {
        const auto & role = roles[i];
        if (role != "system" && role != "user" && role != "assistant" && role != "tool")
            throw std::runtime_error("Unsupported K2 message role.");
        prompt += "<|ifm|im_start|>" + role;
        if (role == "assistant") {
            prompt += "<ifm|think>\n</ifm|think>\n";
        } else {
            prompt += "\n";
        }
        auto content = role == "user" || role == "tool" ? k2UntrustedContent(contents[i]) : contents[i];
        if (role == "assistant") {
            const auto first = content.find_first_not_of('\n');
            content = first == std::string::npos ? "" : content.substr(first);
        }
        prompt += content;
        prompt += "<|ifm|im_end|>";
    }
    prompt += "<|ifm|im_start|>assistant\n" + thinkingOpening;
    return prompt;
}

} // namespace harness_local
