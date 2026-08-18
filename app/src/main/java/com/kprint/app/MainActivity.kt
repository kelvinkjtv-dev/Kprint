package com.kprint.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kprint.app.data.AppConfig
import com.kprint.app.data.EventLogStore
import com.kprint.app.data.EventType
import com.kprint.app.data.PrintEvent
import com.kprint.app.data.SettingsStore
import com.kprint.app.printing.BluetoothPrinter
import com.kprint.app.printing.PairedPrinter
import com.kprint.app.service.MonitorPreference
import com.kprint.app.service.MonitorRuntime
import com.kprint.app.service.MonitorSnapshot
import com.kprint.app.service.MonitorStatus
import com.kprint.app.service.PrintMonitorService
import com.kprint.app.ui.theme.KPrintAmber
import com.kprint.app.ui.theme.KPrintGreen
import com.kprint.app.ui.theme.KPrintNavy
import com.kprint.app.ui.theme.KPrintTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { KPrintTheme { KPrintApp() } }
    }
}

private enum class AppTab(val label: String, val symbol: String) {
    DASHBOARD("Painel", "●"),
    SETTINGS("Configurar", "⚙"),
    HISTORY("Histórico", "≡"),
}

private enum class PermissionAction { START, TEST, REFRESH }

@Composable
private fun KPrintApp() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val eventStore = remember { EventLogStore(context) }
    val bluetooth = remember { BluetoothPrinter(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val monitor by MonitorRuntime.state.collectAsState()

    var selectedTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var config by remember { mutableStateOf(settingsStore.load()) }
    var events by remember { mutableStateOf(eventStore.load()) }
    var pairedDevices by remember { mutableStateOf(emptyList<PairedPrinter>()) }
    var monitorEnabled by remember { mutableStateOf(MonitorPreference.isEnabled(context)) }
    var pendingPermissionAction by remember { mutableStateOf<PermissionAction?>(null) }

    fun refreshDevices() {
        pairedDevices = bluetooth.pairedDevices()
    }

    fun startMonitor() {
        if (!config.isReady) {
            selectedTab = AppTab.SETTINGS
            scope.launch { snackbar.showSnackbar("Complete e salve a configuração primeiro") }
            return
        }
        runCatching { PrintMonitorService.start(context) }
            .onSuccess { monitorEnabled = true }
            .onFailure { scope.launch { snackbar.showSnackbar(it.message ?: "Não foi possível iniciar") } }
    }

    fun testPrint() {
        if (!config.isPrinterReady) {
            selectedTab = AppTab.SETTINGS
            scope.launch { snackbar.showSnackbar("Selecione e salve uma impressora") }
            return
        }
        runCatching { PrintMonitorService.printTest(context) }
            .onFailure { scope.launch { snackbar.showSnackbar(it.message ?: "Não foi possível imprimir") } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            result[Manifest.permission.BLUETOOTH_CONNECT] == true || bluetooth.hasPermission()
        if (bluetoothGranted) {
            refreshDevices()
            when (pendingPermissionAction) {
                PermissionAction.START -> startMonitor()
                PermissionAction.TEST -> testPrint()
                else -> Unit
            }
        } else {
            scope.launch { snackbar.showSnackbar("O Bluetooth é necessário para imprimir") }
        }
        pendingPermissionAction = null
    }

    fun withBluetoothPermission(action: PermissionAction) {
        if (bluetooth.hasPermission()) {
            refreshDevices()
            when (action) {
                PermissionAction.START -> startMonitor()
                PermissionAction.TEST -> testPrint()
                PermissionAction.REFRESH -> Unit
            }
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(requiredRuntimePermissions())
        }
    }

    LaunchedEffect(Unit) {
        if (bluetooth.hasPermission()) refreshDevices()
        while (true) {
            events = eventStore.load()
            monitorEnabled = MonitorPreference.isEnabled(context)
            delay(2_000)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(monitorEnabled && monitor.status != MonitorStatus.STOPPED) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.symbol, fontSize = 19.sp) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            label = "tabs",
            modifier = Modifier.padding(padding),
        ) { tab ->
            when (tab) {
                AppTab.DASHBOARD -> DashboardScreen(
                    config = config,
                    monitor = monitor,
                    enabled = monitorEnabled,
                    events = events,
                    onToggle = {
                        if (monitorEnabled) {
                            PrintMonitorService.stop(context)
                            monitorEnabled = false
                        } else {
                            withBluetoothPermission(PermissionAction.START)
                        }
                    },
                    onTestPrint = { withBluetoothPermission(PermissionAction.TEST) },
                    onConfigure = { selectedTab = AppTab.SETTINGS },
                    onHistory = { selectedTab = AppTab.HISTORY },
                )
                AppTab.SETTINGS -> SettingsScreen(
                    initial = config,
                    pairedDevices = pairedDevices,
                    hasBluetoothPermission = bluetooth.hasPermission(),
                    onRequestBluetooth = { withBluetoothPermission(PermissionAction.REFRESH) },
                    onOpenBluetoothSettings = {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                    onSave = { updated ->
                        settingsStore.save(updated)
                        config = settingsStore.load()
                        scope.launch { snackbar.showSnackbar("Configuração salva com segurança") }
                        selectedTab = AppTab.DASHBOARD
                    },
                )
                AppTab.HISTORY -> HistoryScreen(
                    events = events,
                    onClear = {
                        eventStore.clear()
                        events = emptyList()
                    },
                )
            }
        }
    }
}

@Composable
private fun AppHeader(active: Boolean) {
    Surface(color = KPrintNavy, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(KPrintGreen),
                contentAlignment = Alignment.Center,
            ) { Text("K", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("KPrint", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Delivery Print Hub", color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (active) KPrintGreen else Color(0xFF8DA099)),
                )
                Spacer(Modifier.width(7.dp))
                Text(if (active) "ATIVO" else "PARADO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    config: AppConfig,
    monitor: MonitorSnapshot,
    enabled: Boolean,
    events: List<PrintEvent>,
    onToggle: () -> Unit,
    onTestPrint: () -> Unit,
    onConfigure: () -> Unit,
    onHistory: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Central de impressão", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Seus pedidos continuam imprimindo mesmo com outro app aberto.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
        item { MonitorCard(monitor, enabled, onToggle) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Impressos", monitor.printedThisSession.toString(), "nesta sessão", Modifier.weight(1f))
                MetricCard("Intervalo", "${config.pollingSeconds}s", "consulta segura", Modifier.weight(1f))
            }
        }
        item {
            PrinterCard(config, enabled, onTestPrint, onConfigure)
        }
        if (!config.isReady) {
            item { SetupCard(config, onConfigure) }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Atividade recente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Ver tudo", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onHistory).padding(8.dp))
            }
        }
        if (events.isEmpty()) {
            item { EmptyHistory() }
        } else {
            items(events.take(4), key = { it.id + it.timestamp }) { EventRow(it) }
        }
    }
}

@Composable
private fun MonitorCard(monitor: MonitorSnapshot, enabled: Boolean, onToggle: () -> Unit) {
    val isBusy = monitor.status == MonitorStatus.STARTING || monitor.status == MonitorStatus.PRINTING
    val accent = when (monitor.status) {
        MonitorStatus.ERROR -> MaterialTheme.colorScheme.error
        MonitorStatus.STOPPED -> Color(0xFF87958F)
        else -> KPrintGreen
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = KPrintNavy),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(30.dp), color = KPrintGreen, strokeWidth = 3.dp)
                } else {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(11.dp).clip(CircleShape).background(accent))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusTitle(monitor.status), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(monitor.message, color = Color.White.copy(alpha = .68f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(13.dp),
            ) { Text(if (enabled) "Parar monitor" else "Iniciar monitor", fontWeight = FontWeight.Bold) }
        }
    }
}

private fun statusTitle(status: MonitorStatus) = when (status) {
    MonitorStatus.STOPPED -> "Monitor desligado"
    MonitorStatus.STARTING -> "Conectando"
    MonitorStatus.LISTENING -> "Pronto para imprimir"
    MonitorStatus.PRINTING -> "Pedido recebido"
    MonitorStatus.ERROR -> "Atenção necessária"
}

@Composable
private fun MetricCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 12.sp)
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun PrinterCard(config: AppConfig, monitorEnabled: Boolean, onTestPrint: () -> Unit, onConfigure: () -> Unit) {
    Card(shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("P", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(config.printerName.ifBlank { "Nenhuma impressora" }, fontWeight = FontWeight.Bold)
                    Text(
                        if (config.isPrinterReady) "${config.paperWidth} mm • ${config.printerAddress}" else "Toque para configurar",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                        fontSize = 12.sp,
                    )
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (config.isPrinterReady) KPrintGreen else KPrintAmber))
            }
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onConfigure, modifier = Modifier.weight(1f)) { Text("Configurar") }
                FilledTonalButton(onClick = onTestPrint, enabled = config.isPrinterReady && !monitorEnabled, modifier = Modifier.weight(1f)) {
                    Text("Imprimir teste")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(config: AppConfig, onConfigure: () -> Unit) {
    val steps = listOf(config.isCloudReady, config.isPrinterReady).count { it }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(17.dp)) {
            Text("Finalize a instalação", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("$steps de 2 etapas concluídas", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) { index ->
                    Box(Modifier.height(7.dp).weight(1f).clip(CircleShape).background(if (index < steps) KPrintGreen else Color.White.copy(alpha = .6f)))
                }
            }
            Spacer(Modifier.height(13.dp))
            Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) { Text("Configurar agora") }
        }
    }
}

@Composable
private fun SettingsScreen(
    initial: AppConfig,
    pairedDevices: List<PairedPrinter>,
    hasBluetoothPermission: Boolean,
    onRequestBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onSave: (AppConfig) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var tokenVisible by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Configuração", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Conecte o app à fila segura do seu projeto Supabase.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))

        SectionCard("Supabase") {
            Field("URL do projeto", draft.supabaseUrl, "https://seu-projeto.supabase.co") { draft = draft.copy(supabaseUrl = it) }
            Field("Chave anon (publishable)", draft.supabaseAnonKey, "eyJ…") { draft = draft.copy(supabaseAnonKey = it) }
            Field("ID da loja", draft.storeId, "UUID da loja") { draft = draft.copy(storeId = it) }
            Field("ID deste dispositivo", draft.deviceId, "UUID cadastrado em kprint_printers") { draft = draft.copy(deviceId = it) }
            OutlinedTextField(
                value = draft.deviceToken,
                onValueChange = { draft = draft.copy(deviceToken = it) },
                label = { Text("Token secreto do dispositivo") },
                placeholder = { Text("Token criado no cadastro da impressora") },
                visualTransformation = if (tokenVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        if (tokenVisible) "Ocultar" else "Exibir",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { tokenVisible = !tokenVisible }.padding(8.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text("Nunca use a service_role no aplicativo. O token fica criptografado no aparelho.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }

        SectionCard("Impressora Bluetooth") {
            if (!hasBluetoothPermission) {
                OutlinedButton(onClick = onRequestBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Permitir acesso ao Bluetooth") }
            } else if (pairedDevices.isEmpty()) {
                Text("Nenhum dispositivo pareado encontrado.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                OutlinedButton(onClick = onOpenBluetoothSettings, modifier = Modifier.fillMaxWidth()) { Text("Abrir configurações do Bluetooth") }
            } else {
                pairedDevices.forEach { device ->
                    val selected = draft.printerAddress == device.address
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { draft = draft.copy(printerName = device.name, printerAddress = device.address) }
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) KPrintGreen else Color(0xFFB6C0BB)))
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text(device.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                        }
                    }
                }
            }
            Text("Largura do papel", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(58, 80).forEach { width ->
                    val selected = draft.paperWidth == width
                    if (selected) {
                        Button(onClick = { draft = draft.copy(paperWidth = width) }, modifier = Modifier.weight(1f)) { Text("$width mm") }
                    } else {
                        OutlinedButton(onClick = { draft = draft.copy(paperWidth = width) }, modifier = Modifier.weight(1f)) { Text("$width mm") }
                    }
                }
            }
        }

        SectionCard("Funcionamento") {
            Field(
                label = "Intervalo entre consultas (3–60 segundos)",
                value = draft.pollingSeconds.toString(),
                placeholder = "5",
                keyboardType = KeyboardType.Number,
            ) { value -> draft = draft.copy(pollingSeconds = value.toIntOrNull()?.coerceIn(3, 60) ?: 5) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Retomar após reiniciar", fontWeight = FontWeight.SemiBold)
                    Text("Reabre o monitor se ele já estava ativo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
                Switch(checked = draft.autoStart, onCheckedChange = { draft = draft.copy(autoStart = it) })
            }
        }

        Button(
            onClick = { onSave(draft) },
            enabled = draft.supabaseUrl.isNotBlank() || draft.printerAddress.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(13.dp),
        ) { Text("Salvar configuração", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}

@Composable
private fun HistoryScreen(events: List<PrintEvent>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Histórico", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Últimos eventos deste aparelho", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
            if (events.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("Limpar") }
        }
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.TopCenter) { EmptyHistory() }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(events, key = { it.id + it.timestamp }) { EventRow(it) }
            }
        }
    }
}

@Composable
private fun EventRow(event: PrintEvent) {
    val color = when (event.type) {
        EventType.PRINTED -> KPrintGreen
        EventType.ERROR -> MaterialTheme.colorScheme.error
        EventType.INFO -> KPrintAmber
    }
    Card(shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(event.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(formatEventTime(event.timestamp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
        }
    }
}

@Composable
private fun EmptyHistory() {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tudo pronto", fontWeight = FontWeight.Bold)
            Text("As impressões aparecerão aqui.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }
    }
}

private fun formatEventTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault("")

private fun requiredRuntimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
