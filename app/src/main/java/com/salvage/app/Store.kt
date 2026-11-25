package com.salvage.app

import android.content.Context
import org.json.JSONArray

class Store(context: Context) {

    companion object {
        /** Searches from the very first version; newer ones start un-primed so they don't flood notifications. */
        private val FIRST_TYPES = setOf("ps5", "xsx", "xss")
    }

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

    fun alertPrice(c: Console) = p.getInt("alert_${c.key}", c.defaultAlert)
    fun setAlertPrice(c: Console, v: Int) = p.edit().putInt("alert_${c.key}", v).apply()

    /** Also alert for easy-fix Buy it now listings up to 20% over the alert price. */
    var easyFixBoost: Boolean
        get() = p.getBoolean("easy_boost", true)
        set(v) = p.edit().putBoolean("easy_boost", v).apply()

    /** Notify for every new auction under the max price. */
    var auctionAlerts: Boolean
        get() = p.getBoolean("auction_alerts", true)
        set(v) = p.edit().putBoolean("auction_alerts", v).apply()

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
    fun primed(c: Console): Boolean =
        p.getBoolean("primed_${c.key}", c.key in FIRST_TYPES && p.getBoolean("primed", false))
    fun setPrimed(c: Console) = p.edit().putBoolean("primed_${c.key}", true).apply()

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

    // --- description notes, cached so each listing is only read once ---

    fun notes(): MutableMap<String, Notes> {
        val out = LinkedHashMap<String, Notes>()
        try {
            val o = org.json.JSONObject(p.getString("notes", "{}") ?: "{}")
            o.keys().forEach { k -> o.optJSONObject(k)?.let { out[k] = Notes.fromJson(it) } }
        } catch (e: Exception) { }
        return out
    }

    fun saveNotes(map: Map<String, Notes>, keep: Set<String>) {
        val o = org.json.JSONObject()
        // Only keep notes for listings still being shown, plus the most recent others.
        val entries = map.entries.toList()
        val recent = entries.takeLast(600).map { it.key }.toSet()
        entries.filter { it.key in keep || it.key in recent }.forEach { o.put(it.key, it.value.toJson()) }
        p.edit().putString("notes", o.toString()).apply()
    }

    // --- auction reminders ---

    /** Minutes before the end of an auction to remind you. */
    var reminderLead: Int
        get() = p.getInt("reminder_lead", 10)
        set(v) = p.edit().putInt("reminder_lead", v).apply()

    /** Automatically remind about auctions still under the alert price. */
    var autoReminders: Boolean
        get() = p.getBoolean("auto_reminders", true)
        set(v) = p.edit().putBoolean("auto_reminders", v).apply()

    fun reminders(): Map<String, Reminder> {
        val out = LinkedHashMap<String, Reminder>()
        try {
            val o = org.json.JSONObject(p.getString("reminders", "{}") ?: "{}")
            o.keys().forEach { k ->
                val r = o.optJSONObject(k) ?: return@forEach
                val deal = r.optJSONObject("deal")?.let { Deal.fromJson(it) } ?: return@forEach
                out[k] = Reminder(deal, r.optBoolean("auto"))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveReminders(map: Map<String, Reminder>) {
        val o = org.json.JSONObject()
        map.forEach { (k, r) -> o.put(k, org.json.JSONObject().put("deal", r.deal.toJson()).put("auto", r.auto)) }
        p.edit().putString("reminders", o.toString()).commit()
    }

    fun saveReminder(r: Reminder) = saveReminders(reminders().toMutableMap().apply { put(r.deal.id, r) })
    fun removeReminder(id: String) = saveReminders(reminders().toMutableMap().apply { remove(id) })

    /** Auctions you switched the bell off for, so automatic reminders don't add them back. */
    val noRemind: Set<String> get() = ids("no_remind")
    fun addNoRemind(id: String) {
        val s = ids("no_remind"); s.add(id); saveIds("no_remind", s, 500)
    }
    fun removeNoRemind(id: String) {
        val s = ids("no_remind"); s.remove(id); saveIds("no_remind", s, 500)
    }

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
