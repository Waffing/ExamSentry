package cn.exam.sentry.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.exam.sentry.BuildConfig

object OpenSourceInfo {
    const val AUTHOR = "Waffing"
    const val REPOSITORY = "https://github.com/Waffing/ExamSentry"
    const val EMAIL = "203458615@qq.com"
    const val LICENSE = "MIT License"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("考境哨兵", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("考试防作弊检测", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("版本 ${BuildConfig.VERSION_NAME} · Android", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("自动记录考试环境与应用行为，关联异常线索，保留可复核的本地报告。", style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                Text("开源信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                AboutRow(Icons.Outlined.PersonOutline, "开源作者", OpenSourceInfo.AUTHOR) { open("https://github.com/${OpenSourceInfo.AUTHOR}") }
                AboutRow(Icons.Outlined.Code, "源代码", "Waffing / ExamSentry") { open(OpenSourceInfo.REPOSITORY) }
                AboutRow(Icons.Outlined.MailOutline, "联系邮箱", OpenSourceInfo.EMAIL) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${OpenSourceInfo.EMAIL}"))) }
                }
                AboutRow(Icons.Outlined.Balance, "开源许可", OpenSourceInfo.LICENSE) { open("${OpenSourceInfo.REPOSITORY}/blob/main/LICENSE") }
                AboutRow(Icons.Outlined.SystemUpdateAlt, "版本下载", "GitHub Releases") { open("${OpenSourceInfo.REPOSITORY}/releases") }
                AboutRow(Icons.Outlined.BugReport, "问题反馈", "GitHub Issues") { open("${OpenSourceInfo.REPOSITORY}/issues") }
                AboutRow(Icons.Outlined.LibraryBooks, "第三方组件", "组件许可与本地模型来源") { open("${OpenSourceInfo.REPOSITORY}/blob/main/THIRD_PARTY_NOTICES.md") }
                HorizontalDivider()
                Text("数据与检测范围", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("检测记录保存在本机，由使用者主动导出。相机与麦克风默认关闭，开启后只保留统计信息。在线网络检查由使用者单独开启。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("异常线索需要人工复核。截图与录屏回调仅覆盖系统提供的本 App 可见范围，其他设备拍摄等行为无法完整检测。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Copyright 2026 ${OpenSourceInfo.AUTHOR}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AboutRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp).heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, "打开$title", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
