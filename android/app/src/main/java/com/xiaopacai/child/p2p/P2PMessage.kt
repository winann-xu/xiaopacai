package com.xiaopacai.child.p2p

import org.json.JSONObject

/**
 * [TASK-D1-04] P2P 消息模型
 *
 * JSON 消息的 Kotlin 表示。
 * 支持与 JSON 字符串的双向转换。
 *
 * @param type 消息类型
 * @param payload 消息载荷（Map 结构，序列化为 JSON Object）
 */
data class P2PMessage(
    val type: String,
    val payload: Map<String, Any> = emptyMap()
) {
    /** 序列化为 JSON 字节数组 */
    fun toJsonBytes(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }

    /** 序列化为 JSON 字符串 */
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type)
        val payloadObj = JSONObject()
        payload.forEach { (key, value) ->
            payloadObj.put(key, value)
        }
        json.put("payload", payloadObj)
        return json.toString()
    }

    companion object {
        /** 从 JSON 字符串反序列化 */
        fun fromJson(json: String): P2PMessage {
            val obj = JSONObject(json)
            val type = obj.getString("type")
            val payloadObj = obj.optJSONObject("payload") ?: JSONObject()

            val payload = mutableMapOf<String, Any>()
            payloadObj.keys().forEach { key ->
                payload[key] = payloadObj.get(key)
            }
            // [TASK-PRELAUNCH-FIX-SCAN] Web 服务端拒绝帧（handshake_rejected）的
            // error / error_code 为顶层字段且无 payload；并入 payload 便于统一解析。
            // payload 内同名键优先（不与现有帧冲突）
            obj.keys().forEach { key ->
                if (key != "type" && key != "payload" && !payload.containsKey(key)) {
                    payload[key] = obj.get(key)
                }
            }

            return P2PMessage(type, payload)
        }
    }
}
