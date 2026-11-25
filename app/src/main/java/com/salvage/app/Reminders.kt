package com.salvage.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A reminder before an auction ends. [auto] = added because it was under the alert price. */
class Reminder(val deal: Deal, val auto: Boolean)

/** Live state of an auction, read just before the reminder goes off. */
class AuctionStatus(val price: Double, val bids: Int, val endTime: Long?)

object Reminders {
    const val CHANNEL = "auction_ending"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Auctions ending", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders shortly before auctions you're watching end"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun isOn(store: Store, id: String) = store.reminders().containsKey(id)

    /** Bell tapped: start or stop reminding about this auction. Returns true if now on. */
    fun toggle(context: Context, d: Deal): Boolean {
        val store = Store(context)
        return if (isOn(store, d.id)) {
            store.removeReminder(d.id)
            store.addNoRemind(d.id) // don't let the automatic reminder add it back
            cancel(context, d.id)
            false
        } else {
            store.saveReminder(Reminder(d, auto = false))
            store.removeNoRemind(d.id)
            schedule(context, d.id, d.endTime ?: return false)
            true
        }
    }

    /** After each scan: remind about auctions still under the alert price. */
    fun autoAdd(context: Context, deals: List<Deal>) {
        val store = Store(context)
        val now = System.currentTimeMillis()
        val current = deals.associateBy { it.id }
        val reminders = store.reminders()

        // Keep the saved copy of watched auctions up to date (bids, price).
        reminders.values.forEach { r ->
            current[r.deal.id]?.let { store.saveReminder(Reminder(it, r.auto)) }
        }
        if (!store.autoReminders) return

        val hidden = store.hidden
        val noRemind = store.noRemind
        val leadMs = store.reminderLead * 60_000L
        for (d in deals) {
            val end = d.endTime ?: continue
            if (!d.auction || end - leadMs < now) continue
            if (d.id in reminders || d.id in hidden || d.id in noRemind) continue
            if (d.analysis.risky && !store.notifyRisky) continue
            if (d.total > store.alertPrice(d.console)) continue
            store.saveReminder(Reminder(d, auto = true))
            schedule(context, d.id, end)
        }
    }

    /** After a reboot, an app update or a change of reminder time. */
    fun rescheduleAll(context: Context) {
        val store = Store(context)
        val now = System.currentTimeMillis()
        for (r in store.reminders().values) {
            val end = r.deal.endTime
            if (end == null || end < now) {
                store.removeReminder(r.deal.id)
                cancel(context, r.deal.id)
            } else {
                schedule(context, r.deal.id, end)
            }
        }
    }

    fun canBeExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun schedule(context: Context, id: String, end: Long) {
        val now = System.currentTimeMillis()
        if (end <= now) return
        val lead = Store(context).reminderLead * 60_000L
        val at = maxOf(end - lead, now + 5_000)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, id)
        try {
            if (canBeExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
    }

    /** The alarm went off: check the live price and tell the user. */
    fun fire(context: Context, id: String) {
        val store = Store(context)
        val r = store.reminders()[id] ?: return
        store.removeReminder(id)
        val d = r.deal

        val live = try { Ebay(store).auctionStatus(id) } catch (e: Exception) { null }
        val price = live?.price ?: d.price
        val bids = live?.bids ?: d.bids
        val end = live?.endTime ?: d.endTime ?: return
        val now = System.currentTimeMillis()
        if (end <= now) return
        val total = price + (d.postage ?: 0.0)
        // Automatic reminders are only for bargains – skip if it has been bid past the alert price.
        if (r.auto && total > store.alertPrice(d.console)) return

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)

        val bidText = if (bids == 1) "1 bid" else "$bids bids"
        val post = d.postage
        val postText = when {
            post == null -> ""
            post == 0.0 -> " · free postage"
            else -> " + ${money(post)} post"
        }
        val tags = d.analysis.tags.joinToString(" · ") { it.label }
        val open = PendingIntent.getActivity(
            context, id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(d.url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Ending in ${duration(end - now)} · ${d.shortName} · ${money(price)}")
            .setContentText(d.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${money(price)}$postText · $bidText\n${d.title}" +
                    (if (tags.isNotEmpty()) "\n$tags" else "") +
                    (d.notes?.summary?.let { "\n“$it”" } ?: "")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(end)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("end" + id).hashCode(), n)
        } catch (e: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reminders.fire(context.applicationContext, id) } finally { result.finish() }
        }
    }
}

/** Alarms are wiped by a reboot or an app update, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleAll(context.applicationContext)
        Scheduler.apply(context.applicationContext)
    }
}
