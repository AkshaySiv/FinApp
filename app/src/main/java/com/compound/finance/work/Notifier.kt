package com.compound.finance.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.compound.finance.MainActivity
import com.compound.finance.R
import com.compound.finance.data.Repository
import com.compound.finance.data.Settings
import com.compound.finance.data.TxEntity

object Notifier {
    private const val CH_TX = "transactions"
    private const val CH_ALERTS = "alerts"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TX, "New transactions", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A short note each time a bank SMS is recorded"
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Budgets and due dates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Budget limits, EMI, card and bill reminders"
        })
    }

    private fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(context: Context, tab: String): PendingIntent =
        PendingIntent.getActivity(context, tab.hashCode(),
            Intent(context, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun post(context: Context, id: Int, channel: String, title: String, text: String, tab: String) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(context, tab))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }

    fun transaction(context: Context, tx: TxEntity, s: Settings) {
        val sign = if (tx.direction == "CREDIT") "+" else ""
        val where = tx.merchant ?: tx.bank ?: "your account"
        post(context, ("tx" + tx.id).hashCode(), CH_TX,
            "$sign${Repository.fmt(tx.amount, tx.currency)} · ${tx.category}",
            if (tx.direction == "CREDIT") "Received from $where" else "Paid at $where. Tap to change the category.",
            "spending")
    }

    fun budget(context: Context, category: String, spent: Double, limit: Double, s: Settings) {
        val pct = (spent / limit * 100).toInt()
        val over = spent > limit
        post(context, ("budget" + category).hashCode(), CH_ALERTS,
            if (over) "$category budget exceeded" else "$category budget $pct% used",
            "${Repository.fmt(spent, s.mainCurrency)} of ${Repository.fmt(limit, s.mainCurrency)} this month.",
            "budgets")
    }

    fun imported(context: Context, transactions: Int, review: Int) =
        post(context, "imported".hashCode(), CH_TX,
            "$transactions new transaction${if (transactions > 1) "s" else ""} recorded",
            "From your latest SMS backup." + if (review > 0) " $review message${if (review > 1) "s" else ""} need a look." else "",
            "spending")

    fun due(context: Context, title: String, text: String, key: String) =
        post(context, ("due$key").hashCode(), CH_ALERTS, title, text, "dues")
}
