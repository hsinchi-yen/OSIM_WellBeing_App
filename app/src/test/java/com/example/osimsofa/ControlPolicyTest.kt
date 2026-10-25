package com.example.osimsofa

import com.example.osimsofa.ControlPolicy.Context
import com.example.osimsofa.ControlPolicy.Op
import com.example.osimsofa.ControlPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** improve-plan M04 / S01: one policy for buttons and voice. */
class ControlPolicyTest {

    private val ok = SofaStatus(sofaPosition = true, footBackLimit = true)
    private fun ctx(status: SofaStatus? = ok, age: Long? = 500, operable: Boolean = true, bg: Boolean = false) =
        Context(operable, status, age, bg)
    private fun blocked(op: Op, c: Context) = ControlPolicy.check(op, c) is Verdict.Block
    private val userOps = listOf(Op.MOVE, Op.START_MASSAGE, Op.ADJUST, Op.RESUME)

    @Test fun stopIsAlwaysAllowed() {
        for (c in listOf(ctx(operable = false, status = null, age = null), ctx(status = ok.copy(errorCode = 18)), ctx(age = 60_000), ctx(bg = true)))
            assertEquals(Verdict.Allow, ControlPolicy.check(Op.STOP, c))
    }

    @Test fun nothingElseRunsWithoutAnOperableLinkAndAStatus() {
        for (op in userOps + Op.PAUSE) {
            assertTrue(blocked(op, ctx(operable = false)))
            assertTrue(blocked(op, ctx(status = null, age = null)))
        }
    }

    @Test fun aStaleStatusBlocks() {
        val v = ControlPolicy.check(Op.MOVE, ctx(age = ControlPolicy.STALE_MS + 1_000)) as Verdict.Block
        assertTrue(v.reason.contains("秒"))
    }

    @Test fun aFaultBlocksMovingAndStartingButNotPausing() {
        val c = ctx(status = ok.copy(errorCode = 18))
        for (op in userOps) assertTrue(op.name, blocked(op, c))
        assertTrue((ControlPolicy.check(Op.MOVE, c) as Verdict.Block).reason.contains("18"))
        assertEquals(Verdict.Allow, ControlPolicy.check(Op.PAUSE, c))
    }

    @Test fun nothingNewStartsInTheBackground() {
        for (op in userOps) assertTrue(blocked(op, ctx(bg = true)))
    }

    @Test fun aHealthyFreshStatusAllows() {
        for (op in Op.entries) assertEquals(op.name, Verdict.Allow, ControlPolicy.check(op, ctx()))
    }
}
