package com.salvage.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ScanResult(val deals: List<Deal>, val fresh: List<Deal>, val errors: List<String>)

/** Runs every enabled search, saves the results and works out which listings are new. */
class Scanner(context: Context) {
    private val store = Store(context)

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val ebay = Ebay(store)
        val found = LinkedHashMap<String, Deal>()
        val errors = ArrayList<String>()
        var firstError: Exception? = null
        var anyOk = false
        val searched = ArrayList<Console>()

        for (console in Console.entries) {
            if (!store.enabled(console)) continue
            try {
                ebay.search(console, store.maxPrice(console)).forEach { found.putIfAbsent(it.id, it) }
                searched.add(console)
                anyOk = true
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                errors.add("${console.label}: ${e.message ?: "couldn't reach eBay"}")
                // Bad keys will fail every search the same way – stop early.
                if (e is EbayException && e.keyProblem) break
            }
        }
        if (!anyOk && firstError != null) throw firstError!!

        val deals = withNotes(ebay, found.values.toList())
        val seen = store.seen
        val fresh = deals.filter { it.id !in seen && store.primed(it.console) }
        store.addSeen(deals.map { it.id })
        searched.forEach { store.setPrimed(it) }
        store.saveDeals(deals)
        store.lastScan = System.currentTimeMillis()
        ScanResult(deals, fresh, errors)
    }

    /** Adds what each description says. Each listing is read once, then cached. */
    private suspend fun withNotes(ebay: Ebay, deals: List<Deal>): List<Deal> = coroutineScope {
        val cache = store.notes()
        val toRead = deals.filter { it.id !in cache }
            .sortedByDescending { it.created ?: 0L }
            .take(MAX_READS_PER_SCAN)
        val gate = Semaphore(5)
        toRead.map { d ->
            async {
                gate.withPermit {
                    try { d.id to ebay.readNotes(d.id, d.console.kind) } catch (e: Exception) { null }
                }
            }
        }.awaitAll().filterNotNull().forEach { (id, notes) -> cache[id] = notes }
        store.saveNotes(cache, deals.map { it.id }.toSet())
        deals.map { d -> cache[d.id]?.let { d.copy(notes = it) } ?: d }
    }

    companion object {
        /** Keeps eBay API use well under the daily limit; the rest get read on the next scan. */
        private const val MAX_READS_PER_SCAN = 80
    }
}

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        if (!store.hasKeys) return Result.success()
        return try {
            val result = Scanner(applicationContext).scan()
            val hidden = store.hidden
            val toNotify = result.fresh.filter {
                it.id !in hidden && (store.notifyRisky || !it.analysis.risky) && Alerts.worthIt(store, it)
            }
            Notifier.notifyNew(applicationContext, toNotify)
            Result.success()
        } catch (e: Exception) {
            Result.success() // try again at the next interval
        } finally {
            Scheduler.chainFast(applicationContext, fromChain = Scheduler.FAST_TAG in tags)
        }
    }
}

object Alerts {
    private const val EASY_FIX_MARGIN = 1.2

    /** Is this new listing worth a notification? */
    fun worthIt(store: Store, d: Deal): Boolean {
        if (d.auction) return store.auctionAlerts
        val alert = store.alertPrice(d.console)
        if (d.total <= alert) return true
        return store.easyFixBoost && isEasyFix(d) && d.total <= alert * EASY_FIX_MARGIN
    }

    /** Has a green fault tag (not just "Powers on" or cosmetic) and no amber/red ones. */
    fun isEasyFix(d: Deal): Boolean {
        val tags = d.analysis.tags
        return tags.any { it.level == Level.EASY && it.label != "Powers on" && it.label != "Cosmetic" } &&
            tags.none { it.level != Level.EASY }
    }
}

object Scheduler {
    private const val NAME = "salvage-scan"
    private const val FAST = "salvage-fast"
    const val FAST_MINUTES = 5
    const val FAST_TAG = "fast"

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun fastRequest() = OneTimeWorkRequestBuilder<ScanWorker>()
        .addTag(FAST_TAG)
        .setInitialDelay(FAST_MINUTES.toLong(), TimeUnit.MINUTES)
        .setConstraints(constraints())
        .build()

    /**
     * Called at the end of each check: queues the next one when the 5-minute option is on.
     * A check from the chain adds the next link; the 30-minute backup only restarts a broken chain.
     */
    fun chainFast(context: Context, fromChain: Boolean) {
        val store = Store(context)
        if (store.interval != FAST_MINUTES || !store.hasKeys) return
        val policy = if (fromChain) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(FAST, policy, fastRequest())
    }

    fun apply(context: Context) {
        val store = Store(context)
        val wm = WorkManager.getInstance(context)
        val mins = store.interval
        if (mins <= 0 || !store.hasKeys) {
            wm.cancelUniqueWork(NAME)
            wm.cancelUniqueWork(FAST)
            return
        }
        if (mins == FAST_MINUTES) {
            // Keep a slow periodic check as a safety net in case the chain is ever dropped.
            val backup = PeriodicWorkRequestBuilder<ScanWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints()).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, backup)
            wm.enqueueUniqueWork(FAST, ExistingWorkPolicy.KEEP, fastRequest())
            return
        }
        wm.cancelUniqueWork(FAST)
        val request = PeriodicWorkRequestBuilder<ScanWorker>(mins.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

object Notifier {
    const val CHANNEL = "new_listings"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "New listings", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "New faulty listings under your alert price"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun notifyNew(context: Context, deals: List<Deal>) {
        if (deals.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val nm = NotificationManagerCompat.from(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        try {
            if (deals.size <= 3) {
                for (d in deals) {
                    val open = PendingIntent.getActivity(
                        context, d.id.hashCode(),
                        Intent(Intent.ACTION_VIEW, Uri.parse(d.url)), flags
                    )
                    val tags = d.analysis.tags.joinToString(" · ") { it.label }
                    val kind = if (d.auction) "Auction" else "Buy it now"
                    val n = NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_notify)
                        .setContentTitle("${d.shortName} · ${money(d.total)} · $kind")
                        .setContentText(d.title)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(
                            d.title + (if (tags.isNotEmpty()) "\n$tags" else "") +
                                (d.notes?.summary?.let { "\n“$it”" } ?: "")))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(d.id.hashCode(), n)
                }
            } else {
                val open = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), flags
                )
                val style = NotificationCompat.InboxStyle()
                deals.take(6).forEach { style.addLine("${money(it.total)}  ${it.shortName} – ${it.title}") }
                if (deals.size > 6) style.setSummaryText("+${deals.size - 6} more")
                val n = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("${deals.size} new faulty listings")
                    .setContentText(deals.joinToString(", ") { "${it.shortName} ${money(it.total)}" })
                    .setStyle(style)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(1, n)
            }
        } catch (e: SecurityException) { }
    }
}
