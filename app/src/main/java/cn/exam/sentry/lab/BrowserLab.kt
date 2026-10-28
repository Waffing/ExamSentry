package cn.exam.sentry.lab

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cn.exam.sentry.core.SentryRuntime
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun BrowserLab(sessionId: String?) {
    val context = LocalContext.current
    val pages = remember(sessionId) { BrowserPages(context, sessionId) }
    var current by remember(sessionId) { mutableIntStateOf(0) }
    var secondOpen by remember(sessionId) { mutableStateOf(false) }
    DisposableEffect(pages) { onDispose { pages.destroy() } }
    Column(Modifier.fillMaxWidth().height(540.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
            Text("浏览器专项", modifier = Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { secondOpen = true; current = 1 }) {
                Icon(Icons.Outlined.Add, contentDescription = "打开对照页")
            }
            IconButton(onClick = { pages.reload(current) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新运行当前页探针")
            }
            IconButton(onClick = { pages.shareCard() }) {
                Icon(Icons.Outlined.IosShare, contentDescription = "分享浏览器实测水印卡")
            }
        }
        if (secondOpen) {
            TabRow(selectedTabIndex = current) {
                Tab(selected = current == 0, onClick = { current = 0 }, text = { Text("实测页 A") })
                Tab(selected = current == 1, onClick = { current = 1 }, text = { Text("对照页 B") })
            }
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { localContext -> FrameLayout(localContext).also { pages.attach(it) } },
                modifier = Modifier.fillMaxSize(),
                update = { pages.show(if (secondOpen) 2 else 1, current) }
            )
        }
        Text(
            if (sessionId == null) "预览 · 当前操作未记入实测会话" else "本地实测 · ${sessionId.take(8)}",
            modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private class BrowserPages(private val context: Context, private val sessionId: String?) {
    private val watermarkSession = sessionId ?: UUID.randomUUID().toString()
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    private val views = mutableListOf<WebView>()
    private val facts = linkedMapOf<String, String>()
    private var container: FrameLayout? = null
    private val bridgeAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    fun attach(frame: FrameLayout) {
        container = frame
        show(1, 0)
    }

    fun show(count: Int, selected: Int) {
        val frame = container ?: return
        while (views.size < count) {
            val webView = makePage(UUID.randomUUID().toString())
            views += webView
            frame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        }
        views.forEachIndexed { index, view -> view.visibility = if (index == selected) View.VISIBLE else View.GONE }
    }

    private fun makePage(tabId: String): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowed(request.url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (allowed(request.url)) {
                    return loader.shouldInterceptRequest(request.url) ?: blocked()
                }
                return blocked()
            }
            override fun onPageFinished(view: WebView, url: String) {
                SentryRuntime.probe(
                    "browser_specialty", "离线浏览器专项", if (bridgeAvailable) "AVAILABLE" else "LIMITED",
                    if (bridgeAvailable) "本地页面已加载；专项事件可记录到实测会话" else "当前 WebView 不支持安全消息接口，页面仅能本地显示结果"
                )
            }
        }
        if (bridgeAvailable) {
            val limiter = MessageLimiter()
            WebViewCompat.addWebMessageListener(this, "SentryBridge", setOf(ORIGIN)) { _, message, sourceOrigin, mainFrame, _ ->
                if (!mainFrame || sourceOrigin.toString().trimEnd('/') != ORIGIN || message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                if (data.length > 4_096 || !limiter.accept()) return@addWebMessageListener
                val value = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
                val kind = value.optString("kind")
                val summary = summaries[kind] ?: return@addWebMessageListener
                val detail = cleanDetail(value.optJSONObject("detail") ?: JSONObject())
                detail.put("tabId", tabId)
                if (kind == "CANVAS_FINGERPRINT") facts["Canvas"] = detail.optString("hash", "无法获取")
                if (kind == "WEBGL_INFO") facts["WebGL"] = detail.optString("renderer", "无法获取")
                if (kind == "WEBRTC_RESULT") facts["WebRTC"] = "候选 ${detail.optInt("candidateCount")} / 私网地址 ${detail.optInt("privateIpCount")}"
                if (kind == "DUPLICATE_PAGE") facts["重复页面"] = "已发现同会话的另一页面"
                val active = SentryRuntime.state.value.activeSession
                if (sessionId != null && active?.id == sessionId && active.mode == "LAB") {
                    SentryRuntime.emit(kind, "WEBVIEW", summary, detail.toString(), severity = 0, isTest = true)
                }
            }
        }
        loadUrl(Uri.parse("$ORIGIN/assets/lab/index.html").buildUpon()
            .appendQueryParameter("sid", watermarkSession).appendQueryParameter("tab", tabId).build().toString())
    }

    fun reload(index: Int) { views.getOrNull(index)?.reload() }

    fun shareCard() {
        val bitmap = Bitmap.createBitmap(1_080, 1_160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(248, 250, 252))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        paint.color = Color.rgb(8, 127, 140)
        canvas.drawRect(0f, 0f, 1_080f, 22f, paint)
        paint.textSize = 54f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        canvas.drawText("考境哨兵", 72f, 120f, paint)
        paint.color = Color.rgb(31, 41, 55)
        paint.textSize = 36f
        canvas.drawText("浏览器专项实测卡", 72f, 190f, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 25f
        var y = 280f
        val lines = listOf(
            "会话：$watermarkSession",
            "时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}",
            "已打开页面：${views.size}",
            "${if (sessionId == null) "预览结果" else "实测会话"} · 所有专项在本机运行"
        ) + facts.map { "${it.key}：${it.value}" }
        for (line in lines) {
            var remaining = line
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, 936f, null).coerceAtLeast(1)
                canvas.drawText(remaining.substring(0, count), 72f, y, paint)
                remaining = remaining.substring(count)
                y += 44f
            }
            y += 18f
        }
        paint.color = Color.rgb(100, 116, 139)
        paint.textSize = 24f
        canvas.drawText("专项结果仅反映本次页面可观察信号", 72f, 1_070f, paint)
        val directory = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(directory, "browser-card-${UUID.randomUUID()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("浏览器实测卡", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享实测卡"))
    }

    fun destroy() {
        views.forEach { view ->
            view.stopLoading()
            if (bridgeAvailable) WebViewCompat.removeWebMessageListener(view, "SentryBridge")
            container?.removeView(view)
            view.destroy()
        }
        views.clear()
        container = null
        SentryRuntime.probe("browser_specialty", "离线浏览器专项", "DISABLED", "实测页面已关闭")
    }

    private fun cleanDetail(raw: JSONObject): JSONObject = JSONObject().apply {
        for (key in detailKeys) {
            when (val value = raw.opt(key)) {
                is Boolean -> put(key, value)
                is Number -> if (value.toDouble().isFinite()) put(key, value.toDouble().coerceIn(-1_000_000.0, 1_000_000.0))
                is String -> if (key != "wmSession" || value == watermarkSession) put(key, value.take(256))
            }
        }
    }

    private fun allowed(uri: Uri): Boolean = uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
        uri.port in listOf(-1, 443) && uri.userInfo == null && uri.path.orEmpty().startsWith("/assets/lab/")

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private class MessageLimiter {
        private var since = SystemClock.elapsedRealtime()
        private var count = 0
        fun accept(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - since >= 60_000) { since = now; count = 0 }
            return ++count <= 180
        }
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        private val detailKeys = setOf("len", "hash", "renderer", "vendor", "version", "maxTexture", "candidateCount", "privateIpCount", "mdnsCount", "supported", "width", "height", "scale", "scrollY", "visible", "focused", "shortcut", "peerTab", "matches", "found", "wmSession", "method", "feature", "answerChanges", "dwellMs", "firstAnswerMs", "changed", "fullScreen", "dpr", "screenWidth", "screenHeight", "cores", "platform", "userAgent", "orientation")
        private val summaries = mapOf(
            "BROWSER_READY" to "离线浏览器探针已就绪", "CANVAS_FINGERPRINT" to "已取得 Canvas 渲染摘要",
            "BROWSER_DEVICE" to "已取得浏览器运行环境参数", "BROWSER_ORIENTATION" to "浏览器屏幕方向变化",
            "WEBGL_INFO" to "已取得 WebGL 渲染信息", "WEBRTC_RESULT" to "WebRTC 本地候选检测已完成",
            "VIEWPORT_CHANGED" to "浏览器可视区域变化", "WEB_ZOOM" to "浏览器缩放变化",
            "BROWSER_SCROLL" to "浏览器页面滚动", "BROWSER_FOCUS" to "浏览器焦点变化",
            "BROWSER_VISIBILITY" to "浏览器页面可见性变化", "COPY" to "实测页发生复制",
            "PASTE" to "实测页发生粘贴", "SELECTION" to "实测页发生文本选择",
            "LONG_PRESS" to "实测页出现长按菜单", "DEBUG_SHORTCUT" to "实测页收到调试快捷键",
            "DUPLICATE_PAGE" to "发现同一实测会话的另一页面", "WATERMARK_CHECK" to "已完成本地水印核验",
            "BROWSER_ANSWER" to "浏览器实测区选项变化", "BROWSER_SUBMIT" to "浏览器实测区提交",
            "BROWSER_FULL_SCREEN" to "浏览器全屏状态变化", "FEATURE_UNAVAILABLE" to "浏览器专项接口受限"
        )
    }
}
