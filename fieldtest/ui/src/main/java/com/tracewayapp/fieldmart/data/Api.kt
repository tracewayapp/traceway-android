package com.tracewayapp.fieldmart.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * The fixture data API (02-APP-SPEC.md §3.1), served by the gateway under `/fx/api/`. Calls block: run them off the
 * main thread. Every response goes through the app's one OkHttp client, so the SDK build records each as a span.
 */
class Api(private val http: OkHttpClient, private val base: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun products(page: Int, size: Int = PAGE_SIZE, category: Int? = null): Page<Product> {
        val root = get("api/products?page=$page&size=$size" + (category?.let { "&category=$it" } ?: ""))
        val items = list(root).map { Product.of(it.obj()) }
        return Page(items, (root as? JsonObject)?.i("total", "count") ?: -1)
    }

    fun product(id: Int): ProductDetail = ProductDetail.of(unwrap(get("api/products/$id"), "product").obj())

    fun reviews(productId: Int, page: Int, size: Int = 50): Page<Review> {
        val root = get("api/products/$productId/reviews?page=$page&size=$size")
        return Page(list(root).map { Review.of(it.obj()) }, (root as? JsonObject)?.i("total", "count") ?: -1)
    }

    fun categories(): List<Category> = list(get("api/categories")).map { Category.of(it.obj()) }

    fun deals(): List<Deal> = list(get("api/deals")).map { Deal.of(it) }

    fun hero(): List<Hero> = list(get("api/hero")).map { Hero.of(it.obj()) }

    /** The terms dialog's text (`{title, text}`). */
    fun terms(): String = (get("api/terms") as? JsonObject)?.s("text") ?: ""

    fun feed(cursor: Int, size: Int = 100): Page<FeedRow> {
        val root = get("api/feed?cursor=$cursor&size=$size")
        // Rows have no index field: a row's index is its position (shared/fixtures-api.md).
        return Page(list(root).mapIndexed { i, e -> FeedRow.of(e.obj(), cursor + i) }, (root as? JsonObject)?.i("total", "count") ?: -1)
    }

    fun symbols(): List<Symbol> = list(get("api/symbols")).mapIndexed { i, e -> Symbol.of(e.obj(), i) }

    fun candles(symbol: String, tf: String): List<Candle> =
        list(get("api/candles?symbol=${enc(symbol)}&tf=$tf")).map { Candle.of(it) }

    fun quotes(since: Int): Int = (get("api/quotes?since=$since") as? JsonObject)?.i("n") ?: since

    fun search(q: String): List<Product> = list(get("api/search?q=${enc(q)}")).map { Product.of(it.obj()) }

    fun promos(q: String): List<Promo> = list(get("api/promos?q=${enc(q)}")).map { Promo.of(it) }

    fun countries(): List<String> = list(get("api/countries")).map { e ->
        (e as? JsonPrimitive)?.content ?: e.obj().s("name", "title") ?: e.toString()
    }

    fun checkout(form: Map<String, String>): String {
        val body = buildJsonObject { form.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
        val req = Request.Builder().url("$base/api/checkout").post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("checkout ${r.code}")
            val root = json.parseToJsonElement(r.body!!.string())
            return (root as? JsonObject)?.s("order") ?: root.toString()
        }
    }

    /** `GET /fx/api/fail/500` and `/fx/api/slow?ms=`: the status code, for the diagnostics buttons. */
    fun status(path: String): Int = http.newCall(Request.Builder().url("$base/$path").build()).execute().use { it.code }

    fun productImage(productId: Int, index: Int, size: Int): String = "$base/img/p/$productId/$index/$size.jpg"
    fun heroImage(slide: Int): String = "$base/img/hero/$slide/1600.jpg"
    /** A path relative to `fmFixtures` (`img/hero/1/1600.jpg`), as the hero route gives it. */
    fun fixture(path: String): String = "$base/${path.trimStart('/')}"
    fun avatarImage(avatarId: Int): String = "$base/img/avatar/${((avatarId - 1).mod(64)) + 1}/96.jpg"
    fun badgeImage(name: String): String = "$base/img/badge/${enc(name.lowercase())}.png"

    private fun get(path: String): JsonElement {
        val req = Request.Builder().url("$base/$path").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GET $path: ${r.code}")
            return json.parseToJsonElement(r.body!!.string())
        }
    }

    companion object {
        const val PAGE_SIZE = 40
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

class Page<T>(val items: List<T>, val total: Int)

/** A bare array, or the first array field of an object (`items`, `data`, …). */
internal fun list(root: JsonElement): List<JsonElement> = when (root) {
    is JsonArray -> root
    is JsonObject -> (listOf("items", "data", "results", "rows", "products", "reviews", "candles", "symbols",
        "categories", "deals", "promos", "countries").firstNotNullOfOrNull { root[it] as? JsonArray }
        ?: root.values.firstOrNull { it is JsonArray } as? JsonArray) ?: emptyList()
    else -> emptyList()
}

internal fun unwrap(root: JsonElement, key: String): JsonElement = (root as? JsonObject)?.get(key) as? JsonObject ?: root

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.s(vararg names: String): String? {
    for (n in names) (this[n] as? JsonPrimitive)?.let { if (it.isString || it.longOrNull != null) return it.content }
    return null
}

internal fun JsonObject.l(vararg names: String): Long? {
    for (n in names) (this[n] as? JsonPrimitive)?.let { p -> (p.longOrNull ?: p.doubleOrNull?.toLong())?.let { return it } }
    return null
}

internal fun JsonObject.i(vararg names: String): Int? = l(*names)?.toInt()

internal fun JsonObject.d(vararg names: String): Double? {
    for (n in names) (this[n] as? JsonPrimitive)?.doubleOrNull?.let { return it }
    return null
}

internal fun JsonObject.strings(vararg names: String): List<String> {
    for (n in names) (this[n] as? JsonArray)?.let { a ->
        return a.map { (it as? JsonPrimitive)?.content ?: it.obj().s("name", "label", "code") ?: it.toString() }
    }
    return emptyList()
}
