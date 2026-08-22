package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable

@Serializable
enum class AiNetworkMode(val displayName: String) {
    DEFAULT("默认（HTTP/2 + HTTP/1.1）"),
    HTTP1_ONLY("兼容模式（仅 HTTP/1.1）"),
    FRESH_CONNECTION("新连接模式（禁用 AI 连接复用）"),
}
