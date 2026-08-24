package com.xiaopacai.child.adbshell

import com.xiaopacai.child.adbshell.StrictPreconditions.PreconditionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class StrictPreconditionsTest {

    @Test
    fun androidBelow11IsUnsupported() {
        assertEquals(PreconditionResult.SdkTooOld, StrictPreconditions.evaluate(26, false, true))
        assertEquals(PreconditionResult.SdkTooOld, StrictPreconditions.evaluate(29, false, true))
    }

    @Test
    fun alreadyDeviceOwnerIsTerminalState() {
        assertEquals(PreconditionResult.AlreadyActive, StrictPreconditions.evaluate(34, true, true))
    }

    @Test
    fun missingBinaryBlocks() {
        assertEquals(PreconditionResult.BinaryMissing, StrictPreconditions.evaluate(34, false, false))
    }

    @Test
    fun allOkPasses() {
        assertEquals(PreconditionResult.Ok, StrictPreconditions.evaluate(34, false, true))
    }
}
