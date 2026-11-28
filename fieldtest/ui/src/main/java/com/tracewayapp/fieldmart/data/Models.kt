package com.tracewayapp.fieldmart.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

class Product(
    val id: Int,
    val title: String,
    val brand: String,
    val categoryId: Int,
    val priceCents: Long,
    val compareAtCents: Long?,
    val rating: Double,
    val reviewCount: Int,
    val badges: List<String>,
    val imageCount: Int,
) {
    companion object {
        fun of(o: JsonObject) = Product(
            id = o.i("id") ?: 0,
            title = o.s("title", "name") ?: "",
            brand = o.s("brand") ?: "",
            categoryId = o.i("categoryId", "category") ?: 0,
            priceCents = o.l("priceCents", "price") ?: 0,
            compareAtCents = o.l("compareAtCents", "compareAt"),
            rating = o.d("rating") ?: 0.0,
            reviewCount = o.i("reviewCount", "reviews") ?: 0,
            badges = o.strings("badges"),
            imageCount = o.i("imageCount", "images") ?: 1,
        )
    }
}

class Colour(val name: String, val argb: Int?)

class ProductDetail(
    val product: Product,
    val description: String,
    val sizes: List<String>,
    val colours: List<Colour>,
    val stock: Int,
) {
    companion object {
        fun of(o: JsonObject): ProductDetail {
            val colours = (o["colours"] as? JsonArray ?: o["colors"] as? JsonArray)?.map { e ->
                when (e) {
                    is JsonPrimitive -> Colour(e.content, parseColour(e.content))
                    else -> {
                        val c = e.obj()
                        Colour(c.s("name", "label") ?: "", parseColour(c.s("hex", "rgb", "color", "colour")))
                    }
                }
            } ?: emptyList()
            return ProductDetail(Product.of(o), o.s("description") ?: "", o.strings("sizes"), colours, o.i("stock") ?: 0)
        }

        private fun parseColour(s: String?): Int? = try {
            if (s != null && s.startsWith("#")) android.graphics.Color.parseColor(s) else null
        } catch (_: Throwable) {
            null
        }
    }
}

class Review(val id: String, val author: String, val avatarId: Int, val date: String, val rating: Int, val text: String) {
    companion object {
        fun of(o: JsonObject) = Review(
            id = o.s("id") ?: "",
            author = o.s("author", "name") ?: "",
            avatarId = o.i("avatarId", "avatar") ?: 1,
            date = o.s("date") ?: "",
            rating = o.i("rating") ?: 0,
            text = o.s("text", "body") ?: "",
        )
    }
}

class Category(val id: Int, val name: String) {
    companion object {
        fun of(o: JsonObject) = Category(o.i("id") ?: 0, o.s("name", "title") ?: "")
    }
}

class Deal(val productId: Int, val percent: Int, val badge: String) {
    companion object {
        fun of(e: JsonElement): Deal = when (e) {
            is JsonPrimitive -> Deal(e.longOrNull?.toInt() ?: 0, 0, "sale")
            else -> {
                val o = e.obj()
                val pct = o.i("pct", "discountPercent", "percent", "discount") ?: 0
                Deal(o.i("productId", "id", "product") ?: 0, pct, o.s("badge") ?: "pct-$pct")
            }
        }
    }
}

/** A hero slide: `{id, title, subtitle, image}` with the image relative to `fmFixtures`. */
class Hero(val id: Int, val title: String, val subtitle: String, val image: String) {
    companion object {
        fun of(o: JsonObject) = Hero(o.i("id") ?: 1, o.s("title") ?: "", o.s("subtitle") ?: "", o.s("image") ?: "img/hero/${o.i("id") ?: 1}/1600.jpg")
    }
}

/** A feed row: `order`, `shipment`, `alert`, `message` or `header`, with the fields of its kind. */
class FeedRow(val index: Int, val type: String, val o: JsonObject) {
    val text: String get() = o.s("label", "text", "title", "body", "message") ?: ""
    val lines: List<String> get() = o.strings("lines")

    companion object {
        fun of(o: JsonObject, fallbackIndex: Int): FeedRow {
            val raw = (o.s("type", "kind") ?: "order").lowercase()
            val type = when {
                raw.startsWith("order") -> "order"
                raw.startsWith("ship") -> "shipment"
                raw.contains("alert") || raw.startsWith("price") -> "alert"
                raw.startsWith("msg") || raw.startsWith("message") -> "message"
                raw.startsWith("head") || raw.startsWith("section") || raw == "week" -> "header"
                else -> raw
            }
            return FeedRow(o.i("index", "i", "id") ?: fallbackIndex, type, o)
        }
    }
}

class Symbol(val symbol: String, val name: String, val seedIndex: Int, val startCents: Long) {
    companion object {
        fun of(o: JsonObject, i: Int) = Symbol(
            symbol = o.s("symbol", "id") ?: "S$i",
            name = o.s("name") ?: "",
            seedIndex = o.i("seedIndex", "index") ?: i,
            startCents = o.l("startCents", "cents", "price") ?: 10000,
        )
    }
}

/** One candle `[t, o, h, l, c]` in cents; the live one changes on every tick. */
class Candle(val t: Long, var o: Long, var h: Long, var l: Long, var c: Long) {
    companion object {
        fun of(e: JsonElement): Candle {
            if (e is JsonArray) {
                fun n(i: Int) = (e.getOrNull(i) as? JsonPrimitive)?.longOrNull ?: 0L
                return Candle(n(0), n(1), n(2), n(3), n(4))
            }
            val o = e.obj()
            return Candle(o.l("t") ?: 0, o.l("o") ?: 0, o.l("h") ?: 0, o.l("l") ?: 0, o.l("c") ?: 0)
        }
    }
}

class Promo(val code: String, val label: String) {
    companion object {
        fun of(e: JsonElement): Promo = when (e) {
            is JsonPrimitive -> Promo(e.content, e.content)
            else -> {
                val o = e.obj()
                val code = o.s("code", "id") ?: ""
                Promo(code, o.s("label", "description", "title")?.let { "$code · $it" } ?: code)
            }
        }
    }
}
