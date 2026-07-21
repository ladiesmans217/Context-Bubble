package com.contextbubble.app.ui

import android.Manifest
import android.app.PendingIntent
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import com.contextbubble.app.BuildConfig
import com.contextbubble.app.cloud.AccountState
import com.contextbubble.app.cloud.CloudSyncOutcome
import com.contextbubble.app.cloud.CloudDashboard
import com.contextbubble.app.cloud.CloudDeletePreview
import com.contextbubble.app.cloud.McpGrant
import com.contextbubble.app.cloud.McpChange
import com.contextbubble.app.cloud.MemoryConflict
import com.contextbubble.app.accessibility.AccessibilityBridge
import com.contextbubble.app.accessibility.AutomationCommand
import com.contextbubble.app.appContainer
import com.contextbubble.app.data.AppSettings
import com.contextbubble.app.data.LocalCapture
import com.contextbubble.app.data.QuickFillItem
import com.contextbubble.app.data.VaultMemory
import com.contextbubble.app.domain.RetentionPolicy
import com.contextbubble.app.overlay.BubbleService
import com.contextbubble.app.overlay.BubbleActionBridge
import com.contextbubble.app.overlay.OverlayShareActions
import com.contextbubble.app.ui.theme.Cobalt
import com.contextbubble.app.ui.theme.ContextBubbleTheme
import com.contextbubble.app.ui.theme.Coral
import com.contextbubble.app.ui.theme.Cyan
import com.contextbubble.app.ui.theme.Ink
import com.contextbubble.app.ui.theme.Mint
import com.contextbubble.app.ui.theme.Mist
import com.contextbubble.app.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.io.File

class MainActivity : ComponentActivity() {
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey++ }
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey++ }
    private var pendingCalendarResolution: CompletableDeferred<Intent?>? = null
    private val requestCalendarAuthorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingCalendarResolution?.complete(if (result.resultCode == RESULT_OK) result.data else null)
        pendingCalendarResolution = null
    }
    private var refreshKey by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            ContextBubbleTheme {
                ContextBubbleRoot(
                    refreshKey = refreshKey,
                    requestNotifications = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    requestMicrophone = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
        // Keep the persisted control and the real foreground service in sync.
        // Android stops the service during an APK replacement and some OEM
        // recovery paths even though DataStore still says the bubble is active.
        lifecycleScope.launch {
            val settings = applicationContext.appContainer.settings.settings.first()
            val notificationsAllowed = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (settings.bubbleEnabled && Settings.canDrawOverlays(this@MainActivity) && notificationsAllowed) {
                BubbleService.start(this@MainActivity)
            }
        }
    }

    suspend fun launchCalendarAuthorization(pendingIntent: PendingIntent): Intent? {
        pendingCalendarResolution?.cancel()
        val deferred = CompletableDeferred<Intent?>()
        pendingCalendarResolution = deferred
        requestCalendarAuthorization.launch(IntentSenderRequest.Builder(pendingIntent).build())
        return deferred.await()
    }
}

private enum class RootSection(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    VAULT("Vault", Icons.Rounded.Memory),
    FILL("Quick Fill", Icons.Rounded.Keyboard),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
private fun ContextBubbleRoot(
    refreshKey: Int,
    requestNotifications: () -> Unit,
    requestMicrophone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(RootSection.HOME) }
    val settingsFlow = remember(context) {
        context.appContainer.settings.settings.map<AppSettings, AppSettings?> { it }
    }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val activeSettings = settings ?: AppSettings()
    val pendingAction by BubbleActionBridge.pending.collectAsStateWithLifecycle()
    val accessState by AccessibilityBridge.state.collectAsStateWithLifecycle()
    val permissions = permissionSnapshot(context, accessState.connected)
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(settings?.onboardingComplete, settings?.notificationPromptShown, permissions.notifications) {
        val loadedSettings = settings ?: return@LaunchedEffect
        if (!loadedSettings.onboardingComplete &&
            !loadedSettings.notificationPromptShown &&
            !permissions.notifications
        ) {
            // Let the app window draw before Android places its translucent
            // permission sheet above it, so users retain clear visual context.
            repeat(2) { withFrameNanos { } }
            context.appContainer.settings.markNotificationPromptShown()
            requestNotifications()
        }
    }

    LaunchedEffect(section) {
        val window = (context as? ComponentActivity)?.window ?: return@LaunchedEffect
        if (section == RootSection.VAULT || section == RootSection.FILL) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(refreshKey) { /* forces permission-backed composables to re-read */ }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!imeVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    RootSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == section,
                            onClick = { section = item },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val screenModifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
        when (section) {
            RootSection.HOME -> HomeScreen(
                modifier = screenModifier,
                settings = activeSettings,
                requestNotifications = requestNotifications,
                requestMicrophone = requestMicrophone,
                onOpenSettings = { section = RootSection.SETTINGS },
            )
            RootSection.VAULT -> VaultScreen(screenModifier)
            RootSection.FILL -> QuickFillScreen(screenModifier)
            RootSection.SETTINGS -> SettingsScreen(screenModifier, activeSettings)
        }
    }
    pendingAction?.let { action ->
        BubbleActionDialog(action) { BubbleActionBridge.consume(action.id) }
    }
    if (pendingAction == null && settings?.onboardingComplete == false) {
        FirstRunSetupDialog(
            permissions = permissions,
            onRequestNotifications = requestNotifications,
            onOpenOverlaySettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
            onOpenAccessibilitySettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onComplete = {
                scope.launch { context.appContainer.settings.setOnboardingComplete(true) }
            },
        )
    }
}

@Composable
private fun FirstRunSetupDialog(
    permissions: PermissionSnapshot,
    onRequestNotifications: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onComplete: () -> Unit,
) {
    val requiredComplete = permissions.notifications && permissions.overlay && permissions.accessibility
    AlertDialog(
        onDismissRequest = { /* Use the explicit Not now action so the choice is intentional. */ },
        icon = { OrbitMark(active = false, blocked = false, modifier = Modifier.size(52.dp)) },
        title = { Text("Set up Context Bubble") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Complete these Android controls once. You can repair or review every permission later from Assistant health.",
                    color = Slate,
                )
                SetupPermissionRow(
                    title = "Notifications",
                    body = "Shows the persistent bubble control and reminder status.",
                    icon = Icons.Rounded.Notifications,
                    complete = permissions.notifications,
                    actionLabel = "Allow",
                    onAction = onRequestNotifications,
                )
                SetupPermissionRow(
                    title = "Display over apps",
                    body = "Lets the edge bubble appear above supported apps. Android opens a dedicated settings screen.",
                    icon = Icons.Rounded.Visibility,
                    complete = permissions.overlay,
                    actionLabel = "Open settings",
                    onAction = onOpenOverlaySettings,
                )
                SetupPermissionRow(
                    title = "Screen access",
                    body = "Detects the focused text field and reads bounded screen context only after you invoke the assistant. Passwords and protected apps stay blocked.",
                    icon = Icons.Rounded.HealthAndSafety,
                    complete = permissions.accessibility,
                    actionLabel = "Open settings",
                    onAction = onOpenAccessibilitySettings,
                )
                Text(
                    "Microphone access is requested only when you first use dictation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate,
                )
            }
        },
        confirmButton = {
            Button(enabled = requiredComplete, onClick = onComplete) { Text("Finish") }
        },
        dismissButton = {
            OutlinedButton(onClick = onComplete) { Text("Not now") }
        },
    )
}

@Composable
private fun SetupPermissionRow(
    title: String,
    body: String,
    icon: ImageVector,
    complete: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (complete) Mint.copy(alpha = 0.18f) else Mist),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (complete) Icons.Rounded.Check else icon,
                    contentDescription = null,
                    tint = if (complete) Color(0xFF17865F) else Cobalt,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                title,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            if (complete) {
                Text("Allowed", color = Color(0xFF17865F), fontWeight = FontWeight.SemiBold)
            }
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Slate)
        if (!complete) {
            FilledTonalButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    settings: AppSettings,
    requestNotifications: () -> Unit,
    requestMicrophone: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessState by AccessibilityBridge.state.collectAsStateWithLifecycle()
    val permissions = permissionSnapshot(context, accessState.connected)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitMark(
                    active = settings.bubbleEnabled,
                    blocked = accessState.blocked,
                    modifier = Modifier.size(84.dp),
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text("CONTEXT BUBBLE", style = MaterialTheme.typography.labelSmall, color = Cobalt)
                    Text("Present when you need it.", style = MaterialTheme.typography.displaySmall)
                    Text("Silent when you don’t.", style = MaterialTheme.typography.bodyLarge, color = Slate)
                }
            }
        }

        item {
            BubbleControlCard(
                enabled = settings.bubbleEnabled,
                ready = permissions.overlay && permissions.notifications,
                onToggle = { enable ->
                    if (enable && !permissions.overlay) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    } else if (enable && !permissions.notifications) {
                        requestNotifications()
                    } else {
                        scope.launch { context.appContainer.settings.setBubbleEnabled(enable) }
                        if (enable) BubbleService.start(context) else BubbleService.stop(context)
                    }
                },
            )
        }

        item { SectionLabel("ASSISTANT HEALTH", "Tap any incomplete item to repair it") }
        item {
            HealthRail(
                items = listOf(
                    HealthItem("Display over apps", permissions.overlay, Icons.Rounded.Visibility) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    },
                    HealthItem("Screen access", permissions.accessibility, Icons.Rounded.HealthAndSafety) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    HealthItem("Notifications", permissions.notifications, Icons.Rounded.Notifications, requestNotifications),
                    HealthItem("Microphone", permissions.microphone, Icons.Rounded.Mic, requestMicrophone),
                    HealthItem(
                        if (permissions.batteryUnrestricted) "Battery unrestricted" else "Battery optimization active",
                        permissions.batteryUnrestricted,
                        Icons.Rounded.Bolt,
                    ) {
                        openBatteryOptimizationSettings(context)
                    },
                ),
            )
        }

        if (BuildConfig.LAB_AUTOMATION) {
            item {
                NoticeCard(
                    title = "Experimental automation build",
                    body = "Deterministic UI actuators may be enabled for allowlisted apps. Financial and authentication screens remain blocked.",
                    accent = Coral,
                )
            }
        }

        item {
            NoticeCard(
                title = "Privacy is a hard boundary",
                body = "The microphone and AI stay off while idle. Screen context is read only after your bubble action, and memory is saved only after approval.",
                accent = Cyan,
                icon = Icons.Rounded.Lock,
            )
        }

        item {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Tune, null)
                Text("Review controls", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun BubbleControlCard(enabled: Boolean, ready: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "Bubble is active" else "Bubble is paused", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (enabled) "Ready · stays visible over allowed apps" else if (ready) "Ready to appear over your apps" else "Complete the required controls below",
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

private data class HealthItem(val title: String, val complete: Boolean, val icon: ImageVector, val repair: () -> Unit)

@Composable
private fun HealthRail(items: List<HealthItem>) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !item.complete, onClick = item.repair).padding(vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(if (item.complete) Mint.copy(alpha = 0.18f) else Mist), contentAlignment = Alignment.Center) {
                        Icon(item.icon, null, tint = if (item.complete) Color(0xFF17865F) else Slate, modifier = Modifier.size(20.dp))
                    }
                    Text(item.title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Icon(if (item.complete) Icons.Rounded.Check else Icons.Rounded.Add, null, tint = if (item.complete) Mint else Cobalt)
                }
                if (index != items.lastIndex) HorizontalDivider(color = Mist)
            }
        }
    }
}

@Composable
private fun VaultScreen(modifier: Modifier) {
    val context = LocalContext.current
    val memories by context.appContainer.vault.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val captures by context.appContainer.vault.captures.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedMemory by remember { mutableStateOf<VaultMemory?>(null) }
    var selectedCapture by remember { mutableStateOf<LocalCapture?>(null) }
    ScreenScaffold(modifier, "Vault", "Only approved memories live here.") {
        if (memories.isEmpty() && captures.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.Memory, "Nothing remembered yet", "Use Remember from the bubble. You will review every memory before it is saved.")
            }
        } else {
            items(memories, key = { it.id }) { item ->
                DataCard(
                    if (item.hasConflict) "SYNC CONFLICT · ${item.type.uppercase()}" else item.type.uppercase(),
                    item.summary,
                    when {
                        item.hasConflict -> "Phone and cloud changed separately · tap to review"
                        item.scope.name == "LOCAL_ONLY" -> "On this phone · tap to view and copy"
                        else -> "Assistant memory · tap to view and copy"
                    },
                    onClick = { selectedMemory = item },
                )
            }
            items(captures, key = { it.id }) { item ->
                DataCard(
                    label = item.kind.replace('_', ' '),
                    title = when (item.state) {
                        "PENDING_TRANSCRIPTION" -> "Waiting for a connection"
                        "TRANSCRIBED" -> "Transcription completed"
                        else -> when (item.kind) {
                            "SCREENSHOT_PNG", "SCREENSHOT_JPEG" -> "Screenshot · tap to view"
                            "GENERATED_IMAGE_PNG" -> "Generated image · tap to view"
                            else -> "Encrypted capture · tap to inspect"
                        }
                    },
                    footnote = "${formatBytes(item.sizeBytes)} · ${DateFormat.getDateTimeInstance().format(Date(item.createdAtEpochMs))}",
                    onClick = { selectedCapture = item },
                )
            }
        }
    }

    selectedMemory?.let { memory ->
        MemoryDetailDialog(memory, onDismiss = { selectedMemory = null })
    }
    selectedCapture?.let { capture ->
        CaptureDetailDialog(capture, onDismiss = { selectedCapture = null })
    }
}

@Composable
private fun QuickFillScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by context.appContainer.vault.quickFill.collectAsStateWithLifecycle(initialValue = emptyList())
    var showAdd by remember { mutableStateOf(false) }

    ScreenScaffold(
        modifier,
        "Quick Fill",
        "Private details, ready when forms repeat.",
        action = {
            FilledTonalButton(onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, null)
                Text("Add", Modifier.padding(start = 6.dp))
            }
        },
    ) {
        if (items.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.Keyboard, "Save your first detail", "Names, email, phone, address, and reusable text stay encrypted on this device by default.")
            }
        } else {
            items(items, key = QuickFillItem::id) { item ->
                DataCard(item.kind.uppercase(), item.label, if (item.allowAi) "AI allowed when selected" else "Local only", value = item.value)
            }
        }
    }

    if (showAdd) {
        AddQuickFillDialog(
            onDismiss = { showAdd = false },
            onSave = { label, kind, value, allowAi ->
                scope.launch { context.appContainer.vault.saveQuickFill(label, kind, value, allowAi) }
                showAdd = false
            },
        )
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showApps by remember { mutableStateOf(false) }
    var appQuery by remember { mutableStateOf("") }
    var showLabAllowlist by remember { mutableStateOf(false) }
    var labAllowedPackages by remember { mutableStateOf(context.appContainer.automation.allowedPackages(context)) }
    var labCommandText by remember { mutableStateOf("") }
    var labPackageName by remember { mutableStateOf("") }
    var pendingLabCommand by remember { mutableStateOf<AutomationCommand?>(null) }
    var labExecutionMessage by remember { mutableStateOf<String?>(null) }
    var backendUrl by remember(settings.customBackendUrl) { mutableStateOf(settings.customBackendUrl.orEmpty()) }
    var cloudMessage by remember { mutableStateOf<String?>(null) }
    var mcpRefresh by remember { mutableStateOf(0) }
    var calendarRefresh by remember { mutableStateOf(0) }
    var cloudRefresh by remember { mutableStateOf(0) }
    var deletePreview by remember { mutableStateOf<CloudDeletePreview?>(null) }
    val accountState by context.appContainer.accounts.state.collectAsStateWithLifecycle()
    val mcpGrants by produceState<List<McpGrant>?>(null, accountState, mcpRefresh) {
        value = if (accountState is AccountState.SignedIn) {
            runCatching { context.appContainer.mcp.listGrants() }.getOrElse { emptyList() }
        } else null
    }
    val mcpChanges by produceState<List<McpChange>>(emptyList(), accountState, mcpRefresh) {
        value = if (accountState is AccountState.SignedIn) {
            runCatching { context.appContainer.mcp.recentChanges() }.getOrElse { emptyList() }
        } else emptyList()
    }
    val cloudDashboard by produceState<CloudDashboard?>(null, accountState, cloudRefresh) {
        value = if (accountState is AccountState.SignedIn) {
            runCatching { context.appContainer.cloudAccount.dashboard() }.getOrNull()
        } else null
    }
    val calendarConnected by produceState<Boolean?>(null, accountState, calendarRefresh) {
        value = if (accountState is AccountState.SignedIn) {
            runCatching { context.appContainer.calendar.connected() }.getOrDefault(false)
        } else false
    }
    val mcpConnectUrl by produceState<String?>(null, accountState) {
        value = runCatching { context.appContainer.cloudConfiguration.capabilities().mcpConnectUrl }.getOrNull()
    }
    val apps by produceState<List<LaunchableApp>?>(null, context) {
        value = withContext(Dispatchers.IO) { launchableApps(context) }
    }
    val visibleApps = remember(apps, appQuery) { filterLaunchableApps(apps.orEmpty(), appQuery) }
    val storageBytes by context.appContainer.vault.captureStorageBytes.collectAsStateWithLifecycle(initialValue = 0L)
    ScreenScaffold(modifier, "Controls", "Tune availability without weakening privacy.", state = listState) {
        item {
            SettingsCard("Start after reboot", "Restore the passive bubble after the phone restarts.", settings.startOnBoot) {
                scope.launch { context.appContainer.settings.setStartOnBoot(it) }
            }
        }
        item {
            SettingsCard(
                "Long press: transcription only",
                "Off uses long press to ask the assistant and hear its reply. On inserts only your transcript.",
                settings.longPressTranscriptionOnly,
            ) { scope.launch { context.appContainer.settings.setLongPressTranscriptionOnly(it) } }
        }
        item {
            SettingsCard(
                "Read text answers aloud",
                "Speak answers started from Ask about screen. Long-press conversations always speak.",
                settings.readTextAnswersAloud,
            ) { scope.launch { context.appContainer.settings.setReadTextAnswersAloud(it) } }
        }
        item {
            Text("RAW CAPTURE RETENTION", style = MaterialTheme.typography.labelSmall, color = Cobalt, modifier = Modifier.padding(top = 8.dp))
            RetentionPicker(settings.retention) { scope.launch { context.appContainer.settings.setRetention(it) } }
        }
        item {
            NoticeCard(
                title = "Private storage · ${formatBytes(storageBytes)}",
                body = when {
                    storageBytes >= 1_000_000_000L -> "Storage is above 1 GB. Review captures in Vault or shorten retention."
                    storageBytes >= 500_000_000L -> "Storage is above 500 MB. Consider reviewing old captures."
                    else -> "Encrypted screenshots, recordings, and generated images are counted here. Pinned items are never removed automatically."
                },
                accent = if (storageBytes >= 500_000_000L) Coral else Cyan,
            )
        }
        if (accountState is AccountState.SignedIn) {
            item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Connect ChatGPT", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "In ChatGPT, open Settings → Apps → Advanced settings, enable developer mode, create a connector, and paste the Context Bubble MCP URL. Review the read/change consent before approving it.",
                            color = Slate,
                        )
                        Text(
                            "ChatGPT calls this MCP when relevant. It does not copy data into native ChatGPT Memory or monitor the phone in the background.",
                            color = Slate,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            enabled = !mcpConnectUrl.isNullOrBlank(),
                            onClick = {
                                OverlayShareActions.copyText(context, mcpConnectUrl.orEmpty())
                                cloudMessage = "Context Bubble MCP URL copied."
                            },
                        ) { Text(if (mcpConnectUrl.isNullOrBlank()) "MCP not enabled" else "Copy MCP URL") }
                    }
                }
            }
            if (mcpGrants == null) {
                item { Text("Loading MCP connections…", color = Slate) }
            } else if (mcpGrants!!.isEmpty()) {
                item { Text("No ChatGPT or other MCP client has been authorized yet.", color = Slate) }
            } else {
                items(mcpGrants!!, key = { "mcp-${it.clientId}" }) { grant ->
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(grant.clientId, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    grant.revoked -> "Revoked"
                                    grant.lastAccessAt != null -> "Last access ${grant.lastAccessAt}"
                                    else -> "Authorized; not used yet"
                                },
                                color = if (grant.revoked) Coral else Slate,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!grant.revoked) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Allow confirmed writes")
                                        Text("Read access remains available when this is off.", color = Slate, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Switch(
                                        checked = grant.accessLevel == "READ_WRITE",
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                runCatching { context.appContainer.mcp.setAccess(grant.clientId, enabled) }
                                                    .onSuccess { mcpRefresh++ }
                                                    .onFailure { cloudMessage = it.message ?: "Could not update MCP access." }
                                            }
                                        },
                                    )
                                }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { context.appContainer.mcp.revoke(grant.clientId) }
                                            .onSuccess {
                                                cloudMessage = "MCP access revoked immediately."
                                                mcpRefresh++
                                            }
                                            .onFailure { cloudMessage = it.message ?: "Could not revoke MCP access." }
                                    }
                                }) { Text("Revoke access") }
                            }
                        }
                    }
                }
            }
            if (mcpChanges.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Recent MCP activity", style = MaterialTheme.typography.titleMedium)
                            mcpChanges.take(3).forEach { change ->
                                Text("${change.operation.replace('_', ' ')} · ${change.actor_client_id ?: "MCP client"}", color = Slate, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Google Calendar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (calendarConnected == true) "Connected with calendar.events only. Every event still shows an exact preview before creation."
                        else "Optional. Connect only when you want a confirmed local reminder copied to Google Calendar.",
                        color = Slate,
                    )
                    if (calendarConnected == true) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { context.appContainer.calendar.revoke() }
                                    .onSuccess {
                                        cloudMessage = "Google Calendar access revoked."
                                        calendarRefresh++
                                    }
                                    .onFailure { cloudMessage = it.message ?: "Could not revoke Calendar." }
                            }
                        }) { Text("Disconnect Calendar") }
                    } else {
                        Button(
                            enabled = accountState is AccountState.SignedIn,
                            onClick = {
                                val activity = context as? MainActivity
                                if (activity == null) cloudMessage = "Calendar authorization is unavailable from this screen."
                                else scope.launch {
                                    cloudMessage = "Opening Google Calendar authorization…"
                                    runCatching {
                                        context.appContainer.calendarAuthorization.connect(activity, activity::launchCalendarAuthorization)
                                    }.onSuccess { connected ->
                                        cloudMessage = if (connected) "Google Calendar connected." else "Calendar connection cancelled."
                                        calendarRefresh++
                                    }.onFailure { cloudMessage = it.message ?: "Calendar connection failed." }
                                }
                            },
                        ) { Text("Connect Calendar") }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Keyboard, null, tint = Cobalt)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Quick Fill autofill service", style = MaterialTheme.typography.titleMedium)
                        Text("Optional system integration for name, email, phone, and address only. Passwords, cards, PINs, and OTPs are always excluded.", color = Slate)
                    }
                    FilledTonalButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                    .setData(Uri.parse("package:${context.packageName}")),
                            )
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }) { Text("Open") }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    if (showApps) appQuery = ""
                    showApps = !showApps
                },
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Apps, null, tint = Cobalt)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("App exclusions", style = MaterialTheme.typography.titleMedium)
                        Text("Hide the bubble in apps you choose.", color = Slate)
                    }
                    Text(if (showApps) "Hide" else "Open", color = Cobalt, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (showApps) {
            item(key = "app-search") {
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                // Header + three controls + retention + storage + exclusions precede search.
                                scope.launch { listState.animateScrollToItem(7) }
                            }
                        },
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (appQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { appQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear app search")
                            }
                        }
                    } else null,
                    supportingText = {
                        Text(
                            if (apps == null) "Loading apps…"
                            else "${visibleApps.size} ${if (visibleApps.size == 1) "app" else "apps"}",
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                )
            }
            if (apps != null && visibleApps.isEmpty()) {
                item(key = "no-app-search-results") {
                    NoticeCard(
                        title = "No matching apps",
                        body = "Try an app name or package name, such as WhatsApp or com.whatsapp.",
                        accent = Cyan,
                    )
                }
            }
            items(visibleApps, key = { it.packageName }) { app ->
                val hardBlocked = context.appContainer.packagePolicy.isHardBlocked(app.packageName)
                val excluded = hardBlocked || app.packageName in settings.excludedPackages
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (hardBlocked) "Always protected" else app.packageName, style = MaterialTheme.typography.bodyMedium, color = if (hardBlocked) Coral else Slate, maxLines = 1)
                    }
                    Switch(
                        checked = excluded,
                        enabled = !hardBlocked,
                        onCheckedChange = { scope.launch { context.appContainer.packagePolicy.setExcluded(app.packageName, it) } },
                    )
                }
            }
        }
        if (BuildConfig.LAB_AUTOMATION) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Coral.copy(alpha = 0.10f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Experimental automation console", style = MaterialTheme.typography.titleMedium)
                        Text("ADB/private Lab build only. Every command stops for an exact preview; an unverified result never advances to another step.", color = Slate)
                        OutlinedTextField(
                            value = labCommandText,
                            onValueChange = { labCommandText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Visible control or text to insert") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                enabled = labCommandText.isNotBlank(),
                                onClick = { pendingLabCommand = AutomationCommand.ClickText(labCommandText.trim()) },
                            ) { Text("Click text") }
                            FilledTonalButton(
                                enabled = labCommandText.isNotBlank(),
                                onClick = { pendingLabCommand = AutomationCommand.SetFocusedText(labCommandText) },
                            ) { Text("Set field") }
                        }
                        OutlinedTextField(
                            value = labPackageName,
                            onValueChange = { labPackageName = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Allowlisted package to open") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                enabled = labPackageName in labAllowedPackages,
                                onClick = { pendingLabCommand = AutomationCommand.OpenApp(labPackageName) },
                            ) { Text("Open app") }
                            OutlinedButton(onClick = { pendingLabCommand = AutomationCommand.ScrollForward }) { Text("Scroll") }
                            OutlinedButton(onClick = { pendingLabCommand = AutomationCommand.Back }) { Text("Back") }
                        }
                        labExecutionMessage?.let { Text(it, color = if (it.startsWith("Verified")) Mint else Coral) }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showLabAllowlist = !showLabAllowlist },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Coral.copy(alpha = 0.10f)),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bolt, null, tint = Coral)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Lab automation allowlist", style = MaterialTheme.typography.titleMedium)
                            Text("Only explicitly selected non-sensitive apps can receive deterministic Lab actions.", color = Slate)
                        }
                        Text(if (showLabAllowlist) "Hide" else "Open", color = Coral, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (showLabAllowlist) {
                items(visibleApps, key = { "lab-${it.packageName}" }) { app ->
                    val hardBlocked = context.appContainer.packagePolicy.isHardBlocked(app.packageName)
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (hardBlocked) "Always protected" else app.packageName, color = if (hardBlocked) Coral else Slate, maxLines = 1)
                        }
                        Switch(
                            checked = !hardBlocked && app.packageName in labAllowedPackages,
                            enabled = !hardBlocked,
                            onCheckedChange = { allowed ->
                                context.appContainer.automation.setPackageAllowed(context, app.packageName, allowed)
                                labAllowedPackages = context.appContainer.automation.allowedPackages(context)
                            },
                        )
                    }
                }
            }
        }
        item {
            SectionLabel("CLOUD & CHATGPT", "Optional. The local bubble, vault, Quick Fill, and reminders work while signed out.")
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val account = accountState) {
                        AccountState.SignedOut -> {
                            Text("Cloud memory is off", style = MaterialTheme.typography.titleMedium)
                            Text("Sign in only when you want approved Shared AI memories to sync and become available through the Context Bubble MCP.", color = Slate)
                            Button(onClick = {
                                val activity = context as? MainActivity
                                if (activity == null) {
                                    cloudMessage = "Google sign-in is unavailable from this screen."
                                } else scope.launch {
                                    cloudMessage = "Opening Google sign-in…"
                                    runCatching { context.appContainer.googleSignIn.signIn(activity) }
                                        .onSuccess {
                                            cloudMessage = "Signed in. Syncing approved shared memories…"
                                            runCatching { context.appContainer.cloudMemorySync.sync() }
                                                .onSuccess { cloudMessage = "Cloud memory is ready." }
                                                .onFailure { cloudMessage = it.message ?: "Signed in; sync will retry automatically." }
                                        }
                                        .onFailure { cloudMessage = it.message ?: "Google sign-in failed." }
                                }
                            }) { Text("Sign in with Google") }
                        }
                        is AccountState.SignedIn -> {
                            Text("Cloud memory connected", style = MaterialTheme.typography.titleMedium)
                            Text(account.email ?: "Signed-in account", color = Slate)
                            Text(
                                if (settings.lastCloudSyncAtEpochMs > 0) "Last sync ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(settings.lastCloudSyncAtEpochMs))}"
                                else "Not synced yet",
                                color = Slate,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            cloudDashboard?.let { dashboard ->
                                Text(
                                    "${dashboard.memories} shared memories · ${formatBytes(dashboard.storage.usedBytes)} of ${formatBytes(dashboard.storage.limitBytes)} cloud output storage",
                                    color = Slate,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${dashboard.activeMcpGrants} MCP connection${if (dashboard.activeMcpGrants == 1) "" else "s"} · ${dashboard.activeIntegrations} integration${if (dashboard.activeIntegrations == 1) "" else "s"}",
                                    color = Slate,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        cloudMessage = "Syncing…"
                                        runCatching { context.appContainer.cloudMemorySync.sync() }
                                            .onSuccess { outcome ->
                                                cloudRefresh++
                                                cloudMessage = when (outcome) {
                                                    CloudSyncOutcome.SYNCED -> "Cloud memory is up to date."
                                                    CloudSyncOutcome.CONFLICT -> "A memory conflict needs review in Vault."
                                                    CloudSyncOutcome.SIGNED_OUT -> "Sign in again to sync."
                                                    CloudSyncOutcome.NOT_CONFIGURED -> "Cloud memory is not configured on this backend."
                                                }
                                            }
                                            .onFailure { cloudMessage = it.message ?: "Sync failed; queued changes are safe." }
                                    }
                                }) { Text("Sync now") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        context.appContainer.accounts.signOut()
                                        cloudMessage = "Signed out. Local data remains on this phone."
                                    }
                                }) { Text("Sign out") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        cloudMessage = "Preparing encrypted cloud export…"
                                        runCatching { context.appContainer.cloudAccount.exportJson() }
                                            .onSuccess {
                                                shareCloudExport(context, it)
                                                cloudMessage = "Cloud export is ready to save or share."
                                            }
                                            .onFailure { cloudMessage = it.message ?: "Cloud export failed." }
                                    }
                                }) { Text("Export cloud data") }
                                OutlinedButton(
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral),
                                    onClick = {
                                        scope.launch {
                                            runCatching { context.appContainer.cloudAccount.prepareDelete() }
                                                .onSuccess { deletePreview = it }
                                                .onFailure { cloudMessage = it.message ?: "Could not prepare deletion." }
                                        }
                                    },
                                ) { Text("Delete cloud data") }
                            }
                        }
                        is AccountState.Unavailable -> {
                            Text("Cloud account unavailable", style = MaterialTheme.typography.titleMedium)
                            Text(account.message, color = Coral)
                        }
                    }
                    cloudMessage?.let { Text(it, color = if (it.contains("fail", true) || it.contains("unavailable", true)) Coral else Slate) }
                }
            }
        }
        item {
            NoticeCard(
                "Connect ChatGPT through MCP",
                "ChatGPT does not receive native ChatGPT Memory. After the production MCP URL is connected and you approve OAuth access, ChatGPT can search approved Shared AI memories. Writes and deletes require a separate preview and confirmation.",
                Cyan,
            )
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Advanced backend", style = MaterialTheme.typography.titleMedium)
                    Text("Only enter a compatible HTTPS Context Bubble backend. API keys and Supabase secrets never belong here.", color = Slate)
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom backend URL") },
                        placeholder = { Text("Use managed default") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            val normalized = backendUrl.trim().trimEnd('/')
                            val valid = normalized.isBlank() || normalized.startsWith("https://") || (
                                BuildConfig.DEBUG && listOf("http://127.0.0.1", "http://localhost", "http://10.0.2.2").any(normalized::startsWith)
                            )
                            if (!valid) cloudMessage = "A custom backend must use HTTPS."
                            else scope.launch {
                                context.appContainer.settings.setCustomBackendUrl(normalized.ifBlank { null })
                                context.appContainer.cloudConfiguration.invalidate()
                                cloudMessage = if (normalized.isBlank()) "Using the managed backend." else "Backend updated. Test sign-in and sync before relying on it."
                            }
                        }) { Text("Apply") }
                        OutlinedButton(onClick = {
                            backendUrl = ""
                            scope.launch {
                                context.appContainer.settings.setCustomBackendUrl(null)
                                context.appContainer.cloudConfiguration.invalidate()
                                cloudMessage = "Using the managed backend."
                            }
                        }) { Text("Reset") }
                    }
                }
            }
        }
    }
    deletePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { deletePreview = null },
            title = { Text("Permanently delete cloud data?") },
            text = { Text(preview.preview) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    onClick = {
                        scope.launch {
                            runCatching { context.appContainer.cloudAccount.commitDelete(preview) }
                                .onSuccess {
                                    deletePreview = null
                                    cloudMessage = "Cloud data and account deleted. Local phone data remains."
                                }
                                .onFailure { cloudMessage = it.message ?: "Cloud deletion failed." }
                        }
                    },
                ) { Text("Delete permanently") }
            },
            dismissButton = { OutlinedButton(onClick = { deletePreview = null }) { Text("Cancel") } },
        )
    }
    pendingLabCommand?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingLabCommand = null },
            title = { Text("Confirm experimental action") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(automationPreview(command))
                    Text("Context Bubble will execute only this one deterministic step, then report whether it could verify the result.", color = Slate)
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    onClick = {
                        val result = context.appContainer.automation.execute(context, command)
                        labExecutionMessage = when {
                            result.success && result.verified -> "Verified · ${result.detail}"
                            result.success -> "Stopped unverified · ${result.detail}"
                            else -> "Blocked or failed · ${result.detail}"
                        }
                        pendingLabCommand = null
                    },
                ) { Text("Run one step") }
            },
            dismissButton = { OutlinedButton(onClick = { pendingLabCommand = null }) { Text("Cancel") } },
        )
    }
}

private fun automationPreview(command: AutomationCommand): String = when (command) {
    is AutomationCommand.OpenApp -> "Open application package: ${command.packageName}"
    is AutomationCommand.ClickText -> "Click the visible control whose text exactly matches: “${command.text}”"
    is AutomationCommand.SetFocusedText -> "Replace the currently focused editable field with: “${command.text}”"
    AutomationCommand.ScrollForward -> "Scroll the first bounded visible scroll container forward once."
    AutomationCommand.Back -> "Perform Android Back once."
}

private suspend fun shareCloudExport(context: Context, content: String) {
    val uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(directory, "context-bubble-cloud-export-${System.currentTimeMillis()}.json")
        file.writeText(content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Save Context Bubble export",
        ),
    )
}

@Composable
private fun RetentionPicker(selected: RetentionPolicy, onSelect: (RetentionPolicy) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RetentionPolicy.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { policy ->
                    val label = when (policy.days) {
                        1 -> "1 day"
                        null -> "No expiry"
                        else -> "${policy.days} days"
                    }
                    val contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    if (policy == selected) {
                        Button(
                            onClick = { onSelect(policy) },
                            modifier = Modifier.weight(1f),
                            contentPadding = contentPadding,
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(policy) },
                            modifier = Modifier.weight(1f),
                            contentPadding = contentPadding,
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, body: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Slate)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun ScreenScaffold(
    modifier: Modifier,
    title: String,
    subtitle: String,
    state: LazyListState? = null,
    action: @Composable (() -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val resolvedState = state ?: rememberLazyListState()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = resolvedState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("PRIVATE BY DEFAULT", style = MaterialTheme.typography.labelSmall, color = Cobalt)
                    Text(title, style = MaterialTheme.typography.displaySmall)
                    Text(subtitle, color = Slate)
                }
                action?.invoke()
            }
        }
        content()
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.Start) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Mist), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Cobalt) }
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 18.dp))
            Text(body, color = Slate, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun DataCard(
    label: String,
    title: String,
    footnote: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable(onClick = onClick)
    Card(shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(18.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Cobalt)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            value?.let { Text(it, color = Ink, modifier = Modifier.padding(top = 8.dp)) }
            Text(footnote, style = MaterialTheme.typography.bodyMedium, color = Slate, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun MemoryDetailDialog(memory: VaultMemory, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conflict by produceState<MemoryConflict?>(null, memory.id, memory.hasConflict) {
        value = if (memory.hasConflict) context.appContainer.vault.loadConflict(memory.id) else null
    }
    fun resolve(action: suspend () -> Unit) {
        scope.launch {
            action()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (memory.hasConflict) "Review memory conflict" else memory.summary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (memory.hasConflict) {
                    Text("On this phone", style = MaterialTheme.typography.labelLarge, color = Cobalt)
                    Text(memory.summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(memory.value, modifier = Modifier.padding(top = 4.dp))
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("In Shared Cloud", style = MaterialTheme.typography.labelLarge, color = Cobalt)
                    if (conflict == null) {
                        Text("Decrypting the cloud version…", color = Slate, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text(conflict!!.cloud.summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                        Text(conflict!!.cloud.value, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text("Nothing is overwritten until you choose.", color = Slate, modifier = Modifier.padding(top = 14.dp))
                } else {
                    Text(memory.value)
                    Text(
                        if (memory.scope.name == "LOCAL_ONLY") "Stored only on this phone" else "Approved for Context Bubble assistant requests",
                        color = Slate,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (memory.hasConflict) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(enabled = conflict != null, onClick = { resolve { context.appContainer.vault.resolveConflictKeepPhone(memory.id) } }) { Text("Keep phone") }
                    OutlinedButton(enabled = conflict != null, onClick = { resolve { context.appContainer.vault.resolveConflictKeepCloud(memory.id) } }) { Text("Keep cloud") }
                    OutlinedButton(enabled = conflict != null, onClick = { resolve { context.appContainer.vault.resolveConflictKeepBoth(memory.id) } }) { Text("Keep both") }
                }
            } else {
                Button(onClick = {
                    OverlayShareActions.copyText(context, memory.value)
                    onDismiss()
                }) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (memory.hasConflict) "Decide later" else "Done") } },
    )
}

@Composable
private fun CaptureDetailDialog(capture: LocalCapture, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bytes by produceState<ByteArray?>(null, capture.id) {
        value = withContext(Dispatchers.IO) { context.appContainer.vault.loadCaptureBytes(capture.id) }
    }
    val image = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    val isImage = capture.kind == "SCREENSHOT_PNG" || capture.kind == "SCREENSHOT_JPEG" || capture.kind == "GENERATED_IMAGE_PNG"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(capture.kind.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)) },
        text = {
            when {
                bytes == null -> Text("Decrypting capture…")
                isImage && image != null -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Saved capture",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    contentScale = ContentScale.Fit,
                )
                capture.state == "TRANSCRIBED" -> Text(bytes!!.toString(Charsets.UTF_8))
                else -> Text("This capture is stored encrypted. A preview is not available for this format.")
            }
        },
        confirmButton = {
            if (isImage && bytes != null) {
                Button(onClick = { OverlayShareActions.copyImage(context, bytes!!) }) { Text("Copy") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isImage && bytes != null) {
                    OutlinedButton(onClick = { OverlayShareActions.shareImage(context, bytes!!) }) { Text("Share") }
                }
                OutlinedButton(onClick = onDismiss) { Text("Done") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddQuickFillDialog(onDismiss: () -> Unit, onSave: (String, String, String, Boolean) -> Unit) {
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Text") }
    var allowAi by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save a Quick Fill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                OutlinedTextField(kind, { kind = it }, label = { Text("Type") }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(allowAi, { allowAi = it })
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("Allow AI when selected")
                        Text("Off keeps this local only", color = Slate, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { Button(enabled = label.isNotBlank() && value.isNotBlank(), onClick = { onSave(label, kind, value, allowAi) }) { Text("Save") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NoticeCard(title: String, body: String, accent: Color, icon: ImageVector = Icons.Rounded.HealthAndSafety) {
    Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = accent)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, color = Slate, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Cobalt)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Slate)
    }
}

@Composable
private fun OrbitMark(active: Boolean, blocked: Boolean, modifier: Modifier = Modifier) {
    val accent = when {
        blocked -> Coral
        active -> Cyan
        else -> Cobalt
    }
    Canvas(modifier) {
        val center = this.center
        drawCircle(Ink, radius = size.minDimension * 0.38f, center = center)
        drawCircle(accent, radius = size.minDimension * 0.28f, center = center, style = Stroke(width = size.minDimension * 0.09f))
        drawArc(
            color = accent,
            startAngle = -55f,
            sweepAngle = if (active) 250f else 120f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.06f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.88f, size.height * 0.88f),
            style = Stroke(width = size.minDimension * 0.035f, cap = StrokeCap.Round),
        )
        drawCircle(Color.White, radius = size.minDimension * 0.085f, center = center)
    }
}

private data class PermissionSnapshot(
    val overlay: Boolean,
    val accessibility: Boolean,
    val notifications: Boolean,
    val microphone: Boolean,
    val batteryUnrestricted: Boolean,
)

private fun permissionSnapshot(context: Context, accessibilityConnected: Boolean): PermissionSnapshot {
    val power = context.getSystemService(PowerManager::class.java)
    return PermissionSnapshot(
        overlay = Settings.canDrawOverlays(context),
        accessibility = accessibilityConnected || isAccessibilityServiceEnabled(context),
        notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName),
    )
}

private fun openBatteryOptimizationSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val primary = if (BuildConfig.DEBUG) {
        // Debug builds are privately installed for device testing. A direct
        // system prompt removes OEM ambiguity and lets us verify the same
        // PowerManager state displayed by Assistant health.
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
    } else {
        // Keep the Play release on the user-managed exemption list. Direct
        // exemption requests require a narrowly accepted Play policy use case.
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
    runCatching { context.startActivity(primary) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

internal data class LaunchableApp(val label: String, val packageName: String)

internal fun filterLaunchableApps(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
    val term = query.trim()
    if (term.isEmpty()) return apps
    return apps
        .filter { app ->
            app.label.contains(term, ignoreCase = true) ||
                app.packageName.contains(term, ignoreCase = true)
        }
        .sortedWith(
            compareBy<LaunchableApp> { app ->
                when {
                    app.label.equals(term, ignoreCase = true) -> 0
                    app.label.startsWith(term, ignoreCase = true) -> 1
                    app.label.contains(term, ignoreCase = true) -> 2
                    app.packageName.startsWith(term, ignoreCase = true) -> 3
                    else -> 4
                }
            }.thenBy { it.label.lowercase() },
        )
}

private fun launchableApps(context: Context): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        .map { LaunchableApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
