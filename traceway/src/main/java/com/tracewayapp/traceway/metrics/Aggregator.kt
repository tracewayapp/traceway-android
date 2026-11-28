package com.tracewayapp.traceway.metrics

internal typealias MetricDims = Map<String, String>

internal class MetricExemplar(
    val value: Double,
    val timeMs: Long,
    val sessionId: String,
    /** What caused the value, when known. */
    val target: String? = null,
)

internal class HistogramPoint(val name: String, val unit: String, val dims: MetricDims) {
    val histogram = Histogram()
    val exemplars = ArrayList<MetricExemplar>(MAX_EXEMPLARS)
}

internal class SumPoint(val name: String, val dims: MetricDims) {
    var value = 0.0
}

/**
 * Everything observed between two flushes. Delta temporality, so batches
 * from different devices add up.
 */
internal class MetricBatch(
    val startMs: Long,
    val endMs: Long,
    val histograms: List<HistogramPoint>,
    val sums: List<SumPoint>,
) {
    val cells: Int get() = histograms.size + sums.size
}

private const val DEFAULT_MAX_CELLS = 200
private const val MAX_EXEMPLARS = 3

/** Room for the folded cells, so folding never has to drop an observation of a known metric. */
private const val FOLD_HEADROOM = 50

internal const val OVERFLOW_VALUE = "other"

/**
 * The dimension folded to [OVERFLOW_VALUE] when there are too many cells:
 * the screen, as the browser SDK folds the page path.
 */
internal const val SCREEN_DIMENSION = "screen"

private fun cellKey(name: String, dims: MetricDims): String {
    if (dims.isEmpty()) return name
    val out = StringBuilder(name)
    for (key in dims.keys.sorted()) {
        out.append('\u001f').append(key).append('=').append(dims[key])
    }
    return out.toString()
}

/**
 * The metrics of one device between two uploads. An observation increments
 * a bucket or a counter and nothing else: memory depends on the number of
 * cells, never on how long the app runs. A port of the browser SDK's
 * `MetricAggregator`.
 *
 * Not thread-safe: [MetricsRecorder] calls it under its lock.
 */
internal class MetricAggregator(
    private val maxCells: Int = DEFAULT_MAX_CELLS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var histograms = LinkedHashMap<String, HistogramPoint>()
    private var sums = LinkedHashMap<String, SumPoint>()
    private var startMs: Long = now()

    /** Observations lost because the cell limit was reached even after folding. */
    var dropped = 0
        private set

    val size: Int get() = histograms.size + sums.size

    /** [exemplarSession] is passed only for a value worth opening a session for. */
    fun observe(
        name: String,
        value: Double,
        dims: MetricDims = emptyMap(),
        unit: String = "",
        exemplarSession: String? = null,
        exemplarTarget: String? = null,
    ) {
        val cell = cell(histograms, name, dims) { HistogramPoint(name, unit, it) } ?: return
        if (!cell.histogram.record(value)) return
        if (!exemplarSession.isNullOrEmpty()) {
            keepExemplar(cell, MetricExemplar(value, now(), exemplarSession, exemplarTarget))
        }
    }

    fun count(name: String, n: Double = 1.0, dims: MetricDims = emptyMap()) {
        if (n.isNaN() || n.isInfinite() || n <= 0) return
        val cell = cell(sums, name, dims) { SumPoint(name, it) } ?: return
        cell.value += n
    }

    /**
     * A ratio is two counters, `<name>` and `<name>.total`, so ratios from
     * different devices add up.
     */
    fun ratio(name: String, part: Double, total: Double, dims: MetricDims = emptyMap()) {
        if (!(total > 0)) return
        count("$name.total", total, dims)
        count(name, part.coerceIn(0.0, total), dims)
    }

    /** Hands over what was collected and starts a new interval. Null when nothing was observed. */
    fun drain(): MetricBatch? {
        val endMs = now()
        val begin = startMs
        startMs = endMs
        if (size == 0) return null
        val batch = MetricBatch(begin, endMs, ArrayList(histograms.values), ArrayList(sums.values))
        histograms = LinkedHashMap()
        sums = LinkedHashMap()
        return batch
    }

    private fun <T> cell(
        cells: MutableMap<String, T>,
        name: String,
        dims: MetricDims,
        create: (MetricDims) -> T,
    ): T? {
        var key = cellKey(name, dims)
        cells[key]?.let { return it }
        var kept = dims
        if (size >= maxCells) {
            if (!dims.containsKey(SCREEN_DIMENSION) || size >= maxCells + FOLD_HEADROOM) {
                dropped++
                return null
            }
            kept = LinkedHashMap(dims).apply { put(SCREEN_DIMENSION, OVERFLOW_VALUE) }
            key = cellKey(name, kept)
            cells[key]?.let { return it }
        }
        val created = create(java.util.Collections.unmodifiableMap(LinkedHashMap(kept)))
        cells[key] = created
        return created
    }

    /** Keeps the highest values: an exemplar is a session worth opening. */
    private fun keepExemplar(cell: HistogramPoint, exemplar: MetricExemplar) {
        val exemplars = cell.exemplars
        if (exemplars.size < MAX_EXEMPLARS) {
            exemplars.add(exemplar)
            return
        }
        var lowest = 0
        for (i in 1 until exemplars.size) {
            if (exemplars[i].value < exemplars[lowest].value) lowest = i
        }
        if (exemplars[lowest].value < exemplar.value) exemplars[lowest] = exemplar
    }
}
