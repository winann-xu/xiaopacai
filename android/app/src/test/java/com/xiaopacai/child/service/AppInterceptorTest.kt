package com.xiaopacai.child.service

import org.junit.Assert.*
import org.junit.Test

/**
 * [TASK-OPT-7] AppInterceptor partial 停用判定单测
 *
 * 覆盖：黑名单始终拦截 / 白名单豁免 / 分类限额用尽拦截 /
 * 学习类放行 / 游戏社交视频拦截 / 其他放行
 */
class AppInterceptorTest {

    @Test
    fun `partial - blacklist always intercepts`() {
        val result = AppInterceptor.decidePartialIntercept(
            category = "study",
            isBlacklisted = true,
            isWhitelisted = false,
            categoryExceeded = false
        )
        assertTrue(result.intercept)
        assertEquals("blacklist", result.reason)
    }

    @Test
    fun `partial - whitelist exempts even game`() {
        val result = AppInterceptor.decidePartialIntercept(
            category = "game",
            isBlacklisted = false,
            isWhitelisted = true,
            categoryExceeded = false
        )
        assertFalse(result.intercept)
        assertEquals("whitelist", result.reason)
    }

    @Test
    fun `partial - category limit exceeded intercepts`() {
        val result = AppInterceptor.decidePartialIntercept(
            category = "video",
            isBlacklisted = false,
            isWhitelisted = false,
            categoryExceeded = true
        )
        assertTrue(result.intercept)
        assertEquals("category-limit", result.reason)
    }

    @Test
    fun `partial - study app always allowed`() {
        for (category in listOf("study", "learning")) {
            val result = AppInterceptor.decidePartialIntercept(
                category = category,
                isBlacklisted = false,
                isWhitelisted = false,
                categoryExceeded = false
            )
            assertFalse(result.intercept)
            assertEquals("study", result.reason)
        }
    }

    @Test
    fun `partial - game social video intercepted by default`() {
        for (category in listOf("game", "social", "video")) {
            val result = AppInterceptor.decidePartialIntercept(
                category = category,
                isBlacklisted = false,
                isWhitelisted = false,
                categoryExceeded = false
            )
            assertTrue(result.intercept)
            assertTrue(result.reason.startsWith("partial-"))
        }
    }

    @Test
    fun `partial - other category allowed`() {
        val result = AppInterceptor.decidePartialIntercept(
            category = "other",
            isBlacklisted = false,
            isWhitelisted = false,
            categoryExceeded = false
        )
        assertFalse(result.intercept)
        assertEquals("other", result.reason)
    }

    @Test
    fun `partial - empty category treated as other`() {
        val result = AppInterceptor.decidePartialIntercept(
            category = "",
            isBlacklisted = false,
            isWhitelisted = false,
            categoryExceeded = false
        )
        assertFalse(result.intercept)
    }
}
