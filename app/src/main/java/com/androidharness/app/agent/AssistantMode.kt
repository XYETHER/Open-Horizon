package com.androidharness.app.agent

import kotlinx.serialization.Serializable

@Serializable
enum class AssistantMode(val title: String) {
    CHAT("Chat"),
    RESEARCH("Research"),
    CODE("Coding"),
}

internal fun ThinkingLevel.k2ReasoningEffort(): String = when (this) {
    ThinkingLevel.OFF, ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    else -> "high"
}
