package com.xiaopacai.child.adbshell

import java.util.regex.Pattern

/**
 * [TASK-STRICT-PROVISION-V1] ADB 命令构造与白名单（ADR 0018）
 *
 * 所有命令参数必须先经过本类构造/校验，禁止拼接任意 shell 命令。
 * 即使 ProcessBuilder 不经过 shell 解释，仍按纵深防御做字符级白名单。
 */
object AdbCommand {
    const val DEVICE_OWNER_COMPONENT = "com.xiaopacai.child/.service.GuardianDeviceAdminReceiver"
    const val LOOPBACK_PORT = 5555

    private val HOST_RE = Pattern.compile("^[A-Za-z0-9.-]+$")
    private val CODE_RE = Pattern.compile("^\\d{6}$")

    /** 无线调试配对：adb pair host:port 6位配对码 */
    fun pair(host: String, port: Int, code: String): List<String>? {
        if (!isValidHost(host) || !isValidPort(port) || !CODE_RE.matcher(code).matches()) return null
        return listOf("pair", "$host:$port", code)
    }

    /** 连接指定主机端口（无线调试端口或回环 5555） */
    fun connect(host: String, port: Int): List<String>? {
        if (!isValidHost(host) || !isValidPort(port)) return null
        return listOf("connect", "$host:$port")
    }

    /** 列出已连接设备（配对后 adb server 会自动回连设备，用于获取 serial） */
    fun devices(): List<String> = listOf("devices")

    /** 仅允许切换到固定回环端口 5555 */
    fun tcpip(port: Int): List<String>? {
        if (port != LOOPBACK_PORT) return null
        return listOf("tcpip", port.toString())
    }

    /** 仅允许执行白名单 shell 命令（如 dpm 预置 / 只读属性） */
    fun shell(serial: String, command: String): List<String>? {
        if (serial.isBlank() || !isAllowedShellCommand(command)) return null
        return listOf("-s", serial, "shell", command)
    }

    fun version(): List<String> = listOf("version")

    fun killServer(): List<String> = listOf("kill-server")

    fun isAllowedShellCommand(command: String): Boolean {
        val trimmed = command.trim()
        return trimmed == "dpm set-device-owner $DEVICE_OWNER_COMPONENT" ||
            trimmed == "getprop ro.build.version.release"
    }

    private fun isValidHost(host: String): Boolean =
        host.isNotBlank() && HOST_RE.matcher(host).matches()

    private fun isValidPort(port: Int): Boolean = port in 1..65535
}
