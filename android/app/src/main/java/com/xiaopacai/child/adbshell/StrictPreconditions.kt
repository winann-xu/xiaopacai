package com.xiaopacai.child.adbshell

/**
 * [TASK-STRICT-PROVISION-V1] 强管制前置条件（ADR 0018）
 *
 * Android 11+（无线调试）→ 未激活 DO → adb 二进制存在。
 * 注：不把 isProvisioningAllowed(ACTION_PROVISION_MANAGED_DEVICE) 作为硬门槛——
 * 该 API 只反映企业预置流程（QR/zero-touch），与 `dpm set-device-owner` 不是同一路径，
 * ColorOS 等 ROM 上会误报 false；真实结果由 dpm 命令输出分类决定（ROM_REJECTED）。
 * 任一不满足即给出明确结论，由 UI 分类提示。
 */
object StrictPreconditions {
    sealed class PreconditionResult {
        object Ok : PreconditionResult()
        object SdkTooOld : PreconditionResult()
        object AlreadyActive : PreconditionResult()
        object BinaryMissing : PreconditionResult()
    }

    fun evaluate(
        sdkInt: Int,
        isDeviceOwner: Boolean,
        binaryPresent: Boolean
    ): PreconditionResult = when {
        sdkInt < 30 -> PreconditionResult.SdkTooOld
        isDeviceOwner -> PreconditionResult.AlreadyActive
        !binaryPresent -> PreconditionResult.BinaryMissing
        else -> PreconditionResult.Ok
    }
}
