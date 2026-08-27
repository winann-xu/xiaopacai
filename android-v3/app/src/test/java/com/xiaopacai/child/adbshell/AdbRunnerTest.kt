package com.xiaopacai.child.adbshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdbRunnerTest {

    private fun runner(
        binary: String? = "/data/app/.../lib/arm64/libadb.so",
        onExec: (List<String>, Map<String, String>) -> AdbResult = { _, _ -> AdbResult(0, "ok") }
    ): AdbRunner = AdbRunner(
        binaryPath = { binary },
        socketName = "xiaopacai_adb",
        homeDir = { "/data/user/0/com.xiaopacai.child/files" },
        executor = onExec
    )

    @Test
    fun pairInvokesExecutorWithWhitelistedArgsAndEnv() {
        var captured: List<String>? = null
        var capturedEnv: Map<String, String>? = null
        val r = runner(onExec = { args, env ->
            captured = args; capturedEnv = env; AdbResult(0, "Successfully paired")
        })
        val result = r.pair("192.168.1.5", 37000, "123456")
        assertNotNull(result)
        assertEquals(listOf("pair", "192.168.1.5:37000", "123456"), captured)
        assertNotNull(capturedEnv)
        assertEquals("localabstract:xiaopacai_adb", capturedEnv?.get("ADB_SERVER_SOCKET"))
        assertEquals("/data/user/0/com.xiaopacai.child/files", capturedEnv?.get("HOME"))
    }

    @Test
    fun pairReturnsNullWhenBinaryMissing() {
        val r = runner(binary = null)
        assertNull(r.pair("192.168.1.5", 37000, "123456"))
    }

    @Test
    fun pairReturnsNullWhenArgsInvalid() {
        var called = false
        val r = runner(onExec = { _, _ -> called = true; AdbResult(0, "") })
        assertNull(r.pair("192.168.1.5;evil", 37000, "123456"))
        assertEquals(false, called)
    }

    @Test
    fun shellRejectsNonWhitelistedCommand() {
        val r = runner()
        assertNull(r.shell("127.0.0.1:5555", "rm -rf /"))
    }

    @Test
    fun killServerInvokesExecutor() {
        var captured: List<String>? = null
        val r = runner(onExec = { args, _ -> captured = args; AdbResult(0, "") })
        r.killServer()
        assertEquals(listOf("kill-server"), captured)
    }
}
