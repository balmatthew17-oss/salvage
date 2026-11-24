package com.salvage.app

import org.json.JSONObject
import java.time.Instant
import java.util.Locale

enum class Kind(val label: String) {
    CONSOLE("Consoles"), CONTROLLER("Controllers"), GPU("GPUs"), LAPTOP("Laptops")
}

private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

/** Something Salvage searches for. Each has its own eBay search and price limits. */
enum class Console(
    val key: String,
    val label: String,
    val query: String,
    val defaultMax: Int,
    /** Buy it now listings at or under this send a notification. */
    val defaultAlert: Int,
    val kind: Kind,
    /** eBay category to search in. */
    val category: String,
    private val pattern: Regex,
) {
    PS5("ps5", "PS5", "(ps5, playstation 5)", 150, 100, Kind.CONSOLE, "139971",
        rx("""\b(ps\s?5|play\s?station\s?5)\b""")),
    SERIES_X("xsx", "Series X", "xbox series x", 130, 90, Kind.CONSOLE, "139971",
        rx("""series\s?x\b""")),
    SERIES_S("xss", "Series S", "xbox series s", 70, 45, Kind.CONSOLE, "139971",
        rx("""series\s?s\b""")),
    DUALSENSE("ds", "DualSense", "(dualsense, ps5 controller)", 20, 12, Kind.CONTROLLER, "117042",
        rx("""dual\s?sense|\b(ps\s?5|play\s?station\s?5)\b""")),
    XBOX_PAD("xpad", "Xbox pad", "(xbox series controller, xbox wireless controller)", 15, 10, Kind.CONTROLLER, "117042",
        rx("""\bxbox\b""")),
    RTX20("rtx20", "RTX 20", "(2060, 2070, 2080)", 80, 45, Kind.GPU, "27386",
        rx("""\b(rtx\s?)?20[678]0\b""")),
    RTX30("rtx30", "RTX 30", "(3050, 3060, 3070, 3080, 3090)", 200, 110, Kind.GPU, "27386",
        rx("""\b(rtx\s?)?30[5-9]0\b""")),
    RTX40("rtx40", "RTX 40", "(4060, 4070, 4080, 4090)", 450, 250, Kind.GPU, "27386",
        rx("""\b(rtx\s?)?40[6-9]0\b""")),
    RTX50("rtx50", "RTX 50", "(5060, 5070, 5080, 5090)", 650, 380, Kind.GPU, "27386",
        rx("""\b(rtx\s?)?50[6-9]0\b""")),
    RTX_LAPTOP("laptop", "RTX laptop", "rtx", 350, 200, Kind.LAPTOP, "177",
        rx("""\brtx|\b(20[678]0|30[5-8]0|40[5-9]0|50[5-9]0)\b"""));

    val isController: Boolean get() = kind == Kind.CONTROLLER

    fun matches(title: String) = pattern.containsMatchIn(title)

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }

        private val gpuRegex = rx("""\b(?:rtx\s?)?((?:20|30|40|50)[5-9]0)(\s?ti\b|\s?super\b)?""")

        /** "RTX 3080 Ti" from a title, or null. */
        fun gpuModel(title: String): String? {
            val m = gpuRegex.find(title) ?: return null
            val suffix = m.groupValues[2].trim().lowercase().let {
                when (it) { "ti" -> " Ti"; "super" -> " Super"; else -> "" }
            }
            return "RTX " + m.groupValues[1] + suffix
        }
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
    /** What the seller's description / condition notes say, once read. */
    val notes: Notes? = null,
) {
    val total: Double get() = price + (postage ?: 0.0)
    val analysis: Analysis = Faults.analyse(title, notes?.tags, console.kind)

    /** What to call it in the list: the GPU model where there is one, otherwise the type. */
    val shortName: String
        get() = if (console.kind == Kind.GPU || console.kind == Kind.LAPTOP)
            Console.gpuModel(title)?.let { if (console.kind == Kind.LAPTOP) "$it laptop" else it } ?: console.label
        else console.label

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("console", console.key)
        put("price", price); postage?.let { put("postage", it) }
        put("auction", auction); put("bestOffer", bestOffer); put("bids", bids)
        endTime?.let { put("end", it) }; created?.let { put("created", it) }
        put("url", url); image?.let { put("image", it) }; location?.let { put("loc", it) }
        notes?.let { put("notes", it.toJson()) }
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
                notes = o.optJSONObject("notes")?.let { Notes.fromJson(it) },
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

/** Fault info pulled from a listing's description. [summary] is null if the seller didn't say. */
data class Notes(val summary: String?, val tags: List<String>) {
    fun toJson(): JSONObject = JSONObject().apply {
        summary?.let { put("s", it) }
        put("t", org.json.JSONArray(tags))
    }

    companion object {
        fun fromJson(o: JSONObject): Notes {
            val arr = o.optJSONArray("t")
            val tags = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
            return Notes(o.optString("s").ifEmpty { null }, tags)
        }
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
