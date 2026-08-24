package com.xiaopacai.child.adbshell

import com.xiaopacai.child.adbshell.AdbOutputParser.ConnectOutcome
import com.xiaopacai.child.adbshell.AdbOutputParser.DpmOutcome
import com.xiaopacai.child.adbshell.AdbOutputParser.PairOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbOutputParserTest {

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
