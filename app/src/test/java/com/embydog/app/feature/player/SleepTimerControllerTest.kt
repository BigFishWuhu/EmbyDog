package com.embydog.app.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class SleepTimerControllerTest {
    private fun withTimer(block: (SleepTimerController, MutableList<SleepTimerAction>, (Long) -> Unit) -> Unit) {
        var now = 1000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = mutableListOf<SleepTimerAction>()
        val timer = SleepTimerController(scope, { now }, actions::add)
        try { block(timer, actions) { now += it } } finally { timer.cancel(); scope.cancel() }
    }

    @Test fun pausesAtDeadlineExactlyOnce() = withTimer { timer, actions, advance ->
        timer.start(1, SleepTimerAction.PAUSE)
        advance(59_999); timer.refresh()
        assertEquals(1L, timer.state.value?.remainingMs)
        assertTrue(actions.isEmpty())
        advance(1); timer.refresh(); timer.refresh()
        assertEquals(listOf(SleepTimerAction.PAUSE), actions)
        assertNull(timer.state.value)
    }

    @Test fun closesAfterAnElapsedTimeJump() = withTimer { timer, actions, advance ->
        timer.start(30, SleepTimerAction.CLOSE)
        advance(31 * 60_000L); timer.refresh()
        assertEquals(listOf(SleepTimerAction.CLOSE), actions)
    }

    @Test fun cancellationPreventsExpiration() = withTimer { timer, actions, advance ->
        timer.start(1, SleepTimerAction.CLOSE); timer.cancel()
        advance(120_000); timer.refresh()
        assertTrue(actions.isEmpty())
        assertNull(timer.state.value)
    }

    @Test fun changingTimerReplacesThePreviousDeadlineAndAction() = withTimer { timer, actions, advance ->
        timer.start(1, SleepTimerAction.CLOSE); advance(30_000)
        timer.start(2, SleepTimerAction.PAUSE); advance(40_000); timer.refresh()
        assertTrue(actions.isEmpty())
        assertEquals(80_000L, timer.state.value?.remainingMs)
        advance(80_000); timer.refresh()
        assertEquals(listOf(SleepTimerAction.PAUSE), actions)
    }

    @Test fun minutesAreValidatedWithoutOverflow() {
        assertEquals(1, sleepTimerMinutes("1"))
        assertEquals(1440, sleepTimerMinutes("1440"))
        listOf("", "0", "-1", "1441", "1.5", "999999999999999").forEach { assertNull(sleepTimerMinutes(it)) }
    }

    @Test fun remainingTimeRoundsUpInsteadOfShowingZeroEarly() {
        assertEquals("00:01", formatSleepTimerTime(1))
        assertEquals("01:00", formatSleepTimerTime(60_000))
        assertEquals("00:00", formatSleepTimerTime(0))
    }
}
