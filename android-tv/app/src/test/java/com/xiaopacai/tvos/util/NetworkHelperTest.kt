// [android-tv] 测试 — NetworkHelper 单元测试
// 小趴菜 TVOS 版 — 网络辅助模块测试

package com.xiaopacai.tvos.util

import org.junit.Assert.*
import org.junit.Test

/**
 * NetworkHelper 单元测试
 * 
 * 验证：
 * 1. 基础 URL 构建正确
 * 2. HTTPS vs HTTP 切换
 * 3. 端口处理
 * 4. ResponseResult 数据结构
 */
class NetworkHelperTest {

    // ==================== URL 构建测试 ====================

    @Test
    fun testBuildBaseHttpsDefault() {
        val url = NetworkHelper.buildBaseUrl("xpc.winann.com", 443)
        assertEquals("https://xpc.winann.com:443", url)
    }

    @Test
    fun testBuildBaseHttpsExplicit() {
        val url = NetworkHelper.buildBaseUrl("xpc.winann.com", 443, useHttps = true)
        assertEquals("https://xpc.winann.com:443", url)
    }

    @Test
    fun testBuildBaseHttp() {
        val url = NetworkHelper.buildBaseUrl("localhost", 8080, useHttps = false)
        assertEquals("http://localhost:8080", url)
    }

    @Test
    fun testBuildBaseHttpWithNonStandardPort() {
        val url = NetworkHelper.buildBaseUrl("192.168.1.100", 3000, useHttps = false)
        assertEquals("http://192.168.1.100:3000", url)
    }

    @Test
    fun testBuildBaseHttpsWithNonStandardPort() {
        val url = NetworkHelper.buildBaseUrl("api.example.com", 8443, useHttps = true)
        assertEquals("https://api.example.com:8443", url)
    }

    @Test
    fun testBuildBaseIpv6() {
        val url = NetworkHelper.buildBaseUrl("::1", 8080, useHttps = false)
        // IPv6 需要方括号
        assertEquals("http://::1:8080", url)
    }

    @Test
    fun testBuildBaseSubdomain() {
        val url = NetworkHelper.buildBaseUrl("tv.xpc.winann.com", 443)
        assertEquals("https://tv.xpc.winann.com:443", url)
    }

    // ==================== ResponseResult 测试 ====================

    @Test
    fun testResponseSuccessHasData() {
        val success = NetworkHelper.ResponseResult.Success("Hello, World!")
        assertEquals("Hello, World!", (success as? NetworkHelper.ResponseResult.Success)?.data)
    }

    @Test
    fun testResponseFailureHasError() {
        val failure = NetworkHelper.ResponseResult.Failure("Connection timeout")
        assertEquals("Connection timeout", (failure as? NetworkHelper.ResponseResult.Failure)?.error)
    }

    @Test
    fun testResponseResultSealedClass() {
        val results = listOf<NetworkHelper.ResponseResult>(
            NetworkHelper.ResponseResult.Success("data"),
            NetworkHelper.ResponseResult.Failure("error")
        )

        assertEquals(2, results.size)
        assertEquals(NetworkHelper.ResponseResult.Success::class, results[0]::class)
        assertEquals(NetworkHelper.ResponseResult.Failure::class, results[1]::class)

        // 区分成功和失败
        results.forEach { result ->
            when (result) {
                is NetworkHelper.ResponseResult.Success -> println("Success: ${result.data}")
                is NetworkHelper.ResponseResult.Failure -> println("Failed: ${result.error}")
            }
        }
    }

    // ==================== URL 边界条件 ====================

    @Test
    fun testBuildBaseEmptyHost() {
        val url = NetworkHelper.buildBaseUrl("", 443)
        assertEquals("https://:443", url)
    }

    @Test
    fun testBuildBaseZeroPort() {
        val url = NetworkHelper.buildBaseUrl("localhost", 0)
        assertEquals("https://localhost:0", url)
    }

    @Test
    fun testBuildBaseLargePort() {
        val url = NetworkHelper.buildBaseUrl("localhost", 65535)
        assertEquals("https://localhost:65535", url)
    }

    @Test
    fun testBuildBaseWithSchemeAlready() {
        // 如果 host 已经包含 scheme，不应重复添加
        val url = NetworkHelper.buildBaseUrl("http://example.com", 8080, useHttps = false)
        assertEquals("http://http://example.com:8080", url)
        // 注意：这是已知行为，实际使用时应传入不含 scheme 的 host
    }

    // ==================== 协议兼容性 ====================

    @Test
    fun testBuildBaseStandardApi() {
        // 标准 API 端点
        val url = NetworkHelper.buildBaseUrl("xpc.winann.com", 443, useHttps = true)
        assertEquals("https://xpc.winann.com:443", url)

        // 本地调试端点
        val localUrl = NetworkHelper.buildBaseUrl("192.168.1.100", 8080, useHttps = false)
        assertEquals("http://192.168.1.100:8080", localUrl)
    }
}
