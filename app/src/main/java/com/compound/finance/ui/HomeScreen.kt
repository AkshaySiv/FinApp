@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compound.finance.data.Repository
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

fun hasSmsPermission(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val txs by vm.recentTx.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val dues by vm.dues.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val reviewCount by vm.reviewCount.collectAsStateWithLifecycle()
    val netWorth by vm.netWorth.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val p = LocalPalette.current

    var granted by remember { mutableStateOf(hasSmsPermission(context)) }
    // Re-check when coming back from Android's App info screen.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = hasSmsPermission(context)
        onPauseOrDispose { }
    }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[Manifest.permission.READ_SMS] == true
        askedOnce = true
        if (granted && !s.onboarded) vm.importInbox(6)
    }
    fun request() {
        val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        launcher.launch(perms.toTypedArray())
    }

    val ym = YearMonth.now()
    val month = txs.filter { YearMonth.from(vm.repo.dateOf(it.timestamp)) == ym }
    val spent = month.filter { vm.repo.isSpending(it) }.sumOf { s.toMain(it.amount, it.currency) }
    val income = month.filter { vm.repo.isIncome(it) }.sumOf { s.toMain(it.amount, it.currency) }
    val budgetTotal = budgets.sumOf { it.monthlyLimit }
    val byCat = month.filter { vm.repo.isSpending(it) }.groupBy { it.category }
        .mapValues { (_, l) -> l.sumOf { s.toMain(it.amount, it.currency) } }.entries.sortedByDescending { it.value }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        if (!com.compound.finance.BuildConfig.SMS_ACCESS) {
            // Phone edition: messages come from a backup folder, Share or paste.
            if (s.backupFolder.isBlank() && txs.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Bring in your bank messages", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                ImportPanel(vm)
            }
        } else if (!granted) {
            Spacer(Modifier.height(16.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sms, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Let Compound read your bank SMS", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text("Only messages from the senders you authorise in Settings are read. Everything stays on this phone.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Muted("Authorised now: ${s.senders.joinToString(", ")}" + if (s.keywords.isNotEmpty()) "; must contain: ${s.keywords.joinToString(", ")}" else "")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { request() }) { Text("Allow SMS access") }
                    TextButton(onClick = { vm.tab.value = "more" }) { Text("Edit senders") }
                }
                if (askedOnce) {
                    Spacer(Modifier.height(12.dp))
                    Muted("No permission prompt? Android blocks SMS access for apps installed outside the Play Store until you allow it: open App info, tap ⋮ in the top corner, choose \"Allow restricted settings\", then try again.")
                    TextButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text("Open App info") }
                }
            }
        } else if (!s.onboarded) {
            Spacer(Modifier.height(16.dp))
            Panel {
                Text("Read your past bank messages", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Pick how far back to go. New messages are recorded automatically from now on.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3L, 6L, 12L).forEach { m -> OutlinedButton(onClick = { vm.importInbox(m) }) { Text("$m months") } }
                }
            }
        }

        // ---- this month
        Spacer(Modifier.height(20.dp))
        Muted("Spent in ${ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(money(spent, s.mainCurrency, decimals = false), style = MaterialTheme.typography.displaySmall)
        }
        s.toSecond(spent)?.let { Muted(money(it, s.secondCurrency, decimals = false)) }
        if (budgetTotal > 0) {
            Spacer(Modifier.height(10.dp))
            val today = LocalDate.now()
            val elapsed = today.dayOfMonth.toDouble() / today.lengthOfMonth()
            val used = spent / budgetTotal
            Bar(used, if (used > 1) p.neg else if (used > elapsed + 0.1) p.warn else MaterialTheme.colorScheme.secondary, height = 10)
            Spacer(Modifier.height(4.dp))
            Muted("${pct(used)} of ${money(budgetTotal, s.mainCurrency, decimals = false)} budget · ${pct(elapsed)} of the month gone · ${money((budgetTotal - spent).coerceAtLeast(0.0), s.mainCurrency, decimals = false)} left")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column { Muted("Income"); Text(money(income, s.mainCurrency, decimals = false), style = MaterialTheme.typography.titleMedium) }
            Column { Muted("Kept"); Text(money(income - spent, s.mainCurrency, decimals = false), style = MaterialTheme.typography.titleMedium,
                color = if (income - spent >= 0) p.pos else p.neg) }
            if (income > 0) Column { Muted("Savings rate"); Text(pct((income - spent) / income), style = MaterialTheme.typography.titleMedium) }
        }

        if (reviewCount > 0) {
            Spacer(Modifier.height(16.dp))
            Panel(onClick = { vm.tab.value = "review" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = p.warn)
                    Spacer(Modifier.width(10.dp))
                    Text("$reviewCount bank message${if (reviewCount > 1) "s" else ""} couldn't be read. Tap to review.")
                }
            }
        }

        netWorth?.let { nw ->
            SectionTitle("Net worth", action = { TextButton(onClick = { vm.refreshNetWorth() }) { Text("Refresh") } })
            Panel {
                Text(money(nw.netWorth, nw.currency, decimals = false), style = MaterialTheme.typography.headlineSmall)
                nw.others.forEach { (c, v) -> Muted(money(v, c, decimals = false)) }
                Spacer(Modifier.height(6.dp))
                Muted("Assets ${money(nw.assets, nw.currency, decimals = false)} · Liabilities ${money(nw.liabilities, nw.currency, decimals = false)} · from Compound, ${nw.asOf}")
            }
        }

        if (insights.isNotEmpty()) {
            SectionTitle("Worth knowing")
            Panel {
                insights.forEachIndexed { i, ins ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                    Row {
                        Icon(if (ins.warning) Icons.Outlined.Warning else Icons.Outlined.Info, null,
                            tint = if (ins.warning) p.warn else p.muted, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(10.dp))
                        Column { Text(ins.title, style = MaterialTheme.typography.bodyLarge); Muted(ins.detail) }
                    }
                }
            }
        }

        val soon = dues.filter { !it.date.isAfter(LocalDate.now().plusDays(14)) }
        if (soon.isNotEmpty()) {
            SectionTitle("Due in the next two weeks", action = { TextButton(onClick = { vm.tab.value = "dues" }) { Text("All dues") } })
            Panel { soon.forEachIndexed { i, d -> if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline); DueRow(d) } }
        }

        if (byCat.isNotEmpty()) {
            SectionTitle("Where it went", action = { TextButton(onClick = { vm.tab.value = "spending" }) { Text("All spending") } })
            Panel {
                val top = byCat.first().value
                byCat.take(8).forEach { (cat, v) ->
                    val limit = budgets.firstOrNull { it.category == cat }?.monthlyLimit
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(cat, Modifier.width(120.dp), maxLines = 1)
                        Bar(if (limit != null) v / limit else v / top, if (limit != null && v > limit) p.neg else categoryColor(cat), Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(compact(v), Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if ((granted || s.backupFolder.isNotBlank()) && s.onboarded && txs.isEmpty()) {
            EmptyState("No transactions yet",
                "Nothing matched your authorised senders. Check the sender names in Settings match exactly what your phone shows.",
                "Open settings") { vm.tab.value = "more" }
        }
    }
}

@Composable
fun DueRow(d: Repository.Due) {
    val p = LocalPalette.current
    val days = ChronoUnit.DAYS.between(LocalDate.now(), d.date)
    val whenText = when { days < 0 -> "Overdue ${-days}d"; days == 0L -> "Today"; days == 1L -> "Tomorrow"; else -> fmtDay(d.date) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.title, maxLines = 1)
            Muted(d.detail)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(d.amount, d.currency))
            Text(whenText, style = MaterialTheme.typography.bodySmall, color = if (days <= 1) p.neg else p.muted)
        }
    }
}
