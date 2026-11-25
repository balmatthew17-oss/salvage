package com.salvage.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.View
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var lead: RadioGroup
    private lateinit var autoReminders: Switch
    private val leadIds = mapOf(2 to R.id.lead_2, 5 to R.id.lead_5, 10 to R.id.lead_10, 30 to R.id.lead_30, 60 to R.id.lead_60)
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
        lead = findViewById(R.id.lead)
        autoReminders = findViewById(R.id.auto_reminders)
        unhide = findViewById(R.id.unhide)

        rows = buildTypeRows(findViewById(R.id.types))

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
        lead.check(leadIds[store.reminderLead] ?: R.id.lead_10)
        autoReminders.isChecked = store.autoReminders
        findViewById<TextView>(R.id.exact_allow).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
            }
        }
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

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(getColor(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun priceField(hint: Int) = EditText(this).apply {
        this.hint = hint.toString()
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(4))
        isSingleLine = true
        textSize = 15f
        setTextColor(getColor(R.color.text))
        setHintTextColor(getColor(R.color.muted))
        setBackgroundResource(R.drawable.field)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(4) }
    }

    /** One card per search (PS5, RTX 30...), grouped under a heading per section. */
    private fun buildTypeRows(container: LinearLayout): List<ConsoleRow> {
        val out = ArrayList<ConsoleRow>()
        for (kind in Kind.entries) {
            container.addView(text(kind.label.uppercase(), 13f, R.color.accent, bold = true).apply {
                letterSpacing = 0.06f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(18); bottomMargin = dp(8) }
            })
            for (c in Console.entries.filter { it.kind == kind }) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.card)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                val toggle = Switch(this).apply {
                    text = settingsName(c)
                    textSize = 15f
                    setTextColor(getColor(R.color.text))
                    setTypeface(typeface, Typeface.BOLD)
                }
                card.addView(toggle, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0)
                }
                val max = priceField(c.defaultMax)
                val alert = priceField(c.defaultAlert)
                line.addView(text("Show up to £", 13f, R.color.muted))
                line.addView(max)
                line.addView(text("Alert under £", 13f, R.color.accent).apply { setPadding(dp(14), 0, 0, 0) })
                line.addView(alert)
                card.addView(line)
                container.addView(card)
                out.add(ConsoleRow(c, toggle, max, alert))
            }
        }
        return out
    }

    private fun settingsName(c: Console) = when (c) {
        Console.PS5 -> "PS5 (standard / slim)"
        Console.PS5_PRO -> "PS5 Pro"
        Console.SERIES_X -> "Xbox Series X"
        Console.SERIES_S -> "Xbox Series S"
        Console.DUALSENSE -> "PS5 DualSense"
        Console.XBOX_PAD -> "Xbox controller"
        Console.RTX20 -> "RTX 2060 – 2080"
        Console.RTX30 -> "RTX 3050 – 3090"
        Console.RTX40 -> "RTX 4060 – 4090"
        Console.RTX50 -> "RTX 5060 – 5090"
        Console.RTX_LAPTOP -> "Laptops with an RTX GPU"
    }

    override fun onResume() {
        super.onResume()
        findViewById<View>(R.id.exact_row).visibility =
            if (Reminders.canBeExact(this)) View.GONE else View.VISIBLE
        Reminders.rescheduleAll(this) // switch to exact timing if just allowed
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
        val newLead = leadIds.entries.firstOrNull { it.value == lead.checkedRadioButtonId }?.key ?: 10
        val leadChanged = newLead != store.reminderLead
        store.reminderLead = newLead
        store.autoReminders = autoReminders.isChecked
        if (leadChanged) Reminders.rescheduleAll(this)
        if (pricesChanged) store.lastScan = 0 // refresh results when back on the main screen
        Scheduler.apply(this)
    }
}
