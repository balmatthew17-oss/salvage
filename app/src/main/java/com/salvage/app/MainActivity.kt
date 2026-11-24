package com.salvage.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private enum class Sort(val label: String) { NEWEST("Newest"), CHEAPEST("Cheapest"), ENDING("Ending soon") }

    private enum class Tab(val label: String, val sorts: List<Sort>) {
        BUY_NOW("Buy it now", listOf(Sort.NEWEST, Sort.CHEAPEST)),
        AUCTIONS("Auctions", listOf(Sort.ENDING, Sort.NEWEST, Sort.CHEAPEST)),
        CONTROLLERS("Controllers", listOf(Sort.NEWEST, Sort.CHEAPEST, Sort.ENDING));

        fun includes(d: Deal) = when (this) {
            BUY_NOW -> !d.console.isController && !d.auction
            AUCTIONS -> !d.console.isController && d.auction
            CONTROLLERS -> d.console.isController
        }
        fun shows(c: Console) = c.isController == (this == CONTROLLERS)
    }

    private lateinit var store: Store
    private lateinit var adapter: DealAdapter
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var subtitle: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var sortRow: LinearLayout
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: TextView

    private var tab = Tab.BUY_NOW
    private var filter: Console? = null
    private var sort = Sort.NEWEST
    private var all: List<Deal> = emptyList()
    private var error: String? = null
    private var scanning = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Notifier.createChannel(this)

        subtitle = findViewById(R.id.subtitle)
        tabRow = findViewById(R.id.tabs)
        filterRow = findViewById(R.id.filters)
        sortRow = findViewById(R.id.sorts)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
        emptyAction = findViewById(R.id.empty_action)
        refresh = findViewById(R.id.refresh)

        adapter = DealAdapter(this, ::open, ::hide)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        refresh.setColorSchemeColors(getColor(R.color.accent))
        refresh.setProgressBackgroundColorSchemeColor(getColor(R.color.card))
        refresh.setOnRefreshListener { scan() }

        findViewById<ImageButton>(R.id.settings).setOnClickListener { openSettings() }
        emptyAction.setOnClickListener { if (store.hasKeys) scan() else openSettings() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        all = store.loadDeals()
        render()
        Scheduler.apply(this)
        if (store.hasKeys && System.currentTimeMillis() - store.lastScan > 3 * 60_000) scan()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun scan() {
        if (!store.hasKeys) {
            refresh.isRefreshing = false
            render()
            return
        }
        if (scanning) return
        scanning = true
        refresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val result = Scanner(this@MainActivity).scan()
                all = result.deals
                error = result.errors.firstOrNull()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach eBay"
            }
            scanning = false
            refresh.isRefreshing = false
            render()
        }
    }

    private fun open(d: Deal) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the listing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hide(d: Deal) {
        store.hide(d.id)
        Toast.makeText(this, "Hidden. You can bring hidden listings back in Settings.", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun visibleDeals(): List<Deal> {
        val hidden = store.hidden
        val now = System.currentTimeMillis()
        val list = all.filter { it.id !in hidden && tab.includes(it) && (filter == null || it.console == filter) }
            // Drop auctions that have already finished.
            .filter { !(it.auction && (it.endTime ?: Long.MAX_VALUE) < now) }
        return when (sort) {
            Sort.NEWEST -> list.sortedByDescending { it.created ?: 0L }
            Sort.CHEAPEST -> list.sortedBy { it.total }
            Sort.ENDING -> list.sortedWith(
                compareBy<Deal> { if (it.auction) 0 else 1 }
                    .thenBy { it.endTime ?: Long.MAX_VALUE }
                    .thenByDescending { it.created ?: 0L }
            )
        }
    }

    private fun render() {
        val shown = visibleDeals()
        adapter.submit(shown)
        buildChips()

        val last = store.lastScan
        subtitle.text = when {
            !store.hasKeys -> "Faulty consoles on eBay UK"
            error != null -> error
            last == 0L -> "Checking eBay…"
            else -> {
                val ago = System.currentTimeMillis() - last
                "${shown.size} listings · updated " + if (ago < 60_000) "just now" else "${duration(ago)} ago"
            }
        }
        subtitle.setTextColor(getColor(if (error != null && store.hasKeys) R.color.risky else R.color.muted))

        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        when {
            !store.hasKeys -> {
                emptyText.text = "Add your eBay API keys to start finding faulty PS5s and Xboxes."
                emptyAction.text = "Open settings"
                emptyAction.visibility = View.VISIBLE
            }
            scanning || (last == 0L && error == null) -> {
                emptyText.text = "Searching eBay…"
                emptyAction.visibility = View.GONE
            }
            error != null && all.isEmpty() -> {
                emptyText.text = error
                emptyAction.text = "Try again"
                emptyAction.visibility = View.VISIBLE
            }
            tab == Tab.CONTROLLERS && Console.entries.none { it.isController && store.enabled(it) } -> {
                emptyText.text = "Controller searches are turned off.\nSwitch them on in Settings."
                emptyAction.visibility = View.GONE
            }
            else -> {
                emptyText.text = "Nothing here under your max prices right now.\nPull down to check again, or raise the limits in Settings."
                emptyAction.visibility = View.GONE
            }
        }
    }

    // --- filter + sort chips ---

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun chip(text: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(if (on) R.color.bg else R.color.text))
        setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        setOnClickListener { click() }
    }

    private fun tabButton(text: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = android.view.Gravity.CENTER
        maxLines = 1
        setTextColor(getColor(if (on) R.color.bg else R.color.muted))
        if (on) setBackgroundResource(R.drawable.chip_on) else background = null
        setPadding(dp(4), dp(9), dp(4), dp(9))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { click() }
    }

    private fun buildChips() {
        val hidden = store.hidden
        val now = System.currentTimeMillis()
        val live = all.filter { it.id !in hidden && !(it.auction && (it.endTime ?: Long.MAX_VALUE) < now) }

        tabRow.removeAllViews()
        for (t in Tab.entries) {
            val n = live.count { t.includes(it) }
            tabRow.addView(tabButton("${t.label}  $n", tab == t) {
                if (tab != t) {
                    tab = t
                    filter = null
                    sort = t.sorts.first()
                    render()
                    findViewById<RecyclerView>(R.id.list).scrollToPosition(0)
                }
            })
        }

        val inTab = live.filter { tab.includes(it) }
        filterRow.removeAllViews()
        filterRow.addView(chip("All ${inTab.size}", filter == null) { filter = null; render() })
        for (c in Console.entries) {
            if (!tab.shows(c) || !store.enabled(c)) continue
            val n = inTab.count { it.console == c }
            filterRow.addView(chip("${c.label} $n", filter == c) { filter = c; render() })
        }

        sortRow.removeAllViews()
        for (s in tab.sorts) {
            sortRow.addView(chip(s.label, sort == s) {
                sort = s
                render()
                findViewById<RecyclerView>(R.id.list).scrollToPosition(0)
            })
        }
    }
}
