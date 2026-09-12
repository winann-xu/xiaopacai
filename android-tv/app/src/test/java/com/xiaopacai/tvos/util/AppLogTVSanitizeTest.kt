// [android-tv] 单测 — 【项8】日志脱敏（AppLogTV.sanitize）
// 小趴菜 TVOS 版
//
// 为什么必须有这组用例：上传的运行日志会离开设备，一旦脱敏失效就等于把家长邮箱、
// 口令、设备令牌送到服务器。这里把「必须打码」的四类内容钉死，防止以后改日志时回归。

package com.xiaopacai.tvos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTVSanitizeTest {

    @Test
    fun `邮箱保留域名首字母打码`() {
        val out = AppLogTV.sanitize("家长账号 xwag14@126.com 登录成功")
        assertEquals("家长账号 x***@126.com 登录成功", out)
    }

    @Test
    fun `手机号中间打码`() {
        val out = AppLogTV.sanitize("绑定手机 13812345678 完成")
        assertEquals("绑定手机 138****5678 完成", out)
    }

    @Test
    fun `JWT 与 Bearer 令牌整体打码`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMiJ9.abcdef123456"
        val out1 = AppLogTV.sanitize("token=$jwt")
        assertFalse("JWT 未打码: $out1", out1.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse("JWT 签名段泄漏: $out1", out1.contains("abcdef123456"))
        // 先被 JWT 规则打码，随后又被「密钥字段」规则覆盖，两者都算合格
        assertTrue("未见打码标记: $out1", out1.contains("[已打码]") || out1.contains("[JWT已打码]"))

        val out2 = AppLogTV.sanitize("Authorization: Bearer ABCDEFGHIJKLmnop1234")
        assertFalse("Bearer 未打码: $out2", out2.contains("ABCDEFGHIJKLmnop1234"))
        assertTrue(out2.contains("[已打码]"))
    }

    @Test
    fun `密码与令牌字段的值被打码`() {
        val out = AppLogTV.sanitize("""{"Username":"xwag14@126.com","Password":"secret123","token":"deadbeefcafe"}""")
        assertFalse("密码明文泄漏: $out", out.contains("secret123"))
        assertFalse("令牌明文泄漏: $out", out.contains("deadbeefcafe"))
        assertTrue(out.contains("[已打码]"))
    }

    @Test
    fun `本机绝对路径被折叠`() {
        val out = AppLogTV.sanitize("读取 /Users/winann/01-project/05-codex_project/projects/x/app.gguf 失败")
        assertFalse("路径泄漏: $out", out.contains("/Users/winann"))
        assertTrue(out.contains("[本地路径]"))
    }

    @Test
    fun `超长内容被截断`() {
        val out = AppLogTV.sanitize("字".repeat(1200))
        assertTrue("未截断，长度 ${out.length}", out.length <= 810)
    }
}
