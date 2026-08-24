package com.xiaopacai.child.adbshell

/**
 * [TASK-STRICT-PROVISION-V1] adb 输出分类解析（ADR 0018）
 *
 * 将 adb pair/connect/dpm 命令的退出码与输出归类为结构化结果，
 * 供强管制流程做分类提示；任何未知输出都归 UNKNOWN/FAILED，不猜测成功。
 */
object AdbOutputParser {
    enum class DpmOutcome { SUCCESS, ACCOUNTS_PRESENT, ALREADY_DEVICE_OWNER, TEST_ONLY_BUILD, ROM_REJECTED, UNKNOWN_FAILURE }
    enum class PairOutcome { SUCCESS, FAILED }
    enum class ConnectOutcome { SUCCESS, FAILED }

    fun classifyDpm(exitCode: Int, output: String): DpmOutcome {
        val out = output.lowercase()
        return when {
            exitCode == 0 && out.contains("success") -> DpmOutcome.SUCCESS
            // 账号相关错误必须先于 ROM 拒绝判断：ColorOS 实测消息同时含 "not allowed" 与账号提示
            out.contains("no accounts") ||
                out.contains("unprovisioned") ||
                out.contains("accounts on the device") ||
                out.contains("already some accounts") -> DpmOutcome.ACCOUNTS_PRESENT
            out.contains("device owner is already set") ||
                (out.contains("already") && out.contains("device owner")) -> DpmOutcome.ALREADY_DEVICE_OWNER
            out.contains("testonly") -> DpmOutcome.TEST_ONLY_BUILD
            out.contains("securityexception") ||
                out.contains("not allowed") ||
                out.contains("permission denial") -> DpmOutcome.ROM_REJECTED
            else -> DpmOutcome.UNKNOWN_FAILURE
        }
    }

    fun classifyPair(exitCode: Int, output: String): PairOutcome {
        val out = output.lowercase()
        return if (exitCode == 0 && out.contains("successfully paired")) PairOutcome.SUCCESS else PairOutcome.FAILED
    }

    fun classifyConnect(exitCode: Int, output: String): ConnectOutcome {
        val out = output.lowercase()
        return if (exitCode == 0 && out.contains("connected to")) ConnectOutcome.SUCCESS else ConnectOutcome.FAILED
    }
}
