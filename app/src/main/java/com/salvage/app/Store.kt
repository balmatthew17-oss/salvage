package com.salvage.app

import android.content.Context
import org.json.JSONArray

class Store(context: Context) {

    private val p = context.applicationContext.getSharedPreferences("salvage", Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("client_id", "") ?: ""
        set(v) = p.edit().putString("client_id", v.trim()).apply()

    var clientSecret: String
        get() = p.getString("client_secret", "") ?: ""
        set(v) = p.edit().putString("client_secret", v.trim()).apply()

    val hasKeys: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    var cachedToken: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()

    var tokenExpiry: Long
        get() = p.getLong("token_expiry", 0)
        set(v) = p.edit().putLong("token_expiry", v).apply()

    fun clearToken() = p.edit().remove("token").remove("token_expiry").apply()

    fun enabled(c: Console) = p.getBoolean("on_${c.key}", true)
    fun setEnabled(c: Console, on: Boolean) = p.edit().putBoolean("on_${c.key}", on).apply()

    fun maxPrice(c: Console) = p.getInt("max_${c.key}", c.defaultMax)
    fun setMaxPrice(c: Console, v: Int) = p.edit().putInt("max_${c.key}", v).apply()

    /** Background check interval in minutes; 0 = off. */
    var interval: Int
        get() = p.getInt("interval", 30)
        set(v) = p.edit().putInt("interval", v).apply()

    var notifyRisky: Boolean
        get() = p.getBoolean("notify_risky", false)
        set(v) = p.edit().putBoolean("notify_risky", v).apply()

    var lastScan: Long
        get() = p.getLong("last_scan", 0)
        set(v) = p.edit().putLong("last_scan", v).apply()

    /** False until the first scan, so you aren't flooded with notifications for everything already listed. */
    var primed: Boolean
        get() = p.getBoolean("primed", false)
        set(v) = p.edit().putBoolean("primed", v).apply()

    // --- id lists ---

    private fun ids(key: String): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val arr = JSONArray(p.getString(key, "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) { }
        return out
    }

    private fun saveIds(key: String, set: Collection<String>, cap: Int) {
        val list = set.toList().takeLast(cap)
        p.edit().putString(key, JSONArray(list).toString()).apply()
    }

    val seen: Set<String> get() = ids("seen")
    fun addSeen(newIds: Collection<String>) {
        val s = ids("seen"); s.addAll(newIds); saveIds("seen", s, 3000)
    }

    val hidden: Set<String> get() = ids("hidden")
    fun hide(id: String) {
        val s = ids("hidden"); s.add(id); saveIds("hidden", s, 3000)
    }
    fun unhideAll() = p.edit().remove("hidden").apply()

    // --- last results ---

    fun saveDeals(deals: List<Deal>) {
        val arr = JSONArray()
        deals.forEach { arr.put(it.toJson()) }
        p.edit().putString("deals", arr.toString()).apply()
    }

    fun loadDeals(): List<Deal> = try {
        val arr = JSONArray(p.getString("deals", "[]"))
        (0 until arr.length()).mapNotNull { Deal.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
}
