package org.hearthlane.ui

import org.hearthlane.controller.RecentEventsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deterministic tests of the foreground-resume recovery primitive
 * [awaitErrorAndRecover]: it reacts to an Error that is present at resume time
 * AND to one that surfaces LATER (the background-interrupted request landing
 * after the resume decision), runs the recovery exactly once, and never
 * touches Loaded/Loading content. The single-shot evaluation is bounded: after
 * one recovery the observation ends, so a genuine repeated failure is left to
 * the user's Retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundResumeRecoveryTest {

    private val error = { s: RecentEventsState -> s is RecentEventsState.Error }

    @Test
    fun `recover runs once when the state is already in Error`() = runTest {
        val state = MutableStateFlow<RecentEventsState>(RecentEventsState.Error("boom"))
        val calls = mutableListOf<Int>()

        awaitErrorAndRecover(state, error) { calls.add(1) }

        assertEquals("an Error already present at resume is recovered once", 1, calls.size)
    }

    @Test
    fun `recover reacts to an Error that surfaces after the resume decision`() = runTest {
        val state = MutableStateFlow<RecentEventsState>(RecentEventsState.Loaded(emptyList()))
        val calls = mutableListOf<Int>()

        val job = launch { awaitErrorAndRecover(state, error) { calls.add(1) } }
        runCurrent()
        assertEquals("Loaded content must not be recovered", 0, calls.size)

        state.value = RecentEventsState.Loaded(emptyList()) // still Loaded
        runCurrent()
        assertEquals(0, calls.size)

        state.value = RecentEventsState.Error("the old request finally fails")
        runCurrent()
        assertEquals("the late Error is recovered exactly once", 1, calls.size)
        job.join()
    }

    @Test
    fun `recover never fires while the content stays Loaded`() = runTest {
        val state = MutableStateFlow<RecentEventsState>(RecentEventsState.Loaded(emptyList()))
        val calls = mutableListOf<Int>()

        val job = launch { awaitErrorAndRecover(state, error) { calls.add(1) } }
        runCurrent()
        assertEquals(0, calls.size)
        job.cancel()
        job.join()
        assertEquals("a Loaded list is never re-fetched", 0, calls.size)
    }

    @Test
    fun `a second Error after the single recovery is not re-recovered`() = runTest {
        val state = MutableStateFlow<RecentEventsState>(RecentEventsState.Error("boom"))
        val calls = mutableListOf<Int>()

        awaitErrorAndRecover(state, error) { calls.add(1) }
        assertEquals(1, calls.size)

        // The observation ended after the single recovery: a genuine repeated
        // failure is left to the user's Retry (no retry loop).
        state.value = RecentEventsState.Loaded(emptyList())
        state.value = RecentEventsState.Error("boom again")
        assertEquals("the recovery is bounded to one attempt", 1, calls.size)
    }
}