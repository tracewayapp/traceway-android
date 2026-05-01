package com.tracewayapp.traceway

import com.tracewayapp.traceway.internal.ExceptionStore
import com.tracewayapp.traceway.models.ExceptionStackTrace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExceptionStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("traceway-store-test-").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newStore(maxLocalFiles: Int = 5, maxAgeHours: Int = 12): ExceptionStore {
        val store = ExceptionStore(
            dir = dir,
            maxLocalFiles = maxLocalFiles,
            maxAgeHours = maxAgeHours,
            debug = false,
        )
        store.init()
        return store
    }

    private fun ex(msg: String) = ExceptionStackTrace(
        stackTrace = msg,
        recordedAtMs = System.currentTimeMillis(),
        isMessage = false,
    )

    @Test
    fun writeRoundTripPreservesException() {
        val store = newStore()
        val original = ex("first failure")
        val id = store.write(original)
        assertNotNull(id)

        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        val entry = loaded.first()
        assertEquals(id, entry.id)
        assertEquals("first failure", entry.exception.stackTrace)
    }

    @Test
    fun removeDeletesFiles() {
        val store = newStore()
        val id1 = store.write(ex("a"))!!
        val id2 = store.write(ex("b"))!!

        store.remove(listOf(id1))

        val remaining = store.loadAll()
        assertEquals(1, remaining.size)
        assertEquals(id2, remaining.first().id)
    }

    @Test
    fun loadAllReturnsOldestFirst() {
        val store = newStore()
        val id1 = store.write(ex("first"))!!
        // Force the second file to have a strictly later createdAt.
        Thread.sleep(10)
        val id2 = store.write(ex("second"))!!

        val loaded = store.loadAll()
        assertEquals(listOf(id1, id2), loaded.map { it.id })
    }

    @Test
    fun pruneExcessOnInitDropsOldestBeyondCap() {
        // Drop max-local-files to 2 and write 4 entries via the same store
        // (init runs prune on construction, but here we just write directly).
        val store = ExceptionStore(dir, maxLocalFiles = 2, maxAgeHours = 12, debug = false)
        store.init()
        // Manually create 4 files with distinct mtimes.
        repeat(4) { i ->
            store.write(ex("entry-$i"))
            Thread.sleep(10)
        }

        // Re-init triggers _pruneExcess() down to 2 files.
        ExceptionStore(dir, maxLocalFiles = 2, maxAgeHours = 12, debug = false).init()

        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!
        assertTrue("expected <=2 files, got ${files.size}", files.size <= 2)
    }
}
