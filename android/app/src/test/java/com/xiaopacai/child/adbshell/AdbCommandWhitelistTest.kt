package com.xiaopacai.child.adbshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdbCommandWhitelistTest {

    @Test
    fun pairArgs_acceptsValidHostPortCode() {
        val args = AdbCommand.pair("192.168.1.5", 37000, "123456")
        assertNotNull(args)
        assertEquals(listOf("pair", "192.168.1.5:37000", "123456"), args)
    }

    @Test
    fun pairArgs_rejectsNonNumericPort() {
        assertNull(AdbCommand.pair("192.168.1.5", -1, "123456"))
        assertNull(AdbCommand.pair("192.168.1.5", 65536, "123456"))
    }

    @Test
    fun pairArgs_rejectsCodeNotSixDigits() {
        assertNull(AdbCommand.pair("192.168.1.5", 37000, "12345"))
        assertNull(AdbCommand.pair("192.168.1.5", 37000, "12345a"))
    }

    @Test
    fun pairArgs_rejectsHostWithShellMetacharacters() {
        assertNull(AdbCommand.pair("192.168.1.5;rm -rf /", 37000, "123456"))
        assertNull(AdbCommand.pair("", 37000, "123456"))
    }

    @Test
    fun connectArgs_acceptsValidHostPort() {
        assertEquals(listOf("connect", "192.168.1.5:33791"), AdbCommand.connect("192.168.1.5", 33791))
    }

    @Test
    fun devicesArgs_areFixed() {
        assertEquals(listOf("devices"), AdbCommand.devices())
    }

    @Test
    fun tcpipArgs_onlyAllowsPort5555() {
        assertEquals(listOf("tcpip", "5555"), AdbCommand.tcpip(5555))
        assertNull(AdbCommand.tcpip(5037))
    }

    @Test
    fun shellArgs_allowsOnlyWhitelistedCommands() {
        val allowed = AdbCommand.shell(
            "127.0.0.1:5555",
            "dpm set-device-owner com.xiaopacai.child/.service.GuardianDeviceAdminReceiver"
        )
        assertNotNull(allowed)
        assertEquals(
            listOf(
                "-s", "127.0.0.1:5555", "shell",
                "dpm set-device-owner com.xiaopacai.child/.service.GuardianDeviceAdminReceiver"
            ),
            allowed
        )
        assertNotNull(AdbCommand.shell("127.0.0.1:5555", "getprop ro.build.version.release"))
    }

    @Test
    fun shellArgs_rejectsNonWhitelistedCommands() {
        assertNull(AdbCommand.shell("127.0.0.1:5555", "rm -rf /"))
        assertNull(AdbCommand.shell("127.0.0.1:5555", "su -c whoami"))
        assertNull(AdbCommand.shell("127.0.0.1:5555", "reboot"))
        assertNull(AdbCommand.shell("127.0.0.1:5555", "dpm set-device-owner evil.pkg/.Receiver"))
        assertNull(AdbCommand.shell("127.0.0.1:5555", "pm uninstall com.xiaopacai.child"))
    }

    @Test
    fun versionAndKillServerProduceExpectedArgs() {
        assertEquals(listOf("version"), AdbCommand.version())
        assertEquals(listOf("kill-server"), AdbCommand.killServer())
    }
}
