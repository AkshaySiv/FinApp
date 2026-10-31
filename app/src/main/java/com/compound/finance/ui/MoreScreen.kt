@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compound.finance.core.Categories
import com.compound.finance.core.Direction
import com.compound.finance.core.SmsParser
import com.compound.finance.data.SmsEntity
import java.time.Instant
import java.time.ZoneId

// ------------------------------------------------------------------ review
@Composable
fun ReviewScreen(vm: MainViewModel) {
    val items by vm.review.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<SmsEntity?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (items.isEmpty()) {
            EmptyState("Nothing to review", "Bank messages that couldn't be read automatically show up here.")
        } else {
            Muted("These came from your authorised senders but couldn't be read with confidence. Add the ones that are real transactions; dismiss the rest.")
            items.forEach { m ->
                Spacer(Modifier.height(12.dp))
                Panel {
                    Muted("${m.sender} · ${fmtDay(Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault()).toLocalDate())}")
                    Spacer(Modifier.height(4.dp))
                    Text(m.body, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { adding = m }) { Text("Add as transaction") }
                        TextButton(onClick = { vm.dismiss(m) }) { Text("Dismiss") }
                        TextButton(onClick = { vm.dismissSender(m.sender) }) { Text("Dismiss all from sender") }
                    }
                }
            }
        }
    }
    adding?.let { m -> ReviewAddDialog(vm, m) { adding = null } }
}

@Composable
private fun ReviewAddDialog(vm: MainViewModel, m: SmsEntity, onDone: () -> Unit) {
    val guess = remember(m.key) { SmsParser.findAmounts(m.body).firstOrNull { !it.isBalance } }
    val s by vm.settings.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf(guess?.value?.let { "%.2f".format(it) } ?: "") }
    var ccy by remember { mutableStateOf(guess?.currency ?: s.mainCurrency) }
    var dir by remember { mutableStateOf(if (m.body.contains("credit", true)) Direction.CREDIT else Direction.DEBIT) }
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Categories.UNCATEGORISED) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted(m.body, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = dir == Direction.DEBIT, onClick = { dir = Direction.DEBIT }, label = { Text("Money out") })
                    FilterChip(selected = dir == Direction.CREDIT, onClick = { dir = Direction.CREDIT }, label = { Text("Money in") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(amount, { amount = it }, "Amount", Modifier.weight(1f))
                    InputField(ccy, { ccy = it.uppercase().take(4) }, "Currency", Modifier.width(100.dp))
                }
                InputField(merchant, { merchant = it }, "Merchant")
                ChoiceField("Category", category, s.allCategories, { category = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.toAmount() ?: return@TextButton vm.say("Enter an amount")
                vm.createFromReview(m, a, ccy, dir, merchant, category); onDone()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ settings
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val reviewCount by vm.reviewCount.collectAsStateWithLifecycle()
    val txCount by vm.txCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var senders by remember(s.senders) { mutableStateOf(s.senders.joinToString("\n")) }
    var keywords by remember(s.keywords) { mutableStateOf(s.keywords.joinToString(", ")) }
    var url by remember(s.compoundUrl) { mutableStateOf(s.compoundUrl) }
    var user by remember(s.compoundUser) { mutableStateOf(s.compoundUser) }
    var pass by remember(s.compoundPassword) { mutableStateOf(s.compoundPassword) }
    var main by remember(s.mainCurrency) { mutableStateOf(s.mainCurrency) }
    var second by remember(s.secondCurrency) { mutableStateOf(s.secondCurrency) }
    var rates by remember(s.rates) { mutableStateOf(s.rates.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" }) }
    var confirmWipe by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    val csvPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importCsv(uri) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        if (reviewCount > 0) {
            Spacer(Modifier.height(16.dp))
            Panel(onClick = { vm.tab.value = "review" }) { Text("$reviewCount message${if (reviewCount > 1) "s" else ""} to review") }
        }

        SectionTitle("Which messages to read")
        Panel {
            Text("Authorised senders", style = MaterialTheme.typography.titleSmall)
            Muted("One per line. Use the sender name exactly as your messages app shows it (e.g. ADCBAlert, EmiratesNBD), or a phone number. Every other message is skipped and never stored. Messages you share or paste yourself are always read.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(senders, { senders = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Senders") })
            SwitchRow("Only read these senders", "Turn off to also read other senders that look like UAE banks", s.onlyListedSenders) { v ->
                vm.updateSettings { it.copy(onlyListedSenders = v) }
            }
            InputField(keywords, { keywords = it }, "Must contain one of these words (optional)")
            Muted("Comma-separated, e.g. debited, credited, purchase, transferred. Leave empty to read every message from your senders.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.updateSettings { it.copy(
                    senders = senders.split("\n", ",").map { x -> x.trim() }.filter { x -> x.isNotEmpty() }.distinct(),
                    keywords = keywords.split(",", "\n").map { x -> x.trim() }.filter { x -> x.isNotEmpty() }) }
                vm.say("Message filter saved")
            }) { Text("Save filter") }
        }

        if (com.compound.finance.BuildConfig.SMS_ACCESS) {
            SectionTitle("Read past messages")
            Panel {
                Muted("Goes through your inbox for the period you pick. Messages already recorded are skipped. $txCount transactions recorded so far.")
                Spacer(Modifier.height(8.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1L, 3L, 6L, 12L).forEach { m -> OutlinedButton(onClick = { vm.importInbox(m) }) { Text(if (m == 1L) "1 month" else "$m months") } }
                }
            }
        } else {
            SectionTitle("Getting your bank messages in")
            ImportPanel(vm, compact = true)
            Spacer(Modifier.height(4.dp))
            Muted("$txCount transactions recorded so far.")
        }

        SectionTitle("Categories")
        Panel {
            Muted("Built in: ${com.compound.finance.core.Categories.spending.dropLast(1).joinToString(", ")}.")
            if (s.customCategories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Yours", style = MaterialTheme.typography.titleSmall)
                s.customCategories.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c, Modifier.weight(1f))
                        TextButton(onClick = { vm.removeCategory(c) }) { Text("Remove") }
                    }
                }
                Muted("Removing a category keeps its past transactions as they are.")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(newCategory, { newCategory = it }, "New category, e.g. Kids", Modifier.weight(1f))
                Button(onClick = { vm.addCategory(newCategory); newCategory = "" }, enabled = newCategory.isNotBlank()) { Text("Add") }
            }
        }

        SectionTitle("Currencies")
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(main, { main = it.uppercase().take(4) }, "Main currency", Modifier.weight(1f))
                InputField(second, { second = it.uppercase().take(4) }, "Also show in", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(rates, { rates = it }, label = { Text("Value of 1 unit in ${s.mainCurrency}") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
            Muted("One per line, e.g. \"INR 0.0417\" means 1 rupee = ${s.mainCurrency} 0.0417. Used to add up foreign-currency spending and show the second currency.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val parsed = rates.lines().mapNotNull { l ->
                    val parts = l.trim().split(Regex("[\\s=:]+"))
                    if (parts.size == 2) parts[1].toDoubleOrNull()?.let { parts[0].uppercase() to it } else null
                }.toMap()
                vm.updateSettings { it.copy(mainCurrency = main.ifBlank { "AED" }, secondCurrency = second, rates = parsed) }
                vm.say("Currencies saved")
            }) { Text("Save currencies") }
        }

        SectionTitle("Notifications")
        Panel {
            SwitchRow("Each new transaction", "A quiet note with the amount and category", s.notifyEachTransaction) { v -> vm.updateSettings { it.copy(notifyEachTransaction = v) } }
            SwitchRow("Budget alerts", "At 80% and 100% of a category budget", s.notifyBudgets) { v -> vm.updateSettings { it.copy(notifyBudgets = v) } }
            SwitchRow("Due date reminders", "EMIs, card payments and bills, up to 3 days ahead", s.notifyDues) { v -> vm.updateSettings { it.copy(notifyDues = v) } }
        }

        SectionTitle("Privacy")
        Panel {
            SwitchRow("Lock with fingerprint or PIN", "Asked each time you open the app", s.appLock) { v -> vm.updateSettings { it.copy(appLock = v) } }
            Muted("Everything is stored only on this phone. Nothing is sent anywhere unless you set up the Compound server below.")
        }

        SectionTitle("Compound server (optional)")
        Panel {
            Muted("Connect to the Compound app you run, to see your net worth here and send it your income and spending. Your phone must be able to reach it, e.g. on home Wi-Fi or a VPN.")
            Spacer(Modifier.height(8.dp))
            InputField(url, { url = it.trim() }, "Address, e.g. http://192.168.1.20:8080")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(user, { user = it }, "Username", Modifier.weight(1f))
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    vm.updateSettings { it.copy(compoundUrl = url, compoundUser = user, compoundPassword = pass) }
                    vm.syncNow()
                }, enabled = !busy) { Text("Save and sync") }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        SectionTitle("Your data")
        Panel {
            Text("Import a bank statement", style = MaterialTheme.typography.titleSmall)
            Muted("For older months or accounts that don't text you: export a statement as CSV from your bank's website or app. Columns are found by name (Date, Description, Amount or Debit/Credit). Importing the same file twice adds nothing.")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*")) },
                enabled = !busy) { Text("Import CSV statement") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.exportCsv(context) }) { Text("Export CSV") }
                TextButton(onClick = { confirmWipe = true }) { Text("Delete all data", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Muted("Compound ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""}")
    }
    if (confirmWipe) ConfirmDialog("Delete all data?",
        "Every transaction, budget, loan, card and bill in the app is deleted. Your SMS stay on the phone, so you can read them again later.",
        "Delete everything", { vm.clearAll() }, { confirmWipe = false })
}
