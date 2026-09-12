// [android-tv] P2P 帧模型 —— 与 Web 服务端 P2P 协议（4 字节大端长度前缀 + JSON 帧）
// 小趴菜 TVOS 版
//
// 协议来源：xiaopacai-web/server/P2P/P2pProtocol.cs / P2pListenerService.cs
//   - 客户端 → 服务端：{ "type": "...", "payload": { ... } }
//   - 服务端 → 客户端：同上（type 可为 policy_update / announcement_push / limit_reset /
//     announcement_clear / update_available / heartbeat_ack，拒绝帧为顶层 handshake_rejected）
//
// 本文件复刻手机版 core/p2p/P2PMessage.kt 的行为（含 handshake_rejected 顶层字段并入 payload）。

package com.xiaopacai.tvos.p2p

import org.json.JSONObject

/** 一条 P2P 消息（type + payload） */
data class TvP2pMessage(
    val type: String,
    val payload: Map<String, Any> = emptyMap()
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("type", type)
        root.put("payload", JSONObject(payload))
        return root.toString()
    }

    companion object {
        /** 从 JSON 字符串解析；顶层非 type/payload 的键（如拒绝帧的 error_code）并入 payload */
        fun fromJson(json: String): TvP2pMessage {
            val obj = JSONObject(json)
            val type = obj.optString("type", "")
            val payloadObj = obj.optJSONObject("payload") ?: JSONObject()
            val payload = mutableMapOf<String, Any>()
            payloadObj.keys().forEach { key -> payload[key] = payloadObj.get(key) }
            obj.keys().forEach { key ->
                if (key != "type" && key != "payload" && !payload.containsKey(key)) {
                    payload[key] = obj.get(key)
                }
            }
            return TvP2pMessage(type, payload)
        }
    }
}
