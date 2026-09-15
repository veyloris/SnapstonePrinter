package com.example.snapstoneprinter.data.print

import org.junit.Assert.*
import org.junit.Test

class PrintJobCoordinatorTest {
    private val first = "00000000-0000-4000-8000-000000000001"
    private val second = "00000000-0000-4000-8000-000000000002"
    private val uris = listOf("content://test/first", "content://test/second")

    private fun coordinator(): PrintJobCoordinator {
        val ids = listOf(first, second).iterator()
        return PrintJobCoordinator { ids.next() }
    }

    private fun ready(total: Int = 2): PrintJobCoordinator = coordinator().apply {
        assertEquals(StartPrintResult.Started(first), start("Card", total))
        assertTrue(exported(first, uris.take(total)))
    }

    @Test
    fun startReservesBeforeExport() {
        var ids = 0
        val coordinator = PrintJobCoordinator { ids++; first }
        assertEquals(StartPrintResult.Started(first), coordinator.start("Card", 2))
        assertEquals(PrintJobState.Preparing(first, "Card", 2), coordinator.state.value)
        assertEquals(StartPrintResult.Busy, coordinator.start("Other", 1))
        assertEquals(1, ids)
    }

    @Test
    fun emptyAndNegativeTotalsNeverChangeState() {
        val coordinator = coordinator()
        for (busy in listOf(false, true)) {
            if (busy) coordinator.start("Card", 2)
            val before = coordinator.state.value
            assertEquals(StartPrintResult.Empty, coordinator.start("Empty", 0))
            assertThrows(IllegalArgumentException::class.java) { coordinator.start("Invalid", -1) }
            assertSame(before, coordinator.state.value)
        }
    }

    @Test
    fun invalidFactoryIdentityRejectedBeforePreparation() {
        for (id in listOf("", "not-a-uuid", "1-1-1-1-1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
            val coordinator = PrintJobCoordinator { id }
            assertThrows(IllegalStateException::class.java) { coordinator.start("Card", 1) }
            assertSame(PrintJobState.Idle, coordinator.state.value)
        }
        val repeated = PrintJobCoordinator { first }
        repeated.start("Card", 1)
        repeated.stop(first)
        val before = repeated.state.value
        assertThrows(IllegalStateException::class.java) { repeated.start("Again", 1) }
        assertSame(before, repeated.state.value)
    }

    @Test
    fun invalidTokensRejected() {
        assertThrows(IllegalArgumentException::class.java) { DispatchToken("../other", 0) }
        assertThrows(IllegalArgumentException::class.java) { DispatchToken(first, -1) }
        assertEquals(first, DispatchToken(first, 0).jobId)
    }

    @Test
    fun badExportCannotBecomeReady() {
        for (payload in listOf(emptyList(), listOf("content://one"), listOf("content://one", " "))) {
            val coordinator = coordinator()
            coordinator.start("Card", 2)
            val before = coordinator.state.value
            assertFalse(coordinator.exported(second, payload))
            assertSame(before, coordinator.state.value)
            assertTrue(coordinator.exported(first, payload))
            assertEquals(PrintJobState.Failed(first, "Could not prepare all slips for sharing."), coordinator.state.value)
        }
    }

    @Test
    fun exportSnapshotIsDefensivelyCopiedAndUnmodifiable() {
        val coordinator = coordinator()
        coordinator.start("Card", 2)
        val payload = uris.toMutableList()
        coordinator.exported(first, payload)
        payload[0] = "content://changed"
        val state = coordinator.state.value as PrintJobState.Ready
        assertEquals(uris, state.uris)
        assertThrows(UnsupportedOperationException::class.java) { (state.uris as MutableList<String>)[0] = "changed" }
        assertEquals(2, state.total)
        assertEquals(DispatchToken(first, 0), state.token)
    }

    @Test
    fun claimOnce() {
        val coordinator = ready()
        val token = DispatchToken(first, 0)
        assertNull(coordinator.claimLaunch(DispatchToken(first, 1)))
        assertEquals(LaunchRequest(token, uris[0], "Card", 2), coordinator.claimLaunch(token))
        val before = coordinator.state.value
        assertTrue(before is PrintJobState.Launched)
        assertNull(coordinator.claimLaunch(token))
        assertSame(before, coordinator.state.value)
    }

    @Test
    fun returnWaitsForChoice() {
        val coordinator = ready()
        val firstToken = DispatchToken(first, 0)
        coordinator.claimLaunch(firstToken)
        assertTrue(coordinator.returned(firstToken))
        assertTrue(coordinator.state.value is PrintJobState.AwaitingNext)
        assertNull(coordinator.claimLaunch(DispatchToken(first, 1)))
        assertFalse(coordinator.sendNext(second))
        assertTrue(coordinator.sendNext(first))
        assertEquals(LaunchRequest(DispatchToken(first, 1), uris[1], "Card", 2),
            coordinator.claimLaunch(DispatchToken(first, 1)))
        assertTrue(coordinator.returned(DispatchToken(first, 1)))
        assertEquals(PrintJobState.Completed(first), coordinator.state.value)
        assertFalse(coordinator.state.value.isBusy)
        assertEquals(StartPrintResult.Started(second), coordinator.start("New", 1))
    }

    @Test
    fun stopDoesNotSendRemainder() {
        val coordinator = ready()
        coordinator.claimLaunch(DispatchToken(first, 0))
        coordinator.returned(DispatchToken(first, 0))
        assertTrue(coordinator.stop(first))
        assertEquals(PrintJobState.Cancelled(first), coordinator.state.value)
        assertFalse(coordinator.sendNext(first))
        assertFalse(coordinator.returned(DispatchToken(first, 0)))
        assertNull(coordinator.claimLaunch(DispatchToken(first, 1)))
    }

    @Test
    fun stoppingDrainsOutstandingCallback() {
        val coordinator = ready()
        val token = DispatchToken(first, 0)
        coordinator.claimLaunch(token)
        assertTrue(coordinator.stop(first))
        assertTrue(coordinator.state.value is PrintJobState.Stopping)
        val stopping = coordinator.state.value
        assertFalse(coordinator.stop(first))
        assertFalse(coordinator.returned(DispatchToken(second, 0)))
        assertEquals(StartPrintResult.Busy, coordinator.start("New", 1))
        assertSame(stopping, coordinator.state.value)
        assertTrue(coordinator.returned(token))
        assertEquals(PrintJobState.Cancelled(first), coordinator.state.value)
        assertEquals(StartPrintResult.Started(second), coordinator.start("New", 1))
    }

    @Test
    fun staleAndDuplicateCallbacksDoNotAdvance() {
        val coordinator = ready(1)
        val old = DispatchToken(first, 0)
        coordinator.claimLaunch(old)
        coordinator.returned(old)
        assertFalse(coordinator.returned(old))
        coordinator.start("New", 1)
        coordinator.exported(second, listOf("content://new"))
        coordinator.claimLaunch(DispatchToken(second, 0))
        val before = coordinator.state.value
        assertFalse(coordinator.returned(old))
        assertFalse(coordinator.launchFailed(old, "old error"))
        assertFalse(coordinator.exportFailed(first, "old error"))
        assertFalse(coordinator.exported(first, uris))
        assertFalse(coordinator.stop(first))
        assertSame(before, coordinator.state.value)
    }

    @Test
    fun failuresUseDefaultsAndReleaseJob() {
        val exporting = coordinator()
        exporting.start("Card", 1)
        assertTrue(exporting.exportFailed(first, " "))
        assertEquals(PrintJobState.Failed(first, "Could not save images for sharing."), exporting.state.value)
        for (stopFirst in listOf(false, true)) {
            val coordinator = ready()
            val token = DispatchToken(first, 0)
            coordinator.claimLaunch(token)
            if (stopFirst) coordinator.stop(first)
            assertTrue(coordinator.launchFailed(token, ""))
            assertEquals(PrintJobState.Failed(first, "No app could open this slip."), coordinator.state.value)
            assertEquals(StartPrintResult.Started(second), coordinator.start("Again", 1))
        }
    }

    @Test
    fun stopsBeforeLaunchCancelImmediately() {
        for (exportFirst in listOf(false, true)) {
            val coordinator = coordinator()
            coordinator.start("Card", 2)
            if (exportFirst) coordinator.exported(first, uris)
            assertTrue(coordinator.stop(first))
            assertEquals(PrintJobState.Cancelled(first), coordinator.state.value)
            assertFalse(coordinator.exported(first, uris))
            assertNull(coordinator.claimLaunch(DispatchToken(first, 0)))
        }
    }

    @Test
    fun busyStatesRejectAdditionalStarts() {
        val coordinator = coordinator()
        coordinator.start("Card", 2)
        fun assertBusyUnchanged() {
            val before = coordinator.state.value
            assertTrue(before.isBusy)
            assertEquals(StartPrintResult.Busy, coordinator.start("Other", 1))
            assertSame(before, coordinator.state.value)
        }
        assertBusyUnchanged()
        coordinator.exported(first, uris)
        assertBusyUnchanged()
        coordinator.claimLaunch(DispatchToken(first, 0))
        assertBusyUnchanged()
        coordinator.returned(DispatchToken(first, 0))
        assertBusyUnchanged()
        coordinator.sendNext(first)
        coordinator.claimLaunch(DispatchToken(first, 1))
        coordinator.stop(first)
        assertBusyUnchanged()
    }

    @Test
    fun outOfOrderEventsAndExplicitFailureMessages() {
        val coordinator = coordinator()
        val token = DispatchToken(first, 0)
        assertFalse(coordinator.exported(first, uris))
        assertFalse(coordinator.exportFailed(first, "error"))
        assertFalse(coordinator.returned(token))
        assertFalse(coordinator.sendNext(first))
        assertFalse(coordinator.stop(first))
        coordinator.start("Card", 2)
        val preparing = coordinator.state.value
        assertNull(coordinator.claimLaunch(token))
        assertFalse(coordinator.launchFailed(token, "error"))
        assertFalse(coordinator.returned(token))
        assertFalse(coordinator.sendNext(first))
        assertSame(preparing, coordinator.state.value)
        assertTrue(coordinator.exportFailed(first, "Specific export failure"))
        assertEquals(PrintJobState.Failed(first, "Specific export failure"), coordinator.state.value)
        coordinator.start("Next", 1)
        coordinator.exported(second, listOf(uris[0]))
        val ready = coordinator.state.value
        assertFalse(coordinator.returned(DispatchToken(second, 0)))
        assertFalse(coordinator.launchFailed(DispatchToken(second, 0), "error"))
        assertFalse(coordinator.sendNext(second))
        assertFalse(coordinator.exportFailed(second, "error"))
        assertSame(ready, coordinator.state.value)
        coordinator.claimLaunch(DispatchToken(second, 0))
        assertTrue(coordinator.launchFailed(DispatchToken(second, 0), "Specific launch failure"))
        assertEquals(PrintJobState.Failed(second, "Specific launch failure"), coordinator.state.value)
    }
}
