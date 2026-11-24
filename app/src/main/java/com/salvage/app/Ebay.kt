package com.salvage.app

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class EbayException(message: String, val keyProblem: Boolean = false) : Exception(message)

/**
 * Talks to eBay's official Browse API (eBay UK marketplace).
 * Uses an application token from the user's own Client ID + Client Secret.
 */
class Ebay(private val store: Store) {

    companion object {
        private const val TOKEN_URL = "https://api.ebay.com/identity/v1/oauth2/token"
        private const val SEARCH_URL = "https://api.ebay.com/buy/browse/v1/item_summary/search"
        private const val ITEM_URL = "https://api.ebay.com/buy/browse/v1/item/"
        private const val SCOPE = "https://api.ebay.com/oauth/api_scope"
        /** eBay condition "For parts or not working". */
        private const val CONDITION_FAULTY = "7000"
    }

    /** Gets a fresh token, throwing a readable error if the keys are wrong. */
    fun testKeys() {
        store.clearToken()
        token()
    }

    private fun token(): String {
        val now = System.currentTimeMillis()
        val cached = store.cachedToken
        if (cached != null && store.tokenExpiry - 120_000 > now) return cached

        val id = store.clientId.trim()
        val secret = store.clientSecret.trim()
        if (id.isEmpty() || secret.isEmpty()) {
            throw EbayException("Add your eBay API keys in Settings", keyProblem = true)
        }

        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val basic = Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)
            setRequestProperty("Authorization", "Basic $basic")
        }
        val body = "grant_type=client_credentials&scope=" + URLEncoder.encode(SCOPE, "UTF-8")
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val text = readBody(conn, code)
        if (code != 200) {
            // Show eBay's own reason so problems are easy to diagnose.
            val reason = try {
                val e = JSONObject(text)
                listOf(e.optString("error"), e.optString("error_description"))
                    .filter { it.isNotBlank() }.joinToString(": ")
            } catch (ex: Exception) { "" }.ifBlank { text.take(200) }
            val msg = when (code) {
                400, 401 -> "eBay rejected your keys (error $code" +
                    (if (reason.isNotBlank()) " – $reason" else "") + ")"
                else -> "eBay sign-in failed (error $code" +
                    (if (reason.isNotBlank()) " – $reason" else "") + ")"
            }
            throw EbayException(msg, keyProblem = code == 400 || code == 401)
        }
        val json = JSONObject(text)
        val token = json.getString("access_token")
        store.cachedToken = token
        store.tokenExpiry = now + json.optLong("expires_in", 7200) * 1000
        return token
    }

    /** Newest "for parts or not working" listings for one console, up to [maxPrice] GBP. */
    fun search(console: Console, maxPrice: Int): List<Deal> {
        val filter = listOf(
            "price:[5..$maxPrice]",
            "priceCurrency:GBP",
            "conditionIds:{$CONDITION_FAULTY}",
            "itemLocationCountry:GB",
            "buyingOptions:{FIXED_PRICE|AUCTION}",
        ).joinToString(",")

        val url = SEARCH_URL +
            "?q=" + enc(console.query) +
            "&category_ids=" + console.category +
            "&filter=" + enc(filter) +
            "&sort=newlyListed" +
            "&limit=100"

        var json = get(url)
        if (json == null) {
            // Token probably expired early – get a new one and try once more.
            store.clearToken()
            json = get(url) ?: throw EbayException("eBay refused the request. Check your keys in Settings.", true)
        }

        val items = json.optJSONArray("itemSummaries") ?: return emptyList()
        val out = ArrayList<Deal>()
        for (i in 0 until items.length()) {
            var deal = Deal.fromEbay(items.getJSONObject(i), console) ?: continue
            if (!console.matches(deal.title)) continue
            when (console.kind) {
                Kind.CONSOLE -> if (Faults.isControllerListing(deal.title)) {
                    // A controller listed in the console category – show it with the controllers instead.
                    val pad = if (Console.XBOX_PAD.matches(deal.title)) Console.XBOX_PAD else Console.DUALSENSE
                    if (!store.enabled(pad) || deal.total > store.maxPrice(pad)) continue
                    deal = deal.copy(console = pad)
                } else {
                    if (Faults.isAccessory(deal.title)) continue
                    if (deal.total > maxPrice) continue
                }
                Kind.CONTROLLER -> {
                    if (Faults.isConsoleListing(deal.title)) continue
                    if (Faults.isAccessory(deal.title)) continue
                    if (deal.total > maxPrice) continue
                }
                Kind.GPU, Kind.LAPTOP -> {
                    if (Faults.isPcJunk(deal.title)) continue
                    if (deal.total > maxPrice) continue
                }
            }
            out.add(deal)
        }
        return out
    }

    /** Reads one listing's condition notes and description and works out what the seller says is wrong. */
    fun readNotes(itemId: String, kind: Kind): Notes {
        val url = ITEM_URL + enc(itemId)
        val json = get(url) ?: run { store.clearToken(); get(url) }
            ?: throw EbayException("eBay refused the request")
        return Faults.readDescription(
            json.optString("conditionDescription").ifEmpty { null },
            json.optString("description").ifEmpty { json.optString("shortDescription").ifEmpty { null } },
            kind,
        )
    }

    /** Returns null on 401 so the caller can refresh the token. */
    private fun get(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Authorization", "Bearer ${token()}")
            setRequestProperty("X-EBAY-C-MARKETPLACE-ID", "EBAY_GB")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val text = readBody(conn, code)
        if (code == 401) return null
        if (code == 429) throw EbayException("eBay's daily limit reached – try again later")
        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            } catch (e: Exception) { null }
            throw EbayException("eBay error $code" + (detail?.let { ": $it" } ?: ""))
        }
        return JSONObject(text)
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
