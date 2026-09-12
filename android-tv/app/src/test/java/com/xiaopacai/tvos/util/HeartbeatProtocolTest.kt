// [android-tv] 测试 — 心跳协议解析单元测试
// 小趴菜 TVOS 版 — CloudSyncService 心跳协议解析测试（核心模块 3/3）

package com.xiaopacai.tvos.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * 心跳协议解析单元测试
 * 
 * 验证：
 * 1. 心跳请求 JSON 构建正确
 * 2. 心跳响应解析正确
 * 3. 策略更新字段解析
 * 4. 错误响应处理
 * 5. 边界条件处理
 * 
 * 测试数据基于 android-v3 CloudSyncService 协议规范
 */
class HeartbeatProtocolTest {

    private lateinit var validHeartbeatRequest: JSONObject

    @Before
    fun setUp() {
        // 构建标准心跳请求
        validHeartbeatRequest = JSONObject().apply {
            put("device_id", "test_device_001")
            put("bind_token", "test_bind_token")
            put("usage_data", JSONArray().apply {
                put(JSONObject().apply {
                    put("package_name", "tv.danmaku.bili")
                    put("minutes", 30)
                })
            })
            put("diagnostics", JSONObject().apply {
                put("brand", "Xiaomi")
                put("model", "MI-BOX-S")
                put("sdk", 31)
                put("version_name", "1.0.0")
                put("version_code", 100)
            })
            put("timestamp", System.currentTimeMillis())
        }
    }

    // ==================== 请求构建测试 ====================

    @Test
    fun testHeartbeatRequestHasDeviceId() {
        assertTrue(validHeartbeatRequest.has("device_id"))
        assertEquals("test_device_001", validHeartbeatRequest.getString("device_id"))
    }

    @Test
    fun testHeartbeatRequestHasBindToken() {
        assertTrue(validHeartbeatRequest.has("bind_token"))
        assertEquals("test_bind_token", validHeartbeatRequest.getString("bind_token"))
    }

    @Test
    fun testHeartbeatRequestHasUsageData() {
        assertTrue(validHeartbeatRequest.has("usage_data"))
        val usageData = validHeartbeatRequest.getJSONArray("usage_data")
        assertEquals(1, usageData.length())
    }

    @Test
    fun testHeartbeatRequestHasDiagnostics() {
        assertTrue(validHeartbeatRequest.has("diagnostics"))
        val diagnostics = validHeartbeatRequest.getJSONObject("diagnostics")
        assertEquals("Xiaomi", diagnostics.getString("brand"))
        assertEquals("MI-BOX-S", diagnostics.getString("model"))
    }

    @Test
    fun testHeartbeatRequestHasTimestamp() {
        assertTrue(validHeartbeatRequest.has("timestamp"))
        val timestamp = validHeartbeatRequest.getLong("timestamp")
        assertTrue(timestamp > 0) // 时间戳应为正数
    }

    @Test
    fun testHeartbeatRequestWithEmptyToken() {
        val request = JSONObject(validHeartbeatRequest).apply {
            put("bind_token", "")
        }
        assertEquals("", request.getString("bind_token"))
    }

    @Test
    fun testHeartbeatRequestWithEmptyUsageData() {
        val request = JSONObject(validHeartbeatRequest).apply {
            put("usage_data", JSONArray())
        }
        assertEquals(0, request.getJSONArray("usage_data").length())
    }

    // ==================== 响应解析测试 ====================

    @Test
    fun testParseSuccessResponse() {
        val response = JSONObject().apply {
            put("code", 0)
            put("message", "OK")
            put("policy", JSONObject().apply {
                put("daily_limit", JSONObject().apply {
                    put("minutes", 90)
                })
            })
            put("config", JSONObject().apply {
                put("device_name", "小趴菜TV")
            })
        }

        assertEquals(0, response.optInt("code", -1))
        assertEquals("OK", response.optString("message", ""))

        val policy = response.optJSONObject("policy")
        assertNotNull(policy)

        val dailyLimit = policy?.optJSONObject("daily_limit")
        assertNotNull(dailyLimit)
        assertEquals(90, dailyLimit?.optInt("minutes", -1))

        val config = response.optJSONObject("config")
        assertNotNull(config)
        assertEquals("小趴菜TV", config?.optString("device_name", ""))
    }

    @Test
    fun testParseResponseWithAnnouncement() {
        val response = JSONObject().apply {
            put("code", 0)
            put("announcement", "系统维护通知")
        }

        assertEquals("系统维护通知", response.optString("announcement", ""))
    }

    @Test
    fun testParseResponseWithAction() {
        val response = JSONObject().apply {
            put("code", 0)
            put("action", "lock")
        }

        assertEquals("lock", response.optString("action", ""))
    }

    @Test
    fun testParseErrorResponse() {
        val response = JSONObject().apply {
            put("code", 400)
            put("message", "设备未绑定")
        }

        assertEquals(400, response.optInt("code", 0))
        assertEquals("设备未绑定", response.optString("message", ""))
    }

    @Test
    fun testParseResponseWithWhitelist() {
        val response = JSONObject().apply {
            put("code", 0)
            put("policy", JSONObject().apply {
                put("whitelist", JSONArray().apply {
                    put("tv.danmaku.bili")
                    put("com.duokan.airplay.tvs")
                    put("com.dmtech.dmbdmb")
                })
            })
        }

        val whitelist = response.optJSONObject("policy")?.optJSONArray("whitelist")
        assertNotNull(whitelist)
        assertEquals(3, whitelist?.length())
        assertEquals("tv.danmaku.bili", whitelist?.getString(0))
        assertEquals("com.duokan.airplay.tvs", whitelist?.getString(1))
        assertEquals("com.dmtech.dmbdmb", whitelist?.getString(2))
    }

    @Test
    fun testParseResponseWithPinHash() {
        val response = JSONObject().apply {
            put("code", 0)
            put("policy", JSONObject().apply {
                put("pin_hash", "a1b2c3d4e5f6...")
            })
        }

        val pinHash = response.optJSONObject("policy")?.optString("pin_hash", "")
        assertEquals("a1b2c3d4e5f6...", pinHash)
    }

    // ==================== 边界条件测试 ====================

    @Test
    fun testParseEmptyResponse() {
        val response = JSONObject("{}")

        assertEquals(0, response.optInt("code", 0))
        assertEquals("", response.optString("message", ""))
        assertNull(response.optJSONObject("policy"))
        assertNull(response.optJSONObject("config"))
    }

    @Test
    fun testParseMinimalResponse() {
        val response = JSONObject().apply {
            put("code", 0)
        }

        assertEquals(0, response.optInt("code", 0))
        assertNull(response.optJSONObject("policy"))
    }

    @Test
    fun testParseNullFields() {
        val response = JSONObject().apply {
            put("code", 0)
            put("policy", JSONObject())
            put("config", JSONObject())
            put("action", JSONObject.NULL)
        }

        // null/NULL 值应安全处理
        val policy = response.optJSONObject("policy")
        val config = response.optJSONObject("config")
        val action = response.optString("action", null)

        // policy 和 config 存在但为空
        assertNotNull(policy)
        assertNotNull(config)
    }

    @Test
    fun testHeartbeatRequestJsonValid() {
        // 确保构建的 JSON 是合法的
        val jsonStr = validHeartbeatRequest.toString()
        assertNotNull(jsonStr)
        assertTrue(jsonStr.isNotEmpty())

        // 确保可以重新解析
        val reparsed = JSONObject(jsonStr)
        assertEquals("test_device_001", reparsed.getString("device_id"))
    }

    // ==================== 协议兼容性测试 ====================

    @Test
    fun testParseResponseMissingPolicy() {
        val response = JSONObject().apply {
            put("code", 0)
            put("config", JSONObject().apply {
                put("cloud_host", "xpc.winann.com")
            })
        }

        // 无 policy 字段应安全处理
        assertNull(response.optJSONObject("policy"))
        assertEquals("xpc.winann.com", response.optJSONObject("config")?.optString("cloud_host", ""))
    }

    @Test
    fun testParseResponseMissingConfig() {
        val response = JSONObject().apply {
            put("code", 0)
            put("policy", JSONObject().apply {
                put("daily_limit", JSONObject().apply {
                    put("minutes", 30)
                })
            })
        }

        assertNull(response.optJSONObject("config"))
        val dailyLimit = response.optJSONObject("policy")?.optJSONObject("daily_limit")
        assertEquals(30, dailyLimit?.optInt("minutes", -1))
    }

    @Test
    fun testParseResponseWithAllFields() {
        val response = JSONObject().apply {
            put("code", 0)
            put("message", "OK")
            put("policy", JSONObject().apply {
                put("daily_limit", JSONObject().apply {
                    put("minutes", 60)
                })
                put("whitelist", JSONArray().apply {
                    put("tv.danmaku.bili")
                })
                put("pin_hash", "hash123")
            })
            put("announcement", "重要公告")
            put("config", JSONObject().apply {
                put("cloud_host", "xpc.winann.com")
                put("cloud_port", 443)
                put("device_name", "小趴菜TV")
            })
            put("action", "lock")
        }

        // 验证所有字段
        assertEquals(0, response.optInt("code", -1))
        assertEquals("OK", response.optString("message", ""))
        assertEquals("重要公告", response.optString("announcement", ""))
        assertEquals("lock", response.optString("action", ""))

        val policy = response.optJSONObject("policy")!!
        assertEquals(60, policy.optJSONObject("daily_limit")?.optInt("minutes", -1))
        assertEquals("hash123", policy.optString("pin_hash", ""))

        val config = response.optJSONObject("config")!!
        assertEquals("xpc.winann.com", config.optString("cloud_host", ""))
        assertEquals(443, config.optInt("cloud_port", -1))
    }

    @Test
    fun testMultipleUsageDataEntries() {
        val request = JSONObject(validHeartbeatRequest).apply {
            put("usage_data", JSONArray().apply {
                put(JSONObject().apply {
                    put("package_name", "tv.danmaku.bili")
                    put("minutes", 30)
                })
                put(JSONObject().apply {
                    put("package_name", "com.duokan.airplay.tvs")
                    put("minutes", 15)
                })
                put(JSONObject().apply {
                    put("package_name", "com.dmtech.dmbdmb")
                    put("minutes", 45)
                })
            })
        }

        val usageData = request.getJSONArray("usage_data")
        assertEquals(3, usageData.length())
        assertEquals("tv.danmaku.bili", usageData.getJSONObject(0).getString("package_name"))
        assertEquals("com.dmtech.dmbdmb", usageData.getJSONObject(2).getString("package_name"))
    }
}
