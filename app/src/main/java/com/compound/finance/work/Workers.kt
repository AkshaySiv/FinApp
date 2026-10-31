package com.compound.finance.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.compound.finance.CompoundApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Records each bank SMS the moment it arrives. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Long messages arrive in several parts: join them per sender.
        val bySender = parts.filterNotNull().groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
        val repo = (context.applicationContext as CompoundApp).repo
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for ((sender, msgs) in bySender) {
                    val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                    val ts = msgs.first().timestampMillis
                    repo.process(sender, body, ts, notify = true)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Runs once a day: catches up on any SMS the receiver missed (phone off, app updated) and
 * sends reminders for EMIs, card payments and bills due soon.
 */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CompoundApp
        val repo = app.repo
        runCatching {
            val since = (repo.db.sms().latest() ?: (System.currentTimeMillis() - Duration.ofDays(30).toMillis())) - Duration.ofDays(2).toMillis()
            repo.importInbox(since)
        }
        val s = repo.prefs.current
        if (s.notifyDues) {
            val today = LocalDate.now()
            for (d in repo.upcomingDues(days = 3)) {
                val days = ChronoUnit.DAYS.between(today, d.date)
                val whenText = when { days < 0 -> "was due ${-days} day${if (days < -1) "s" else ""} ago"; days == 0L -> "is due today"; days == 1L -> "is due tomorrow"; else -> "is due in $days days" }
                val key = "${d.kind}:${d.title}:${d.date}:$days"
                if (!repo.prefs.alertSent(key)) {
                    repo.prefs.markAlert(key)
                    Notifier.due(applicationContext, "${d.title} $whenText",
                        "${com.compound.finance.data.Repository.fmt(d.amount, d.currency)} · ${d.detail}", key)
                }
            }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(9, 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)
            val req = PeriodicWorkRequestBuilder<DailyWorker>(Duration.ofDays(1))
                .setInitialDelay(delay)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
