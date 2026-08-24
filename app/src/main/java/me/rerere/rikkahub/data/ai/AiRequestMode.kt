package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable

@Serializable
enum class AiRequestMode(val displayName: String) {
    NORMAL("正常"),
    MINIMAL_OPENAI_COMPATIBLE("最小 OpenAI Compatible"),
}
