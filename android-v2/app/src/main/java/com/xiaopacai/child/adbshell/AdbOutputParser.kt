package com.xiaopacai.child.adbshell

/**
 * [TASK-STRICT-PROVISION-V1] adb 输出分类解析（ADR 0018）
 *
 * 将 adb pair/connect/dpm 命令的退出码与输出归类为结构化结果，
 * 供强管制流程做分类提示；任何未知输出都归 UNKNOWN/FAILED，不猜测成功。
 */
object AdbOutputParser {
    enum class DpmOutcome { SUCCESS, ACCOUNTS_PRESENT, ALREADY_DEVICE_OWNER, TEST_ONLY_BUILD, COLOROS_SIGNATURE_BLOCKED, ROM_REJECTED, UNKNOWN_FAILURE }
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
            // ColorOS/realme 私有校验：目标应用签名不在 DO 白名单时抛
            // "java.lang.IllegalStateException: unexpected @ProvisioningPreCondition 99"。
            // 与账号问题（上方规则）区分开，本分支仅匹配该私有注解文案。
            out.contains("provisioningprecondition") -> DpmOutcome.COLOROS_SIGNATURE_BLOCKED
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

    /**
     * 解析 `adb devices` 输出中处于 device（已授权连接）状态的第一个设备 serial。
     * 输出形如：
     * ```
     * List of devices attached
     * adb-XXXX._adb-tls-connect._tcp.	device
     * ```
     * 返回 null 表示尚无已授权连接的设备。
     */
    fun parseConnectedDeviceSerial(output: String): String? {
        return output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("\t", " ")
                if (parts.size >= 2 && parts[1] == "device") parts[0].trim() else null
            }
            .firstOrNull { it.isNotBlank() && it != "List" }
    }
}
