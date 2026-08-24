package com.xiaopacai.child.adbshell

import com.xiaopacai.child.adbshell.AdbOutputParser.DpmOutcome
import com.xiaopacai.child.adbshell.ProvisionMachine.Event
import com.xiaopacai.child.adbshell.ProvisionMachine.ProvisionError
import com.xiaopacai.child.adbshell.ProvisionMachine.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionStateMachineTest {

    private val machine = ProvisionMachine

    @Test
    fun startMovesToPreCheck() {
        assertEquals(Step.PreCheck, machine.next(Step.Idle, Event.Start))
    }

    @Test
    fun preCheckOkMovesToGuide() {
        assertEquals(Step.Guide, machine.next(Step.PreCheck, Event.PreCheckOk))
    }

    @Test
    fun preCheckSdkTooOldFailsTerminal() {
        val s = machine.next(Step.PreCheck, Event.PreCheckFailed(ProvisionError.SDK_TOO_OLD))
        assertTrue(s is Step.Failed)
        s as Step.Failed
        assertEquals(ProvisionError.SDK_TOO_OLD, s.error)
        assertEquals(false, s.retryable)
    }

    @Test
    fun guideDoneMovesToPair() {
        assertEquals(Step.Pair, machine.next(Step.Guide, Event.GuideDone))
    }

    @Test
    fun pairOkMovesToConnect() {
        assertEquals(Step.Connect, machine.next(Step.Pair, Event.PairOk))
    }

    @Test
    fun pairFailedIsRetryable() {
        val s = machine.next(Step.Pair, Event.PairFailed)
        assertTrue(s is Step.Failed)
        s as Step.Failed
        assertEquals(ProvisionError.PAIR_FAILED, s.error)
        assertEquals(true, s.retryable)
    }

    @Test
    fun connectOkMovesToProvision() {
        assertEquals(Step.Provision, machine.next(Step.Connect, Event.ConnectOk))
    }

    @Test
    fun connectFailedIsRetryable() {
        val s = machine.next(Step.Connect, Event.ConnectFailed)
        assertTrue(s is Step.Failed)
        s as Step.Failed
        assertEquals(ProvisionError.CONNECTION_FAILED, s.error)
        assertEquals(true, s.retryable)
    }

    @Test
    fun provisionOkMovesToDone() {
        assertEquals(Step.Done, machine.next(Step.Provision, Event.ProvisionOk))
    }

    @Test
    fun provisionAccountsPresentIsTerminal() {
        val s = machine.next(Step.Provision, Event.ProvisionFailed(DpmOutcome.ACCOUNTS_PRESENT))
        assertTrue(s is Step.Failed)
        s as Step.Failed
        assertEquals(ProvisionError.DPM_ACCOUNTS_PRESENT, s.error)
        assertEquals(false, s.retryable)
    }

    @Test
    fun provisionRomRejectedIsTerminal() {
        val s = machine.next(Step.Provision, Event.ProvisionFailed(DpmOutcome.ROM_REJECTED))
        assertTrue(s is Step.Failed)
        s as Step.Failed
        assertEquals(ProvisionError.DPM_ROM_REJECTED, s.error)
        assertEquals(false, s.retryable)
    }

    @Test
    fun provisionUnknownIsRetryable() {
        val s = machine.next(Step.Provision, Event.ProvisionFailed(DpmOutcome.UNKNOWN_FAILURE))
        assertTrue(s is Step.Failed)
        s as Step.Failed
        assertEquals(ProvisionError.DPM_UNKNOWN, s.error)
        assertEquals(true, s.retryable)
    }

    @Test
    fun retryFromPairFailureReturnsToPair() {
        val failed = machine.next(Step.Pair, Event.PairFailed) as Step.Failed
        assertEquals(Step.Pair, machine.next(failed, Event.Retry))
    }

    @Test
    fun retryFromConnectFailureReturnsToConnect() {
        val failed = machine.next(Step.Connect, Event.ConnectFailed) as Step.Failed
        assertEquals(Step.Connect, machine.next(failed, Event.Retry))
    }

    @Test
    fun retryFromDpmUnknownReturnsToProvision() {
        val failed = machine.next(Step.Provision, Event.ProvisionFailed(DpmOutcome.UNKNOWN_FAILURE)) as Step.Failed
        assertEquals(Step.Provision, machine.next(failed, Event.Retry))
    }

    @Test
    fun terminalStatesIgnoreEvents() {
        assertEquals(Step.Done, machine.next(Step.Done, Event.Start))
        val failed = Step.Failed(ProvisionError.SDK_TOO_OLD, retryable = false)
        assertEquals(failed, machine.next(failed, Event.Retry))
    }
}
