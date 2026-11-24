package com.salvage.app

import org.json.JSONObject
import java.time.Instant
import java.util.Locale

enum class Console(
    val key: String,
    val label: String,
    val query: String,
    val defaultMax: Int,
    val isController: Boolean,
    /** eBay category: Video Game Consoles, or Controllers & Attachments. */
    val category: String,
    private val pattern: Regex,
) {
    PS5("ps5", "PS5", "(ps5, playstation 5)", 150, false, "139971",
        Regex("""\b(ps\s?5|play\s?station\s?5)\b""", RegexOption.IGNORE_CASE)),
    SERIES_X("xsx", "Series X", "xbox series x", 130, false, "139971",
        Regex("""series\s?x\b""", RegexOption.IGNORE_CASE)),
    SERIES_S("xss", "Series S", "xbox series s", 70, false, "139971",
        Regex("""series\s?s\b""", RegexOption.IGNORE_CASE)),
    DUALSENSE("ds", "DualSense", "(dualsense, ps5 controller)", 20, true, "117042",
        Regex("""dual\s?sense|\b(ps\s?5|play\s?station\s?5)\b""", RegexOption.IGNORE_CASE)),
    XBOX_PAD("xpad", "Xbox pad", "(xbox series controller, xbox wireless controller)", 15, true, "117042",
        Regex("""\bxbox\b""", RegexOption.IGNORE_CASE));

    fun matches(title: String) = pattern.containsMatchIn(title)

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class Deal(
    val id: String,
    val title: String,
    val console: Console,
    val price: Double,
    val postage: Double?,
    val auction: Boolean,
    val bestOffer: Boolean,
    val bids: Int,
    val endTime: Long?,
    val created: Long?,
    val url: String,
    val image: String?,
    val location: String?,
) {
    val total: Double get() = price + (postage ?: 0.0)
    val analysis: Analysis = Faults.analyse(title)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("console", console.key)
        put("price", price); postage?.let { put("postage", it) }
        put("auction", auction); put("bestOffer", bestOffer); put("bids", bids)
        endTime?.let { put("end", it) }; created?.let { put("created", it) }
        put("url", url); image?.let { put("image", it) }; location?.let { put("loc", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Deal? = try {
            Deal(
                id = o.getString("id"),
                title = o.getString("title"),
                console = Console.byKey(o.getString("console")) ?: throw IllegalStateException(),
                price = o.getDouble("price"),
                postage = if (o.has("postage")) o.getDouble("postage") else null,
                auction = o.optBoolean("auction"),
                bestOffer = o.optBoolean("bestOffer"),
                bids = o.optInt("bids", 0),
                endTime = if (o.has("end")) o.getLong("end") else null,
                created = if (o.has("created")) o.getLong("created") else null,
                url = o.getString("url"),
                image = o.optString("image").ifEmpty { null },
                location = o.optString("loc").ifEmpty { null },
            )
        } catch (e: Exception) { null }

        fun fromEbay(o: JSONObject, console: Console): Deal? {
            val id = o.optString("itemId").ifEmpty { return null }
            val title = o.optString("title").ifEmpty { return null }
            val url = o.optString("itemWebUrl").ifEmpty { return null }

            val options = o.optJSONArray("buyingOptions")
            val opts = (0 until (options?.length() ?: 0)).map { options!!.optString(it) }
            val auction = "AUCTION" in opts && "FIXED_PRICE" !in opts

            val priceObj = if (auction) o.optJSONObject("currentBidPrice") ?: o.optJSONObject("price")
                           else o.optJSONObject("price") ?: o.optJSONObject("currentBidPrice")
            val price = priceObj?.optString("value")?.toDoubleOrNull() ?: return null

            // Cheapest postage option, if eBay lists one.
            var postage: Double? = null
            o.optJSONArray("shippingOptions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)?.optJSONObject("shippingCost")
                        ?.optString("value")?.toDoubleOrNull() ?: continue
                    if (postage == null || v < postage!!) postage = v
                }
            }

            val image = o.optJSONObject("image")?.optString("imageUrl")?.ifEmpty { null }
                ?: o.optJSONArray("thumbnailImages")?.optJSONObject(0)?.optString("imageUrl")?.ifEmpty { null }

            val loc = o.optJSONObject("itemLocation")?.let { l ->
                l.optString("city").ifEmpty { null } ?: l.optString("postalCode").ifEmpty { null }
            }

            return Deal(
                id = id,
                title = title,
                console = console,
                price = price,
                postage = postage,
                auction = auction,
                bestOffer = "BEST_OFFER" in opts,
                bids = o.optInt("bidCount", 0),
                endTime = parseTime(o.optString("itemEndDate")),
                created = parseTime(o.optString("itemCreationDate")),
                url = url,
                image = image,
                location = loc,
            )
        }

        private fun parseTime(s: String?): Long? =
            if (s.isNullOrEmpty()) null else try { Instant.parse(s).toEpochMilli() } catch (e: Exception) { null }
    }
}

fun money(v: Double): String =
    if (v % 1.0 == 0.0) "£" + v.toInt() else String.format(Locale.UK, "£%.2f", v)

/** "3h 12m", "2d 4h", "45m" */
fun duration(ms: Long): String {
    val mins = ms / 60_000
    return when {
        mins < 1 -> "<1m"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h ${mins % 60}m"
        else -> "${mins / (60 * 24)}d ${(mins / 60) % 24}h"
    }
}
