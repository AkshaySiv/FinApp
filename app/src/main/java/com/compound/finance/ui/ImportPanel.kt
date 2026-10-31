@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BACKUP_APP = "com.riteshsahu.SMSBackupRestore"

/**
 * How bank SMS get into the phone edition, which can't read the inbox itself:
 * an SMS Backup & Restore folder checked automatically, a one-off backup file, or pasted text.
 */
@Composable
fun ImportPanel(vm: MainViewModel, compact: Boolean = false) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pasting by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(!compact && s.backupFolder.isBlank()) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "chosen folder"
            vm.setBackupFolder(uri, name)
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackupFile(uri)
    }

    Panel {
        Text("Automatic: from SMS Backup & Restore", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (s.backupFolder.isNotBlank()) {
            val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
            fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt)
            Text("Watching \"${s.backupFolderName}\"", style = MaterialTheme.typography.bodyMedium)
            Muted(buildString {
                append("Checked every few hours")
                if (s.lastBackupCheck > 0) append(", last at ${t(s.lastBackupCheck)}")
                if (s.backupImportedAt > 0) append(". Newest backup read: ${t(s.backupImportedAt)}")
                append(".")
            })
            Spacer(Modifier.height(8.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.syncBackupNow() }) { Text("Check now") }
                TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Hide steps" else "Setup steps") }
                TextButton(onClick = { vm.clearBackupFolder() }) { Text("Stop") }
            }
        } else {
            Text("Compound can't read SMS directly when installed from your phone, because Play Protect blocks that. " +
                "The free SMS Backup & Restore app (from the Play Store) can, and saves a copy of your messages to a folder. " +
                "Compound reads new bank messages from that folder every few hours.",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (showSteps) {
            Spacer(Modifier.height(10.dp))
            listOf(
                "Install SMS Backup & Restore from the Play Store and open it.",
                "Tap Set up a backup. Turn on Messages only (not Calls), choose \"Your phone\" as the location, and pick or create a folder, e.g. SMSBackup.",
                "Under scheduled backups, choose every hour or every few hours.",
                "Run one backup now, then come back here and choose that same folder.",
            ).forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("${i + 1}.", Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val open = context.packageManager.getLaunchIntentForPackage(BACKUP_APP)
                    try {
                        context.startActivity(open ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$BACKUP_APP")))
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$BACKUP_APP")))
                    }
                }) { Text(if (context.packageManager.getLaunchIntentForPackage(BACKUP_APP) != null) "Open backup app" else "Get backup app") }
                if (s.backupFolder.isBlank()) Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
            }
        }
        if (s.backupFolder.isBlank() && !showSteps) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }) { Text("Choose backup folder") }
                TextButton(onClick = { showSteps = true }) { Text("How to set up") }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Panel {
        Text("Or add messages yourself", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Muted("In your Messages app, long-press a bank SMS, tap Share, and choose Compound. Or copy several and paste them here, with a blank line between each.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pasting = true }) { Text("Paste messages") }
            OutlinedButton(onClick = { filePicker.launch(arrayOf("text/xml", "application/xml", "*/*")) }) { Text("Import backup file") }
        }
    }

    if (pasting) PasteDialog(vm) { pasting = false }
}

@Composable
fun PasteDialog(vm: MainViewModel, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Paste bank messages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted("Paste one or more SMS. Put a blank line between messages. The date is read from each message; if it has none, today is used.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    placeholder = { Text("AED10000.00 transferred via ADCB…") })
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.importText(text); onDone() }, enabled = text.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
