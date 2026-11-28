package com.tracewayapp.fieldmart.feed

import com.tracewayapp.fieldmart.data.Api
import com.tracewayapp.fieldmart.data.FeedRow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The 10,000 feed rows (02-APP-SPEC.md §2.4), fetched 100 at a time from `/fx/api/feed` as they come into view, so
 * a jump to row 5,000 or to the end loads only the pages around it. Shared by the Compose and the Views feed.
 */
class FeedModel(private val api: Api) {
    private val pages = ConcurrentHashMap<Int, List<FeedRow>>()
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "FmFeed").apply { isDaemon = true } }
    @Volatile
    var total = TOTAL
        private set
    /** Called (on a pool thread) with the page that just arrived. */
    var listener: ((Int) -> Unit)? = null

    /** The row at [index], or null while its page loads (it starts loading now). */
    fun row(index: Int): FeedRow? {
        val page = index / PAGE
        val rows = pages[page]
        if (rows == null) {
            load(page)
            return null
        }
        return rows.getOrNull(index - page * PAGE)
    }

    fun load(page: Int) {
        if (page < 0 || page * PAGE >= total || pages.containsKey(page) || !loading.add(page)) return
        pool.execute {
            try {
                val result = api.feed(page * PAGE, PAGE)
                if (result.total > 0) total = result.total
                pages[page] = result.items
            } catch (_: Throwable) {
            } finally {
                loading.remove(page)
            }
            listener?.invoke(page)
        }
    }

    fun loadedPages(): Set<Int> = pages.keys.toSet()

    /** The rows of [page] if it is loaded. */
    fun page(page: Int): List<FeedRow>? = pages[page]

    /** Pull to refresh: the first page again. */
    fun refresh() {
        pages.remove(0)
        load(0)
    }

    companion object {
        const val TOTAL = 10_000
        const val PAGE = 100
        const val SECTION = 50

        @Volatile
        private var shared: FeedModel? = null

        fun of(api: Api): FeedModel = shared ?: synchronized(this) { shared ?: FeedModel(api).also { shared = it } }
    }
}
