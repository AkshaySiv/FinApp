@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class Dest(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Dest("home", "Home", Icons.Outlined.Home),
    Dest("spending", "Spending", Icons.Outlined.Receipt),
    Dest("budgets", "Budgets", Icons.Outlined.PieChart),
    Dest("dues", "Dues", Icons.Outlined.CalendarMonth),
    Dest("more", "Settings", Icons.Outlined.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val reviewCount by vm.reviewCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }
    BackHandler(enabled = tab != "home") { vm.tab.value = if (tab == "review") "more" else "home" }

    val title = when (tab) {
        "home" -> "Compound"; "spending" -> "Spending"; "budgets" -> "Budgets"; "dues" -> "Dues"
        "review" -> "Review messages"; else -> "Settings"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (tab == "review") TextButton(onClick = { vm.tab.value = "more" }) { Text("Back") }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEach { d ->
                    val selected = tab == d.key || (d.key == "more" && tab == "review")
                    NavigationBarItem(
                        selected = selected,
                        onClick = { vm.tab.value = d.key },
                        icon = {
                            if (d.key == "more" && reviewCount > 0) BadgedBox(badge = { Badge { Text("$reviewCount") } }) { Icon(d.icon, null) }
                            else Icon(d.icon, null)
                        },
                        label = { Text(d.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                "home" -> HomeScreen(vm)
                "spending" -> SpendingScreen(vm)
                "budgets" -> BudgetsScreen(vm)
                "dues" -> DuesScreen(vm)
                "review" -> ReviewScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
