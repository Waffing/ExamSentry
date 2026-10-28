package cn.exam.sentry.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.exam.sentry.core.*
import cn.exam.sentry.detectors.EnvironmentScanner
import cn.exam.sentry.detectors.MonitoringService
import cn.exam.sentry.detectors.PermissionTools
import cn.exam.sentry.lab.BrowserLab
import cn.exam.sentry.lab.NativeExamLab
import cn.exam.sentry.media.MediaMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF176B68)
private val Amber = Color(0xFF946200)
private val DarkScheme = darkColorScheme(primary = Color(0xFF88CCC4), secondary = Color(0xFFE7BE73), background = Color(0xFF171C1F), surface = Color(0xFF1D2428), surfaceVariant = Color(0xFF293337), primaryContainer = Color(0xFF203D3B), secondaryContainer = Color(0xFF393329), outlineVariant = Color(0xFF394247))
private val LightScheme = lightColorScheme(primary = Teal, secondary = Color(0xFF806029), background = Color(0xFFF5F7F6), surface = Color.White, surfaceVariant = Color(0xFFEBEFEE), primaryContainer = Color(0xFFE1EFEB), secondaryContainer = Color(0xFFF1EADC), outlineVariant = Color(0xFFDDE4E1))
private val SentryShapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp))
private val SentryTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentryApp(activity: ComponentActivity, media: MediaMonitor, setProtection: (Boolean) -> Unit) {
    val state by SentryRuntime.state.collectAsStateWithLifecycle()
    val mediaState by media.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sentry_settings", 0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pageScroll = rememberScrollState()
    var settings by remember { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var darkPreference by rememberSaveable { mutableIntStateOf(prefs.getInt("theme", 0)) }
    val dark = if (darkPreference == 0) isSystemInDarkTheme() else darkPreference == 2
    val snack = remember { SnackbarHostState() }
    var label by rememberSaveable { mutableStateOf(prefs.getString("label", "环境监测") ?: "环境监测") }
    var target by rememberSaveable { mutableStateOf(prefs.getString("target", context.packageName) ?: context.packageName) }
    var targetLabel by rememberSaveable { mutableStateOf(prefs.getString("targetLabel", "内置实测考场") ?: "内置实测考场") }
    var allowed by remember { mutableStateOf(runCatching { JSONArray(prefs.getString("allowed", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())) }
    var online by rememberSaveable { mutableStateOf(prefs.getBoolean("online", false)) }
    var onlineDisclosure by remember { mutableStateOf(false) }
    var appPicker by remember { mutableIntStateOf(0) }
    var protection by rememberSaveable { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf<String?>(null) }
    var pendingMode by remember { mutableStateOf<String?>(null) }
    var selectedIncident by remember { mutableStateOf<IncidentEntity?>(null) }
    var selectedEvent by remember { mutableStateOf<EventEntity?>(null) }
    var elapsed by remember { mutableLongStateOf(0) }
    val scanner = remember { EnvironmentScanner(context.applicationContext) }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val active = state.activeSession
    val activeLab = active?.mode == "LAB"

    fun inform(message: String) { scope.launch { snack.showSnackbar(message) } }
    fun start(mode: String) {
        scope.launch {
            runCatching {
                prefs.edit().putString("label", label).putString("target", target).putString("targetLabel", targetLabel).putString("allowed", JSONArray(allowed.toList()).toString()).putBoolean("online", online).apply()
                SentryRuntime.start(SessionConfig(label.ifBlank { "检测会话" }, mode, if (mode == "LAB") context.packageName else target, allowed, online))
                ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
            }.onFailure { SentryRuntime.stop(); inform("无法开始监测：${it.message ?: "请检查系统权限"}") }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingMode
        pendingMode = null
        if (granted && requested != null) start(requested) else inform("通知未授权，持续监测尚未开始")
    }
    fun begin(mode: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingMode = mode
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else start(mode)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PermissionTools.endSystemPermissionFlow()
        if (granted && activeLab) media.startCamera(activity, preview) else if (!granted) inform("相机未授权，视觉检测保持关闭")
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PermissionTools.endSystemPermissionFlow()
        if (granted && activeLab) media.startAudio() else if (!granted) inform("麦克风未授权，语音检测保持关闭")
    }
    val importReport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(context.cacheDir, "reports/import-${System.currentTimeMillis()}.json")
                    file.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法打开文件" }
                        file.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 30L * 1024 * 1024) { "文件超过 30 MB" }; out.write(buffer, 0, count) }
                        }
                    }
                    SentryRuntime.verify(file)
                }.getOrElse { "校验失败：${it.message}" }
            }
            verification = result
        }
    }
    fun export(sessionId: String, format: String) {
        if (exportBusy) return
        exportBusy = true
        scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { SentryRuntime.export(context, sessionId, format) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val mime = when (format) { "JSON" -> "application/json"; "PDF" -> "application/pdf"; else -> "image/png" }
                val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.json")
                val intent = if (format != "JSON" && sidecar.exists()) Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, FileProvider.getUriForFile(context, "${context.packageName}.files", sidecar)))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.startActivity(Intent.createChooser(intent, "导出检测报告"))
            }.onFailure { inform("导出失败：${it.message}") }
            exportBusy = false
        }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { scanner.scan() } }
    LaunchedEffect(active?.id) {
        if (!activeLab) { protection = false; setProtection(false); media.stopAll() }
        while (active != null) { elapsed = ((SystemClock.elapsedRealtime() - active.startedElapsed) / 1000).coerceAtLeast(0); delay(1000) }
    }
    DisposableEffect(Unit) { onDispose { setProtection(false) } }
    LaunchedEffect(tab, about) { pageScroll.scrollTo(0); if (tab != 2 || about) media.stopAll() }

    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, shapes = SentryShapes, typography = SentryTypography) {
        if (about) {
            BackHandler { about = false }
            AboutScreen(onBack = { about = false })
            return@MaterialTheme
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("考境哨兵", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                                Text("考试防作弊检测", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { about = true }) { Icon(Icons.Outlined.Info, "关于考境哨兵", Modifier.size(22.dp)) }
                            IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "设置", Modifier.size(22.dp)) }
                        }
                        Hairline()
                    }
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Hairline()
                        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    listOf("总览" to Icons.Outlined.Dashboard, "监测" to Icons.Outlined.Sensors, "实测" to Icons.Outlined.Science, "报告" to Icons.Outlined.Description).forEachIndexed { index, item ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.second, item.first, Modifier.size(22.dp)) }, label = { Text(item.first, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                    }
                        }
                    }
                }
            }, snackbarHost = { SnackbarHost(snack) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        StatusBand(active, elapsed, state.incidents.size)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Metric("观察事件", (state.events.maxOfOrNull { it.seq } ?: 0).toString(), Modifier.weight(1f))
                            VerticalDivider(Modifier.height(34.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Metric("待复核", state.incidents.size.toString(), Modifier.weight(1f), if (state.incidents.isNotEmpty()) Amber else null)
                            VerticalDivider(Modifier.height(34.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Metric("有效探针", state.probes.count { it.status in setOf("ACTIVE", "AVAILABLE") }.toString(), Modifier.weight(1f))
                        }
                        if (active == null) Button(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("建立监测会话") }
                        else OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.Sensors, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("查看实时监测") }
                        SectionTitle("最新异常", Icons.Outlined.Flag, state.incidents.size.takeIf { it > 0 }?.let { "$it 条待复核" })
                        if (state.incidents.isEmpty()) EmptyLine("暂无待复核记录", if (active == null) "会话尚未开始" else "仅代表当前已覆盖的检测范围")
                        state.incidents.take(3).forEach { IncidentRow(it) { selectedIncident = it } }
                        SectionTitle("检测覆盖", Icons.Outlined.Sensors, "${state.probes.size} 项")
                        state.probes.sortedBy { probeOrder(it.status) }.forEach { ProbeRow(it) }
                        if (state.probes.isEmpty()) EmptyLine("正在读取设备状态", "首次检查中")
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { scanner.scan() }; inform("设备状态已刷新") } }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("刷新状态") }
                    }
                    1 -> {
                        SectionTitle("持续监测", Icons.Outlined.Sensors)
                        if (active == null) {
                            OutlinedTextField(value = label, onValueChange = { label = it.take(48) }, label = { Text("会话名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            SettingRow("考试应用", targetLabel, Icons.Outlined.Apps) { appPicker = 1 }
                            SettingRow("允许应用", if (allowed.isEmpty()) "未添加" else "${allowed.size} 个应用", Icons.Outlined.Checklist) { appPicker = 2 }
                            ToggleRow("在线网络检查", "查询出口 IP 变化", online) { if (it) onlineDisclosure = true else online = false }
                            Hairline()
                            PermissionRow("使用情况访问", PermissionTools.usageGranted(context), "前台应用与停留时长") { PermissionTools.openUsageSettings(context) }
                            PermissionRow("无障碍窗口信息", PermissionTools.accessibilityGranted(context), "可选，补充小窗与覆盖层线索") { PermissionTools.openAccessibilitySettings(context) }
                            Button(onClick = { begin("MONITOR") }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("开始监测") }
                        } else {
                            StatusBand(active, elapsed, state.incidents.size)
                            OutlinedButton(onClick = { context.startService(Intent(context, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP)); media.stopAll(); protection = false; setProtection(false) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text("结束会话") }
                        }
                        SectionTitle("事件时间线", Icons.Outlined.Timeline, "${state.events.size} 条")
                        if (state.events.isEmpty()) EmptyLine("暂无事件", "实际采集后显示")
                        state.events.take(60).forEach { EventRow(it) { selectedEvent = it } }
                    }
                    2 -> {
                        SectionTitle("实测考场", Icons.Outlined.Science)
                        if (!activeLab) {
                            EmptyLine("${if (active == null) "实测尚未开始" else "持续监测正在运行"}", "实测事件单独保存")
                            Button(onClick = { begin("LAB") }, enabled = active == null, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("开始实测会话") }
                        } else {
                            Text("实测中 · ${formatDuration(elapsed)} · ${active?.id?.take(8)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            NativeExamLab(active?.id, protection) { protection = it; setProtection(it) }
                            SectionTitle("现场辅助", Icons.Outlined.Videocam)
                            ToggleRow("视觉检测", if (mediaState.cameraRunning) "人脸 ${mediaState.faceCount ?: "待识别"} · ${mediaState.quality}" else "未开启", mediaState.cameraRunning) { enabled ->
                                if (!enabled) media.stopCamera() else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) media.startCamera(activity, preview) else { PermissionTools.beginSystemPermissionFlow(); cameraPermission.launch(Manifest.permission.CAMERA) }
                            }
                            if (mediaState.cameraRunning) AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().height(220.dp))
                            ToggleRow("语音活动", if (mediaState.audioRunning) "语音概率 ${mediaState.speechProbability?.let { "${(it * 100).toInt()}%" } ?: "待分析"}" else "未开启", mediaState.audioRunning) { enabled ->
                                if (!enabled) media.stopAudio() else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) media.startAudio() else { PermissionTools.beginSystemPermissionFlow(); audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
                            }
                            if (mediaState.error != null) Text(mediaState.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            SectionTitle("浏览器专项", Icons.Outlined.Language)
                            BrowserLab(active?.id)
                            OutlinedButton(onClick = { context.startService(Intent(context, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP)); media.stopAll(); setProtection(false); protection = false }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text("结束实测") }
                        }
                    }
                    3 -> {
                        SectionTitle("检测报告", Icons.Outlined.Description)
                        TextButton(onClick = { importReport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Icon(Icons.Outlined.FactCheck, null); Spacer(Modifier.width(8.dp)); Text("校验已导出的 JSON") }
                        if (exportBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.sessions.isEmpty()) EmptyLine("尚无报告", "结束会话后保留记录")
                        state.sessions.sortedByDescending { it.startedWall }.forEach { session ->
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(session.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); StatusLabel(sessionStatus(session.status), if (session.status == "INTERRUPTED") Amber else MaterialTheme.colorScheme.primary) }
                                    Text("${formatTime(session.startedWall)} · ${if (session.mode == "LAB") "实测" else "监测"} · ${session.id.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("JSON", "PDF", "PNG").forEach { format -> OutlinedButton(onClick = { export(session.id, format) }, enabled = !exportBusy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(17.dp)); Spacer(Modifier.width(3.dp)); Text(format, fontSize = 12.sp) } } }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("设置") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("外观", fontWeight = FontWeight.SemiBold)
                listOf("跟随系统", "浅色", "深色").forEachIndexed { i, name -> Row(Modifier.fillMaxWidth().clickable { darkPreference = i; prefs.edit().putInt("theme", i).apply() }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = darkPreference == i, onClick = { darkPreference = i; prefs.edit().putInt("theme", i).apply() }); Text(name) } }
                Hairline()
                SettingRow("关于考境哨兵", "作者、项目地址与联系方式", Icons.Outlined.Info) { settings = false; about = true }
                Text("数据保存在本机。音视频检测只保存统计结果，原始画面与录音不落盘。报告由使用者主动导出。", style = MaterialTheme.typography.bodySmall)
                Text("异常记录需人工复核。其他设备拍摄和本机无法访问的行为不在检测覆盖范围内。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { settings = false }) { Text("完成") } })
        if (onlineDisclosure) AlertDialog(onDismissRequest = { onlineDisclosure = false }, title = { Text("在线网络检查") }, text = { Text("开启后向 ipify 发起 HTTPS 请求，取得当前出口 IP 并在本机比较变化。检测记录、应用活动与音视频不会发送到该服务。") }, confirmButton = { TextButton(onClick = { online = true; onlineDisclosure = false }) { Text("开启") } }, dismissButton = { TextButton(onClick = { onlineDisclosure = false }) { Text("取消") } })
        verification?.let { result -> AlertDialog(onDismissRequest = { verification = null }, title = { Text("报告核验") }, text = { Text(result, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton(onClick = { verification = null }) { Text("关闭") } }) }
        if (appPicker != 0) AppPicker(multiple = appPicker == 2, selected = if (appPicker == 2) allowed else setOf(target), onDismiss = { appPicker = 0 }, onSelect = { pkg, name -> if (appPicker == 1) { target = pkg; targetLabel = name; appPicker = 0 } else allowed = if (pkg in allowed) allowed - pkg else allowed + pkg })
        selectedIncident?.let { incident -> AlertDialog(onDismissRequest = { selectedIncident = null }, title = { Text(incident.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(incident.explanation); Text("规则 ${incident.ruleId} · ${formatTime(incident.wallTime)}", style = MaterialTheme.typography.labelSmall); Text("事件引用：${incident.eventIdsJson}", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { selectedIncident = null }) { Text("关闭") } }) }
        selectedEvent?.let { event -> AlertDialog(onDismissRequest = { selectedEvent = null }, title = { Text(event.summary) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("${formatTime(event.wallTime)} · ${event.source}"); Text(if (event.isTest) "实测事件" else "监测事件", color = MaterialTheme.colorScheme.primary); Text(event.detail, style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { selectedEvent = null }) { Text("关闭") } }) }
    }
}

@Composable
private fun StatusBand(session: SessionEntity?, elapsed: Long, incidentCount: Int) {
    val running = session != null
    val statusColor = if (incidentCount > 0 && running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (running) (if (session?.mode == "LAB") "实测正在进行" else "监测正在进行") else "准备开始检测", style = MaterialTheme.typography.titleLarge)
                Text(if (running) session?.label.orEmpty() else "先检查权限，再建立会话", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (running) Icons.Outlined.Sensors else Icons.Outlined.Shield, null, Modifier.size(32.dp), tint = statusColor)
        }
        if (running) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${formatDuration(elapsed)} · ${session?.id?.take(8)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (incidentCount > 0) "$incidentCount 条待复核" else "当前无已记录异常", style = MaterialTheme.typography.labelMedium, color = statusColor)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, accent: Color? = null) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, color = accent ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String, image: ImageVector, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(image, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun EmptyLine(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProbeRow(probe: ProbeEntity) {
    var expanded by remember(probe.id) { mutableStateOf(false) }
    val summary = remember(probe.detail) { readableProbeDetail(probe) }
    val expandable = probe.detail.length > 72 || summary != probe.detail
    val statusColor = when (probe.status) {
        "ERROR", "STALE" -> MaterialTheme.colorScheme.error
        "LIMITED", "UNSUPPORTED", "PERMISSION_REQUIRED" -> MaterialTheme.colorScheme.secondary
        "ACTIVE", "AVAILABLE" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier).padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(probe.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            StatusLabel(probeStatus(probe.status), statusColor)
            if (expandable) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起检测详情" else "展开检测详情", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (expanded) probe.detail else summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        Hairline(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusLabel(label: String, color: Color) { Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }

@Composable
private fun IncidentRow(incident: IncidentEntity, onClick: () -> Unit) {
    val color = if (incident.severity >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Flag, null, Modifier.size(20.dp).padding(top = 2.dp), tint = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(incident.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(incident.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTime(incident.wallTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EventRow(event: EventEntity, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text(SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(event.wallTime)), Modifier.width(56.dp).padding(top = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${readableSource(event.source)} · ${if (event.isTest) "实测" else "监测"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hairline()
    }
}

@Composable
private fun SettingRow(title: String, value: String, image: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(image, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChecked)
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, detail: String, onClick: () -> Unit) {
    val color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.AdminPanelSettings, null, Modifier.size(22.dp), tint = color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(if (granted) "已授权" else "去设置", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

private fun readableSource(source: String): String = when (source) {
    "USAGE_STATS" -> "应用活动"
    "ACCESSIBILITY", "ACCESSIBILITY_SNAPSHOT" -> "窗口信息"
    "ACTIVITY" -> "本机界面"
    "SYSTEM" -> "系统状态"
    "SERVICE" -> "监测服务"
    "NETWORK" -> "网络状态"
    "NATIVE_LAB" -> "原生答题区"
    "WEBVIEW" -> "浏览器实测"
    "USER" -> "主动操作"
    else -> source
}

private fun readableProbeDetail(probe: ProbeEntity): String = runCatching {
    val detail = probe.detail
    when (probe.id) {
        "accessibility.services" -> "系统当前报告 ${JSONArray(detail).length()} 个已启用的辅助服务"
        "environment.displays" -> JSONArray(detail).let { displays -> "系统当前报告 ${displays.length()} 个显示接口，其中 ${(0 until displays.length()).count { !displays.getJSONObject(it).optBoolean("default") }} 个为非默认显示接口" }
        "environment.debugger" -> org.json.JSONObject(detail).let { "调试器${if (it.optBoolean("connected")) "已连接" else "未连接"} · ${if (it.optBoolean("waiting")) "正在等待调试" else "当前未等待调试"}" }
        "environment.developer" -> org.json.JSONObject(detail).let { "开发者选项${if (it.optBoolean("developerOptions")) "已开启" else "未开启"} · USB 调试${if (it.optBoolean("adbEnabled")) "已开启" else "未开启"}" }
        "environment.emulator" -> org.json.JSONObject(detail).optJSONArray("hints").let { hints -> if (hints != null && hints.length() > 0) (0 until hints.length()).joinToString("；") { hints.getString(it) } else "未发现常见模拟器标记；此结果仅供环境复核" }
        "environment.root" -> org.json.JSONObject(detail).let { val count = it.optJSONArray("visibleIndicators")?.length() ?: 0; if (count > 0 || it.optBoolean("testKeys")) "发现 $count 项可见权限修改迹象${if (it.optBoolean("testKeys")) "及测试签名标记" else ""}；需要进一步复核" else "未发现可见 Root 迹象；无法排除隐藏或修改后的环境" }
        "environment.network" -> org.json.JSONObject(detail).let { info -> val transports = info.optJSONArray("transports"); val names = if (transports != null) (0 until transports.length()).joinToString(" / ") { transports.getString(it) } else ""; "${if (info.optBoolean("connected")) names.ifBlank { "网络已连接" } else "网络未连接"} · ${if (info.optBoolean("proxyConfigured")) "检测到代理配置" else "未检测到代理配置"}" }
        "environment.audio" -> org.json.JSONObject(detail).let { "可用输入 ${it.optJSONArray("inputs")?.length() ?: 0} 个 · 输出 ${it.optJSONArray("outputs")?.length() ?: 0} 个；不代表其他应用的占用状态" }
        "environment.cameras" -> org.json.JSONObject(detail).let { "系统提供 ${it.optInt("availableInterfaceCount")} 个摄像头接口；接口数量不等于物理镜头数量" }
        "environment.screen" -> org.json.JSONObject(detail).let { "屏幕${if (it.optBoolean("interactive")) "可交互" else "不可交互"} · ${if (it.optBoolean("locked")) "已锁定" else "已解锁"}" }
        "permission.own_overlay" -> org.json.JSONObject(detail).let { "本 App 悬浮窗${if (it.optBoolean("granted")) "已授权" else "未授权"}；其他应用的授权状态受系统限制" }
        else -> detail
    }
}.getOrDefault(probe.detail)

@Composable
private fun AppPicker(multiple: Boolean, selected: Set<String>, onDismiss: () -> Unit, onSelect: (String, String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { PermissionTools.launchableApps(context).map { it.packageName to it.label } }.let { listOf(context.packageName to "内置实测考场") + it.filterNot { item -> item.first == context.packageName } } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (multiple) "允许应用" else "考试应用") }, text = {
        Column { OutlinedTextField(query, { query = it }, placeholder = { Text("搜索应用") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) { apps.filter { it.second.contains(query, true) || it.first.contains(query, true) }.forEach { (pkg, name) -> Row(Modifier.fillMaxWidth().clickable { onSelect(pkg, name) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { if (multiple) Checkbox(pkg in selected, { onSelect(pkg, name) }) else RadioButton(pkg in selected, { onSelect(pkg, name) }); Column(Modifier.weight(1f)) { Text(name); Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

private fun probeStatus(status: String): String = when (status) { "ACTIVE" -> "监测中"; "AVAILABLE" -> "可用"; "LIMITED" -> "受限"; "UNSUPPORTED" -> "不支持"; "PERMISSION_REQUIRED" -> "未授权"; "ERROR" -> "异常"; "STALE" -> "数据过期"; "DISABLED" -> "未开启"; else -> "待检查" }
private fun probeOrder(status: String): Int = when (status) { "ERROR", "STALE" -> 0; "PERMISSION_REQUIRED" -> 1; "LIMITED", "UNSUPPORTED" -> 2; "ACTIVE" -> 3; else -> 4 }
private fun sessionStatus(status: String): String = when (status) { "ACTIVE" -> "进行中"; "ENDED" -> "已结束"; "INTERRUPTED" -> "已中断"; else -> status }
private fun formatTime(wall: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(wall))
private fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
