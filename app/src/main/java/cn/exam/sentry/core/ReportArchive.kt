package cn.exam.sentry.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class ReportBundle(val session: SessionEntity, val events: List<EventEntity>, val incidents: List<IncidentEntity>, val coverage: List<CoverageEntity>)

internal fun ProbeEntity.asJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("status", status).put("detail", detail).put("updatedWall", updatedWall)

private fun SessionEntity.asJson(): JSONObject = JSONObject()
    .put("id", id).put("label", label).put("mode", mode).put("targetPackage", targetPackage)
    .put("allowedPackages", JSONArray(allowedPackagesJson)).put("onlineCheck", onlineCheck)
    .put("startedWall", startedWall).put("startedElapsed", startedElapsed)
    .put("lastHeartbeatWall", lastHeartbeatWall).put("lastHeartbeatElapsed", lastHeartbeatElapsed)
    .put("endedWall", endedWall ?: JSONObject.NULL).put("status", status)

private fun EventEntity.asJson(): JSONObject = JSONObject()
    .put("id", id).put("sessionId", sessionId).put("seq", seq).put("wallTime", wallTime).put("elapsedTime", elapsedTime)
    .put("kind", kind).put("source", source).put("summary", summary).put("detail", detail)
    .put("severity", severity).put("isTest", isTest).put("previousHash", previousHash).put("hash", hash)

private fun IncidentEntity.asJson(): JSONObject = JSONObject()
    .put("id", id).put("sessionId", sessionId).put("ruleId", ruleId).put("title", title).put("explanation", explanation)
    .put("severity", severity).put("eventIds", JSONArray(eventIdsJson)).put("wallTime", wallTime)

internal object ReportArchive {
    private const val KEY_ALIAS = "exam-sentry-report-v1"
    private const val TRUST_NOTE = "签名与摘要链用于核验导出记录。公钥指纹须与已知设备核对；本地报告不能证明设备从未被篡改，也不能覆盖被强停、卸载后的行为。"
    private const val FINDING_NOTE = "异常记录与本地模型输出仅供复核，不等于作弊结论。"

    fun export(context: Context, bundle: ReportBundle, format: String): File {
        require(format.uppercase(Locale.ROOT) in setOf("JSON", "PDF", "PNG")) { "报告格式无效。" }
        require(EvidenceHash.verify(bundle.events)) { "事件摘要链不完整，停止导出。" }
        val payload = JSONObject().put("schemaVersion", 1).put("rulesVersion", BehaviorRules.VERSION)
            .put("appVersion", "0.1.0").put("exportedWall", System.currentTimeMillis())
            .put("session", bundle.session.asJson()).put("events", JSONArray(bundle.events.map { it.asJson() }))
            .put("incidents", JSONArray(bundle.incidents.map { it.asJson() }))
            .put("coverage", JSONArray(bundle.coverage.map { JSONObject().put("phase", it.phase).put("capturedWall", it.capturedWall).put("probes", JSONArray(it.probesJson)) }))
            .put("chainHead", bundle.events.lastOrNull()?.hash ?: EvidenceHash.GENESIS)
            .put("eventCount", bundle.events.size).put("interpretation", FINDING_NOTE).put("trustLimitations", TRUST_NOTE)
        val body = canonical(payload).toByteArray(Charsets.UTF_8)
        val keyStore = keyStore()
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKeyPair()
            }
        }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update(body) }.sign()
        val digest = EvidenceHash.sha256(body)
        val fingerprint = EvidenceHash.sha256(publicKey)
        val archive = JSONObject().put("payload", payload).put("integrity", JSONObject()
            .put("algorithm", "SHA256withECDSA").put("canonicalization", "sorted-json-v1")
            .put("payloadSha256", digest).put("keyFingerprint", fingerprint)
            .put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP)))
        val directory = File(context.cacheDir, "reports").apply { check(mkdirs() || isDirectory) { "无法创建报告目录。" } }
        val stem = "sentry-${bundle.session.id}-${System.currentTimeMillis()}"
        val json = File(directory, "$stem.json")
        json.writeText(archive.toString(2), Charsets.UTF_8)
        return when (format.uppercase(Locale.ROOT)) {
            "JSON" -> json
            "PDF" -> File(directory, "$stem.pdf").also { writePdf(it, bundle, digest, fingerprint, json.name) }
            "PNG" -> File(directory, "$stem.png").also { writeCard(it, bundle, digest) }
            else -> error("报告格式无效。")
        }
    }

    fun verify(file: File): String = try {
        require(file.length() in 1..52_428_800) { "报告文件大小无效。" }
        val archive = JSONObject(file.readText(Charsets.UTF_8))
        val payload = archive.getJSONObject("payload")
        val integrity = archive.getJSONObject("integrity")
        require(payload.getInt("schemaVersion") == 1) { "报告版本不受支持。" }
        require(integrity.getString("algorithm") == "SHA256withECDSA" && integrity.getString("canonicalization") == "sorted-json-v1") { "签名格式不受支持。" }
        val body = canonical(payload).toByteArray(Charsets.UTF_8)
        require(EvidenceHash.sha256(body) == integrity.getString("payloadSha256")) { "报告内容摘要不一致。" }
        val publicBytes = Base64.decode(integrity.getString("publicKey"), Base64.NO_WRAP)
        val fingerprint = EvidenceHash.sha256(publicBytes)
        require(fingerprint == integrity.getString("keyFingerprint")) { "公钥指纹不一致。" }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        val signature = Base64.decode(integrity.getString("signature"), Base64.NO_WRAP)
        require(Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(body); verify(signature) }) { "数字签名无效。" }
        val array = payload.getJSONArray("events")
        val sessionId = payload.getJSONObject("session").getString("id")
        val events = (0 until array.length()).map { index -> eventFromJson(array.getJSONObject(index)) }
        require(events.all { it.sessionId == sessionId }) { "事件会话编号不一致。" }
        require(payload.getInt("eventCount") == events.size && EvidenceHash.verify(events)) { "事件序号或摘要链被修改。" }
        require(payload.getString("chainHead") == (events.lastOrNull()?.hash ?: EvidenceHash.GENESIS)) { "摘要链尾不一致。" }
        val local = keyStore().getCertificate(KEY_ALIAS)?.publicKey?.encoded?.let { EvidenceHash.sha256(it) }
        val source = if (local == fingerprint) "签名公钥与本机导出密钥一致。" else "签名公钥来自报告，来源尚未核对。"
        "内容摘要、事件链与数字签名有效。\n$source\n公钥指纹：$fingerprint\n$TRUST_NOTE"
    } catch (error: Exception) {
        "核验失败：${if (error is IllegalArgumentException) error.message else "文件格式或签名无法解析。"}"
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    internal fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> error("报告包含不支持的数据类型。")
    }

    private fun eventFromJson(value: JSONObject) = EventEntity(
        value.getString("id"), value.getString("sessionId"), value.getLong("seq"), value.getLong("wallTime"), value.getLong("elapsedTime"),
        value.getString("kind"), value.getString("source"), value.getString("summary"), value.getString("detail"),
        value.getInt("severity"), value.getBoolean("isTest"), value.getString("previousHash"), value.getString("hash"),
    )

    private fun time(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE).format(Date(value))
    private fun status(value: String): String = when (value) { "ACTIVE" -> "进行中"; "ENDED" -> "已结束"; "INTERRUPTED" -> "监测中断"; else -> value }
    private fun probeStatus(value: String): String = when (value) {
        "ACTIVE", "AVAILABLE" -> "可用"; "LIMITED" -> "受限"; "UNSUPPORTED" -> "系统不支持"; "PERMISSION_REQUIRED" -> "未授权"
        "ERROR" -> "异常"; "STALE" -> "待重新检测"; "DISABLED" -> "未开启"; else -> value
    }

    private fun writePdf(file: File, bundle: ReportBundle, digest: String, fingerprint: String, jsonName: String) {
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document)
            writer.line("考境哨兵", 25f, Color.rgb(8, 127, 140))
            writer.line("考试环境检测报告", 18f)
            writer.line(bundle.session.label, 15f)
            writer.line("会话 ${bundle.session.id}")
            writer.line("${if (bundle.session.mode == "LAB") "实测会话" else "监测会话"} · ${status(bundle.session.status)}")
            writer.line("开始：${time(bundle.session.startedWall)}")
            writer.line("结束：${bundle.session.endedWall?.let(::time) ?: "尚未结束，此报告为实时快照"}")
            writer.line("目标应用：${bundle.session.targetPackage}")
            writer.line("规则版本：${BehaviorRules.VERSION} · ${bundle.events.size} 条事件 · ${bundle.incidents.size} 条复核线索")
            writer.line(FINDING_NOTE, 11f, Color.rgb(148, 94, 15))
            writer.heading("监测覆盖范围")
            bundle.coverage.forEach { snapshot ->
                writer.line("${snapshot.phase} · ${time(snapshot.capturedWall)}", 11f, Color.rgb(8, 127, 140))
                val probes = JSONArray(snapshot.probesJson)
                if (probes.length() == 0) writer.line("尚无探针结果；不能据此判定环境正常。")
                for (i in 0 until probes.length()) {
                    val probe = probes.getJSONObject(i)
                    writer.line("${probe.optString("label")}：${probeStatus(probe.optString("status"))} · ${probe.optString("detail")}", 10f)
                }
            }
            writer.heading("关联线索")
            if (bundle.incidents.isEmpty()) writer.line("未产生关联线索。请同时查看检测覆盖范围和中断记录。")
            bundle.incidents.take(60).forEach { finding ->
                writer.line("${time(finding.wallTime)} · ${finding.title}", 12f, if (finding.severity >= 2) Color.rgb(173, 49, 49) else Color.rgb(148, 94, 15))
                writer.line(finding.explanation, 10f)
                writer.line("规则 ${finding.ruleId} · 关联事件 ${finding.eventIdsJson}", 9f, Color.DKGRAY)
            }
            if (bundle.incidents.size > 60) writer.line("PDF 展示前 60 条线索；完整记录见配套 JSON。")
            writer.heading("事件时间线")
            writer.line("PDF 展示最近 150 条事件；完整记录和签名保存在配套 JSON。", 10f)
            bundle.events.takeLast(150).forEach { event ->
                writer.line("#${event.seq} ${time(event.wallTime)} ${if (event.isTest) "[实测]" else "[监测]"}", 9f, Color.DKGRAY)
                writer.line("${event.summary} · ${event.source}", 11f)
            }
            writer.heading("完整性核验")
            writer.line("配套签名文件：$jsonName", 10f)
            writer.line("内容 SHA-256：$digest", 9f)
            writer.line("公钥指纹：$fingerprint", 9f)
            writer.line("PDF 和 PNG 是展示摘要；请使用原始 JSON 进行签名核验。", 10f)
            writer.line(TRUST_NOTE, 10f)
            writer.finish()
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
    }

    private fun writeCard(file: File, bundle: ReportBundle, digest: String) {
        val bitmap = Bitmap.createBitmap(1080, 1520, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(247, 249, 250))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(17, 43, 57)
            canvas.drawRect(0f, 0f, 1080f, 340f, paint)
            fun text(value: String, x: Float, y: Float, size: Float, color: Int = Color.rgb(17, 43, 57), bold: Boolean = false) {
                paint.textSize = size
                paint.color = color
                paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                canvas.drawText(value, x, y, paint)
            }
            fun wrapped(value: String, y: Float, size: Float, maxLines: Int = 3, color: Int = Color.rgb(70, 87, 95)): Float {
                paint.textSize = size
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                var remaining = value
                var position = y
                repeat(maxLines) { index ->
                    if (remaining.isEmpty()) return position
                    val count = paint.breakText(remaining, true, 952f, null).coerceAtLeast(1)
                    val line = remaining.take(count)
                    remaining = remaining.drop(count)
                    text(if (index == maxLines - 1 && remaining.isNotEmpty()) line.dropLast(1) + "…" else line, 64f, position, size, color)
                    position += size * 1.5f
                }
                return position
            }
            text("考境哨兵", 64f, 105f, 54f, Color.WHITE, true)
            text("会话结果", 64f, 178f, 36f, Color.rgb(162, 227, 221))
            text("${status(bundle.session.status)} · ${if (bundle.session.mode == "LAB") "实测" else "监测"}", 64f, 272f, 29f, Color.WHITE)
            val countText = bundle.incidents.size.toString()
            paint.textSize = 92f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val counterSize = 92f * (320f / paint.measureText(countText)).coerceAtMost(1f)
            text(countText, 64f, 480f, counterSize, Color.rgb(8, 127, 140), true)
            text("条待复核线索", 420f, 470f, 34f)
            text("${bundle.events.size} 条事件 · 规则 ${BehaviorRules.VERSION}", 64f, 540f, 29f)
            var y = wrapped(bundle.session.label, 625f, 34f, 2)
            y = wrapped("开始：${time(bundle.session.startedWall)}", y + 18f, 26f, 1)
            y = wrapped("目标：${bundle.session.targetPackage}", y + 10f, 26f, 2)
            paint.color = Color.rgb(219, 229, 231)
            canvas.drawRect(64f, y + 10f, 1016f, y + 12f, paint)
            y += 70f
            if (bundle.incidents.isEmpty()) {
                y = wrapped("未产生关联线索。请结合检测覆盖范围和监测中断情况复核。", y, 29f, 3)
            } else {
                bundle.incidents.take(3).forEach { finding ->
                    y = wrapped("· ${finding.title}", y, 29f, 2, Color.rgb(148, 94, 15)) + 12f
                }
            }
            wrapped(FINDING_NOTE, 1140f, 27f, 2)
            text("会话 ${bundle.session.id.take(8)}", 64f, 1280f, 24f, Color.rgb(70, 87, 95))
            text("SHA-256 ${digest.take(32)}", 64f, 1330f, 22f, Color.rgb(70, 87, 95))
            text(digest.drop(32), 64f, 1365f, 22f, Color.rgb(70, 87, 95))
            text("完整事件、覆盖范围和签名见配套 JSON 报告", 64f, 1450f, 25f, Color.rgb(8, 127, 140))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private class PdfWriter(private val document: PdfDocument) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = 48f

        fun heading(value: String) { y += 10f; line(value, 16f, Color.rgb(8, 127, 140)); y += 3f }

        fun line(value: String, size: Float = 11f, color: Int = Color.rgb(25, 45, 55)) {
            paint.textSize = size
            paint.color = color
            val sections = value.split('\n')
            for (section in sections) {
                var remaining = section.ifEmpty { " " }
                while (remaining.isNotEmpty()) {
                    if (page == null || y + size * 1.4f > 790f) newPage()
                    paint.textSize = size
                    paint.color = color
                    val count = paint.breakText(remaining, true, 519f, null).coerceAtLeast(1)
                    page?.canvas?.drawText(remaining.take(count), 38f, y, paint)
                    remaining = remaining.drop(count)
                    y += size * 1.5f
                }
                y += 3f
            }
        }

        private fun newPage() {
            finish()
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            y = 48f
        }

        fun finish() {
            val current = page ?: return
            paint.textSize = 9f
            paint.color = Color.GRAY
            current.canvas.drawText("考境哨兵 · 第 $pageNumber 页 · 本地检测记录", 38f, 818f, paint)
            document.finishPage(current)
            page = null
        }
    }
}
