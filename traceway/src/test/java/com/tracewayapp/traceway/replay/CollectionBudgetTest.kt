package com.tracewayapp.traceway.replay

import com.tracewayapp.traceway.persistence.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The replay budget of a session ([CollectionBudget]): bytes, events and
 * pictures, kept with the SDK's state. How the controller stops uploading
 * once it is used, and the counts across a restart, are in
 * [ReplayControllerTest].
 */
class CollectionBudgetTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theDefaultsAreTheBrowserSdks() {
        val budget = CollectionBudget()
        assertEquals(50L * 1024 * 1024, budget.maxBytes)
        assertEquals(10_000L, budget.maxEvents)
        assertEquals(4L * 1024 * 1024, budget.maxImageBytes)
        assertEquals(
            mapOf(
                "traceway.collection.bytes" to 0L,
                "traceway.collection.events" to 0L,
                "traceway.collection.dropped" to 0L,
                "traceway.collection.max_bytes" to 50L * 1024 * 1024,
                "traceway.collection.max_events" to 10_000L,
                "traceway.replay.image_bytes" to 0L,
            ),
            budget.snapshot("s1"),
        )
    }

    @Test
    fun eventsAreCountedNotSegments() {
        val budget = CollectionBudget(maxEvents = 10)
        assertTrue(budget.admit("s1", 1, events = 6))
        assertFalse(budget.admit("s1", 1, events = 5))
        assertTrue(budget.admit("s1", 1, events = 4))
        assertFalse(budget.admit("s1", 1, events = 1))
        val snapshot = budget.snapshot("s1")
        assertEquals(10L, snapshot["traceway.collection.events"])
        assertEquals(2L, snapshot["traceway.collection.bytes"])
        assertEquals("the refused segments' events", 6L, snapshot["traceway.collection.dropped"])
    }

    @Test
    fun theCountsAreKeptOnDiskWithTheState() {
        val dir = folder.newFolder("state")
        val first = StateStore(dir, destination = "d1")
        val a = CollectionBudget(maxBytes = 1000, store = first)
        assertTrue(a.admit("s1", 700))
        assertTrue(a.admitImage("s1", 30))
        first.flush()

        val next = StateStore(dir, destination = "d1").also { it.load() }
        val b = CollectionBudget(maxBytes = 1000, store = next, maxImageBytes = 50)
        assertFalse(b.admit("s1", 400))
        assertEquals(700L, b.snapshot("s1")["traceway.collection.bytes"])
        assertEquals(1L, b.snapshot("s1")["traceway.collection.dropped"])
        assertFalse("30 of 50 used before the restart", b.admitImage("s1", 30))

        // Another project's state is not read back.
        val other = StateStore(dir, destination = "d2").also { it.load() }
        assertTrue(CollectionBudget(maxBytes = 1000, store = other).admit("s1", 400))
    }

    @Test
    fun withoutASessionNothingIsSaved() {
        val store = StateStore(null)
        val budget = CollectionBudget(maxBytes = 1000, store = store, maxImageBytes = 100)
        assertTrue(budget.admit(null, 10))
        assertTrue(budget.admitImage(null, 60))
        assertFalse("pictures without a session still have one allowance", budget.admitImage(null, 60))
        assertNull(store.get("budget"))
        // A session starts from nothing, and its counts are saved.
        assertTrue(budget.admit("s1", 10))
        assertTrue(budget.admitImage("s1", 100))
        @Suppress("UNCHECKED_CAST")
        val saved = store.get("budget") as Map<String, Any?>
        assertEquals("s1", saved["sessionId"])
        assertEquals(10L, saved["bytes"])
        assertEquals(1L, saved["events"])
        assertEquals(100L, saved["imageBytes"])
    }

    @Test
    fun aStoreSetLaterIsReadBeforeTheNextCount() {
        val store = StateStore(null)
        store.set("budget", mapOf("sessionId" to "s1", "bytes" to 900L, "events" to 3L, "dropped" to 1L, "imageBytes" to 5L))
        val budget = CollectionBudget(maxBytes = 1000)
        assertTrue(budget.admit("s1", 900))
        budget.store = store
        assertFalse(budget.admit("s1", 200))
        val snapshot = budget.snapshot("s1")
        assertEquals(900L, snapshot["traceway.collection.bytes"])
        assertEquals(3L, snapshot["traceway.collection.events"])
        assertEquals(2L, snapshot["traceway.collection.dropped"])
        assertEquals(5L, snapshot["traceway.replay.image_bytes"])
        // Counts that are not counts are read as none.
        store.set("budget", mapOf("sessionId" to "s2", "bytes" to -5L, "events" to "many", "dropped" to 1.5, "imageBytes" to null))
        budget.store = store
        assertEquals(0L, budget.snapshot("s2")["traceway.collection.bytes"])
        assertEquals(0L, budget.snapshot("s2")["traceway.collection.events"])
        assertEquals(0L, budget.snapshot("s2")["traceway.replay.image_bytes"])
    }
}
