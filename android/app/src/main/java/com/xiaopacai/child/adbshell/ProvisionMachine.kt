package com.xiaopacai.child.adbshell

/**
 * [TASK-STRICT-PROVISION-V1] 强管制预置状态机（ADR 0018）
 *
 * 纯逻辑、可单测。流程：
 * Idle → PreCheck → Guide → Pair → Connect → Provision → Done
 * 失败分类：可重试（配对/连接/未知 dpm 错误）与终态（版本过低/账号存在/ROM 拒绝等）。
 * 安全红线：可能清数据的失败（账号存在/ROM 拒绝）一律终态，不自动重试。
 */
object ProvisionMachine {
    sealed class Step {
        object Idle : Step()
        object PreCheck : Step()
        object Guide : Step()
        object Pair : Step()
        object Connect : Step()
        object Provision : Step()
        object Done : Step()
        data class Failed(val error: ProvisionError, val retryable: Boolean) : Step()
    }

    enum class ProvisionError {
        SDK_TOO_OLD, ALREADY_ACTIVE, BINARY_MISSING, DISCOVERY_FAILED,
        PAIR_FAILED, CONNECTION_FAILED, DPM_ACCOUNTS_PRESENT, DPM_ALREADY_SET,
        DPM_TEST_ONLY, DPM_COLOROS_SIGNATURE_BLOCKED, DPM_ROM_REJECTED, DPM_UNKNOWN
    }

    sealed class Event {
        object Start : Event()
        object PreCheckOk : Event()
        data class PreCheckFailed(val error: ProvisionError) : Event()
        object GuideDone : Event()
        object PairOk : Event()
        object PairFailed : Event()
        object ConnectOk : Event()
        object ConnectFailed : Event()
        object ProvisionOk : Event()
        data class ProvisionFailed(val outcome: AdbOutputParser.DpmOutcome) : Event()
        object Retry : Event()
    }

    fun next(current: Step, event: Event): Step = when (current) {
        Step.Idle -> when (event) {
            Event.Start -> Step.PreCheck
            else -> current
        }

        Step.PreCheck -> when (event) {
            Event.PreCheckOk -> Step.Guide
            is Event.PreCheckFailed -> Step.Failed(event.error, retryable = false)
            else -> current
        }

        Step.Guide -> when (event) {
            Event.GuideDone -> Step.Pair
            else -> current
        }

        Step.Pair -> when (event) {
            Event.PairOk -> Step.Connect
            Event.PairFailed -> Step.Failed(ProvisionError.PAIR_FAILED, retryable = true)
            else -> current
        }

        Step.Connect -> when (event) {
            Event.ConnectOk -> Step.Provision
            Event.ConnectFailed -> Step.Failed(ProvisionError.CONNECTION_FAILED, retryable = true)
            else -> current
        }

        Step.Provision -> when (event) {
            Event.ProvisionOk -> Step.Done
            is Event.ProvisionFailed -> Step.Failed(
                when (event.outcome) {
                    AdbOutputParser.DpmOutcome.ACCOUNTS_PRESENT -> ProvisionError.DPM_ACCOUNTS_PRESENT
                    AdbOutputParser.DpmOutcome.ALREADY_DEVICE_OWNER -> ProvisionError.DPM_ALREADY_SET
                    AdbOutputParser.DpmOutcome.TEST_ONLY_BUILD -> ProvisionError.DPM_TEST_ONLY
                    AdbOutputParser.DpmOutcome.COLOROS_SIGNATURE_BLOCKED ->
                        ProvisionError.DPM_COLOROS_SIGNATURE_BLOCKED
                    AdbOutputParser.DpmOutcome.ROM_REJECTED -> ProvisionError.DPM_ROM_REJECTED
                    else -> ProvisionError.DPM_UNKNOWN
                },
                retryable = event.outcome == AdbOutputParser.DpmOutcome.UNKNOWN_FAILURE
            )
            else -> current
        }

        is Step.Failed -> when (event) {
            Event.Retry -> when (current.error) {
                ProvisionError.PAIR_FAILED, ProvisionError.DISCOVERY_FAILED -> Step.Pair
                ProvisionError.CONNECTION_FAILED -> Step.Connect
                ProvisionError.DPM_UNKNOWN -> Step.Provision
                else -> current
            }
            else -> current
        }

        Step.Done -> current
    }
}
