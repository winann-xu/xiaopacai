package com.xiaopacai.child.adbshell

import com.xiaopacai.child.adbshell.AdbOutputParser.ConnectOutcome
import com.xiaopacai.child.adbshell.AdbOutputParser.DpmOutcome
import com.xiaopacai.child.adbshell.AdbOutputParser.PairOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbOutputParserTest {

    @Test
    fun parseDevices_findsAuthorizedDevice() {
        val out = "List of devices attached\n" +
            "adb-PBMR5L6995RKCQJN-r8yIbq._adb-tls-connect._tcp.\tdevice\n\n"
        assertEquals(
            "adb-PBMR5L6995RKCQJN-r8yIbq._adb-tls-connect._tcp.",
            AdbOutputParser.parseConnectedDeviceSerial(out)
        )
    }

    @Test
    fun parseDevices_skipsUnauthorizedAndOffline() {
        val out = "List of devices attached\n" +
            "some-serial\tunauthorized\n" +
            "other-serial\toffline\n" +
            "good-serial\tdevice\n\n"
        assertEquals("good-serial", AdbOutputParser.parseConnectedDeviceSerial(out))
    }

    @Test
    fun parseDevices_nullWhenNoDevice() {
        assertEquals(
            null,
            AdbOutputParser.parseConnectedDeviceSerial("List of devices attached\n\n")
        )
    }

    @Test
    fun dpm_success() {
        val out = "Success: Device owner set to package com.xiaopacai.child\n" +
            "Active admin set to component {com.xiaopacai.child/com.xiaopacai.child.service.GuardianDeviceAdminReceiver}"
        assertEquals(DpmOutcome.SUCCESS, AdbOutputParser.classifyDpm(0, out))
    }

    @Test
    fun dpm_accountsPresent() {
        val out = "java.lang.IllegalStateException: Device owner can only be set on an unprovisioned device or a device with no accounts."
        assertEquals(DpmOutcome.ACCOUNTS_PRESENT, AdbOutputParser.classifyDpm(1, out))
    }

    @Test
    fun dpm_accountsPresent_colorOsVariant() {
        // OPPO/ColorOS 真机实测输出：包含 "not allowed" 但也包含账号提示，
        // 必须归类为账号问题而非 ROM 拒绝。
        val out = "com.xiaopacai.child/.service.GuardianDeviceAdminReceiver was already an admin for user 0. " +
            "No need to set it again.\n\n" +
            "Exception occurred while executing 'set-device-owner':\n" +
            "java.lang.IllegalStateException: Not allowed to set the device owner " +
            "because there are already some accounts on the device."
        assertEquals(DpmOutcome.ACCOUNTS_PRESENT, AdbOutputParser.classifyDpm(1, out))
    }

    @Test
    fun dpm_alreadyDeviceOwner() {
        val out = "java.lang.IllegalStateException: Device owner is already set"
        assertEquals(DpmOutcome.ALREADY_DEVICE_OWNER, AdbOutputParser.classifyDpm(1, out))
    }

    @Test
    fun dpm_testOnlyPackage() {
        val out = "Error: Not allowed to set the device owner because the package is a testOnly package"
        assertEquals(DpmOutcome.TEST_ONLY_BUILD, AdbOutputParser.classifyDpm(1, out))
    }

    @Test
    fun dpm_colorOsSignatureBlocked_precondition99() {
        // OPPO Reno8 实测输出：ColorOS 对第三方 DO 的签名白名单校验失败。
        val out = "Exception occurred while executing 'set-device-owner':\n" +
            "java.lang.IllegalStateException: unexpected @ProvisioningPreCondition 99 " +
            "at com.android.server.devicepolicy.DevicePolicyManagerService.enforceCanSetDeviceOwnerLocked"
        assertEquals(DpmOutcome.COLOROS_SIGNATURE_BLOCKED, AdbOutputParser.classifyDpm(1, out))
    }

    @Test
    fun dpm_colorOsSignatureBlocked_anyPreconditionCode() {
        // 不依赖具体数值，按注解文案识别，避免其他 ColorOS 构建返回不同 code 时漏判。
        val out = "java.lang.IllegalStateException: Unexpected @ProvisioningPreCondition:1024 at ..."
        assertEquals(DpmOutcome.COLOROS_SIGNATURE_BLOCKED, AdbOutputParser.classifyDpm(1, out))
    }

    @Test
    fun dpm_romRejected() {
        val out = "java.lang.SecurityException: Permission Denial: not allowed to set device owner"
        assertEquals(DpmOutcome.ROM_REJECTED, AdbOutputParser.classifyDpm(1, out))
    }

    @Test
    fun dpm_unknownFailure() {
        assertEquals(DpmOutcome.UNKNOWN_FAILURE, AdbOutputParser.classifyDpm(1, "killed by signal"))
    }

    @Test
    fun pair_success() {
        assertEquals(PairOutcome.SUCCESS, AdbOutputParser.classifyPair(0, "Successfully paired to 192.168.1.5:37000"))
    }

    @Test
    fun pair_failure() {
        assertEquals(PairOutcome.FAILED, AdbOutputParser.classifyPair(1, "Failed to pair"))
    }

    @Test
    fun connect_success() {
        assertEquals(ConnectOutcome.SUCCESS, AdbOutputParser.classifyConnect(0, "connected to 192.168.1.5:33791"))
    }

    @Test
    fun connect_failure() {
        assertEquals(ConnectOutcome.FAILED, AdbOutputParser.classifyConnect(1, "failed to connect to '192.168.1.5:33791': Connection refused"))
    }
}
