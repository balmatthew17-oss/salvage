package com.salvage.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    private lateinit var store: Store
    private lateinit var clientId: EditText
    private lateinit var clientSecret: EditText
    private lateinit var testStatus: TextView
    private lateinit var interval: RadioGroup
    private lateinit var notifyRisky: Switch
    private lateinit var unhide: TextView

    private class ConsoleRow(val console: Console, val toggle: Switch, val max: EditText, val alert: EditText)
    private lateinit var easyBoost: Switch
    private lateinit var auctionAlerts: Switch
    private lateinit var rows: List<ConsoleRow>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = Store(this)

        clientId = findViewById(R.id.client_id)
        clientSecret = findViewById(R.id.client_secret)
        testStatus = findViewById(R.id.test_status)
        interval = findViewById(R.id.interval)
        notifyRisky = findViewById(R.id.notify_risky)
        easyBoost = findViewById(R.id.easy_boost)
        auctionAlerts = findViewById(R.id.auction_alerts)
        unhide = findViewById(R.id.unhide)

        rows = listOf(
            ConsoleRow(Console.PS5, findViewById(R.id.on_ps5), findViewById(R.id.max_ps5), findViewById(R.id.alert_ps5)),
            ConsoleRow(Console.SERIES_X, findViewById(R.id.on_xsx), findViewById(R.id.max_xsx), findViewById(R.id.alert_xsx)),
            ConsoleRow(Console.SERIES_S, findViewById(R.id.on_xss), findViewById(R.id.max_xss), findViewById(R.id.alert_xss)),
            ConsoleRow(Console.DUALSENSE, findViewById(R.id.on_ds), findViewById(R.id.max_ds), findViewById(R.id.alert_ds)),
            ConsoleRow(Console.XBOX_PAD, findViewById(R.id.on_xpad), findViewById(R.id.max_xpad), findViewById(R.id.alert_xpad)),
        )

        clientId.setText(store.clientId)
        clientSecret.setText(store.clientSecret)
        for (r in rows) {
            r.toggle.isChecked = store.enabled(r.console)
            r.max.setText(store.maxPrice(r.console).toString())
            r.alert.setText(store.alertPrice(r.console).toString())
        }
        interval.check(
            when (store.interval) {
                0 -> R.id.int_off
                Scheduler.FAST_MINUTES -> R.id.int_5
                15 -> R.id.int_15
                60 -> R.id.int_60
                else -> R.id.int_30
            }
        )
        notifyRisky.isChecked = store.notifyRisky
        easyBoost.isChecked = store.easyFixBoost
        auctionAlerts.isChecked = store.auctionAlerts
        updateUnhide()

        findViewById<TextView>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.done).setOnClickListener { finish() }
        findViewById<TextView>(R.id.test).setOnClickListener { testKeys() }
        findViewById<TextView>(R.id.dev_link).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.ebay.com/my/keys")))
            } catch (e: Exception) { }
        }
        unhide.setOnClickListener {
            store.unhideAll()
            updateUnhide()
        }
    }

    private fun updateUnhide() {
        val n = store.hidden.size
        unhide.text = if (n == 0) "No hidden listings" else "Show $n hidden listing${if (n == 1) "" else "s"} again"
        unhide.isEnabled = n > 0
        unhide.alpha = if (n > 0) 1f else 0.5f
    }

    private fun saveKeys() {
        val id = clientId.text.toString().trim()
        val secret = clientSecret.text.toString().trim()
        if (id != store.clientId || secret != store.clientSecret) {
            store.clientId = id
            store.clientSecret = secret
            store.clearToken()
        }
    }

    private fun testKeys() {
        saveKeys()
        testStatus.setTextColor(getColor(R.color.muted))
        testStatus.text = "Checking…"
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try { Ebay(store).testKeys(); null } catch (e: Exception) { e.message ?: "Couldn't reach eBay" }
            }
            if (error == null) {
                testStatus.setTextColor(getColor(R.color.easy))
                testStatus.text = "✓ Keys work"
            } else {
                testStatus.setTextColor(getColor(R.color.risky))
                testStatus.text = error
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveKeys()
        var pricesChanged = false
        for (r in rows) {
            val max = r.max.text.toString().trim().toIntOrNull()?.coerceIn(5, 2000) ?: r.console.defaultMax
            if (max != store.maxPrice(r.console) || r.toggle.isChecked != store.enabled(r.console)) pricesChanged = true
            store.setEnabled(r.console, r.toggle.isChecked)
            store.setMaxPrice(r.console, max)
            val alert = r.alert.text.toString().trim().toIntOrNull()?.coerceIn(1, max) ?: minOf(r.console.defaultAlert, max)
            store.setAlertPrice(r.console, alert)
        }
        store.interval = when (interval.checkedRadioButtonId) {
            R.id.int_off -> 0
            R.id.int_5 -> Scheduler.FAST_MINUTES
            R.id.int_15 -> 15
            R.id.int_60 -> 60
            else -> 30
        }
        store.notifyRisky = notifyRisky.isChecked
        store.easyFixBoost = easyBoost.isChecked
        store.auctionAlerts = auctionAlerts.isChecked
        if (pricesChanged) store.lastScan = 0 // refresh results when back on the main screen
        Scheduler.apply(this)
    }
}
