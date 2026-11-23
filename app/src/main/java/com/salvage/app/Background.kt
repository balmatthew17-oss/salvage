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
import kotlinx.coroutines.Dispatchers
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

        for (console in Console.entries) {
            if (!store.enabled(console)) continue
            try {
                ebay.search(console, store.maxPrice(console)).forEach { found.putIfAbsent(it.id, it) }
                anyOk = true
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                errors.add("${console.label}: ${e.message ?: "couldn't reach eBay"}")
                // Bad keys will fail every search the same way – stop early.
                if (e is EbayException && e.keyProblem) break
            }
        }
        if (!anyOk && firstError != null) throw firstError!!

        val deals = found.values.toList()
        val seen = store.seen
        val fresh = if (store.primed) deals.filter { it.id !in seen } else emptyList()
        store.addSeen(deals.map { it.id })
        store.primed = true
        store.saveDeals(deals)
        store.lastScan = System.currentTimeMillis()
        ScanResult(deals, fresh, errors)
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
                it.id !in hidden && (store.notifyRisky || !it.analysis.risky)
            }
            Notifier.notifyNew(applicationContext, toNotify)
            Result.success()
        } catch (e: Exception) {
            Result.success() // try again at the next interval
        }
    }
}

object Scheduler {
    private const val NAME = "salvage-scan"

    fun apply(context: Context) {
        val store = Store(context)
        val wm = WorkManager.getInstance(context)
        val mins = store.interval
        if (mins <= 0 || !store.hasKeys) {
            wm.cancelUniqueWork(NAME)
            return
        }
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
            description = "New faulty consoles under your max price"
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
                        .setContentTitle("${d.console.label} · ${money(d.total)} · $kind")
                        .setContentText(d.title)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(
                            d.title + if (tags.isNotEmpty()) "\n$tags" else ""))
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
                deals.take(6).forEach { style.addLine("${money(it.total)}  ${it.console.label} – ${it.title}") }
                if (deals.size > 6) style.setSummaryText("+${deals.size - 6} more")
                val n = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("${deals.size} new faulty consoles")
                    .setContentText(deals.joinToString(", ") { "${it.console.label} ${money(it.total)}" })
                    .setStyle(style)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(1, n)
            }
        } catch (e: SecurityException) { }
    }
}
