package com.xiaopacai.child.adbshell

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [TASK-STRICT-PROVISION-V1] adb 二进制执行器（LADB 模式，ADR 0018）
 *
 * - 从 nativeLibraryDir 执行官方 adb（libadb.so，三 ABI 内置）；
 * - 通过 ADB_SERVER_SOCKET=localabstract:<socket> 隔离本地 adb server，避免占用 5037；
 * - HOME 指向应用私有目录，保证 RSA 密钥持久化（配对授权后重启仍可复用）；
 * - 所有命令先经 AdbCommand 白名单校验，无效命令直接返回 null 不执行。
 */
data class AdbResult(val exitCode: Int, val output: String)

class AdbRunner(
    private val binaryPath: () -> String?,
    private val socketName: String,
    private val homeDir: () -> String,
    private val executor: (List<String>, Map<String, String>) -> AdbResult
) {
    fun pair(host: String, port: Int, code: String): AdbResult? =
        AdbCommand.pair(host, port, code)?.let { run(it) }

    fun connect(host: String, port: Int): AdbResult? =
        AdbCommand.connect(host, port)?.let { run(it) }

    fun devices(): AdbResult? = run(AdbCommand.devices())

    fun tcpip(serial: String, port: Int): AdbResult? =
        AdbCommand.tcpip(port)?.let { run(listOf("-s", serial) + it) }

    fun shell(serial: String, command: String): AdbResult? =
        AdbCommand.shell(serial, command)?.let { run(it) }

    fun version(): AdbResult? = run(AdbCommand.version())

    fun killServer(): AdbResult? = run(AdbCommand.killServer())

    private fun run(args: List<String>): AdbResult? {
        val binary = binaryPath() ?: return null
        val env = mapOf(
            "ADB_SERVER_SOCKET" to "localabstract:$socketName",
            "HOME" to homeDir()
        )
        return executor(args, env)
    }

    companion object {
        const val SOCKET_NAME = "xiaopacai_adb"

        /** 生产构造：ProcessBuilder 执行内置 libadb.so */
        fun create(context: Context, timeoutSeconds: Long = 30): AdbRunner {
            val binaryPath: () -> String? = {
                val dir = context.applicationInfo.nativeLibraryDir
                val file = File(dir, "libadb.so")
                if (file.exists()) file.absolutePath else null
            }
            val homePath: () -> String = { context.filesDir.absolutePath }
            val executor: (List<String>, Map<String, String>) -> AdbResult = { args, env ->
                val path = binaryPath()
                if (path == null) {
                    AdbResult(-1, "libadb.so missing")
                } else {
                    try {
                        val pb = ProcessBuilder(listOf(path) + args)
                        pb.environment().putAll(env)
                        val proc = pb.start()
                        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                        if (!finished) {
                            proc.destroyForcibly()
                            AdbResult(-1, "adb command timed out after ${timeoutSeconds}s")
                        } else {
                            val out = proc.inputStream.bufferedReader().readText()
                            val err = proc.errorStream.bufferedReader().readText()
                            AdbResult(proc.exitValue(), (out + "\n" + err).trim())
                        }
                    } catch (e: Exception) {
                        AdbResult(-1, "adb exec failed: ${e.message}")
                    }
                }
            }
            return AdbRunner(binaryPath, SOCKET_NAME, homePath, executor)
        }
    }
}
