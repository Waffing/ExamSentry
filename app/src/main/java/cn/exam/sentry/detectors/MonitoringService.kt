package cn.exam.sentry.detectors

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.exam.sentry.R
import cn.exam.sentry.core.SentryRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoring: Job? = null
    private var onlineJob: Job? = null
    private var intentionalStop = false
    private var sessionId = ""
    private var cursor = 0L
    private var lastWall = 0L
    private var lastElapsed = 0L
    private var usageClockValid = true
    private var lastInteractive: Boolean? = null
    private var lastUsagePermission: Boolean? = null
    private var lastHeartbeat = 0L
    private var lastScan = 0L
    private var lastIpCheck = 0L
    private var firstIpHash: String? = null
    private var lastIpHash: String? = null
    private val seenUsage = LinkedHashSet<String>()
    private val notifiedIncidents = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        SentryRuntime.init(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MONITOR, "持续监测", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "检测异常", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            intentionalStop = true
            SentryRuntime.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val active = SentryRuntime.state.value.activeSession
        if (active == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (sessionId.isNotBlank() && sessionId != active.id) {
            monitoring?.cancel()
            onlineJob?.cancel()
            seenUsage.clear()
            notifiedIncidents.clear()
            firstIpHash = null
            lastIpHash = null
            lastHeartbeat = 0
            lastScan = 0
            lastIpCheck = 0
            lastInteractive = null
            lastUsagePermission = null
        }
        val notification = monitoringNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_MONITOR, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_MONITOR, notification)
        } catch (error: RuntimeException) {
            SentryRuntime.probe("service.monitor", "持续监测服务", "ERROR", "前台服务启动失败：${error.javaClass.simpleName}")
            SentryRuntime.emit("MONITORING_INTERRUPTED", "SERVICE", "持续监测服务未能启动", JSONObject().put("error", error.javaClass.simpleName).toString(), 2)
            intentionalStop = true
            SentryRuntime.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitoring?.isActive != true) {
            intentionalStop = false
            sessionId = active.id
            cursor = active.startedWall
            usageClockValid = true
            lastWall = System.currentTimeMillis()
            lastElapsed = SystemClock.elapsedRealtime()
            val scanner = EnvironmentScanner(this)
            SentryRuntime.probe("service.monitor", "持续监测服务", "ACTIVE", "用户已启动；常驻通知开启，应用切换每秒检查，环境与心跳每 5 秒更新")
            SentryRuntime.emit("MONITORING_STARTED", "SERVICE", "持续监测服务已启动")
            monitoring = scope.launch {
                while (isActive) {
                    val session = SentryRuntime.state.value.activeSession
                    if (session == null || session.id != sessionId) {
                        intentionalStop = true
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    try {
                        val elapsed = SystemClock.elapsedRealtime()
                        val wall = System.currentTimeMillis()
                        detectClockChange(wall, elapsed)
                        pollUsage(wall, elapsed, session.startedWall, session.startedElapsed)
                        if (elapsed - lastHeartbeat >= 5_000) {
                            lastHeartbeat = elapsed
                            SentryRuntime.heartbeat()
                            SentryRuntime.probe("service.monitor", "持续监测服务", "ACTIVE", "监测服务运行中；最近心跳已更新。系统强停后无法继续记录。")
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_MONITOR, monitoringNotification())
                        }
                        if (elapsed - lastScan >= 5_000) {
                            lastScan = elapsed
                            scanner.scan()
                        }
                        if (session.onlineCheck && elapsed - lastIpCheck >= 30_000 && onlineJob?.isActive != true) {
                            lastIpCheck = elapsed
                            checkPublicIp(session.id)
                        } else if (!session.onlineCheck) {
                            SentryRuntime.probe("network.public_ip", "出口 IP 变化", "DISABLED", "在线网络检查未开启；未连接公网 IP 查询服务")
                        }
                        alertNewIncidents()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RuntimeException) {
                        SentryRuntime.probe("service.monitor", "持续监测服务", "ERROR", "本轮采集异常：${error.javaClass.simpleName}；下一轮继续检查")
                    }
                    delay(1_000)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun detectClockChange(wall: Long, elapsed: Long) {
        val drift = (wall - lastWall) - (elapsed - lastElapsed)
        if (abs(drift) > 3_000) {
            usageClockValid = false
            SentryRuntime.emit("CLOCK_CHANGED", "SYSTEM", "系统时间跳变；使用情况时间线暂停采信", JSONObject().put("wallClockDriftMs", drift).toString(), 1)
            SentryRuntime.probe("usage.foreground", "前台应用事件", "LIMITED", "检测到系统时间跳变，无法可靠对齐 UsageStats 时间戳；生命周期与已启用的窗口服务仍可提供线索，重新开始会话可重建基准")
        }
        lastWall = wall
        lastElapsed = elapsed
    }

    @Suppress("DEPRECATION")
    private fun pollUsage(wall: Long, elapsed: Long, sessionWall: Long, sessionElapsed: Long) {
        val interactive = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (lastInteractive != interactive) {
            lastInteractive = interactive
            SentryRuntime.emit(if (interactive) "SCREEN_AVAILABLE" else "SCREEN_LOCKED", "SYSTEM", if (interactive) "屏幕可交互且已解锁" else "屏幕关闭或锁定；前台采集可能不可用")
        }
        val granted = PermissionTools.usageGranted(this)
        if (lastUsagePermission == true && !granted) {
            SentryRuntime.emit("PERMISSION_REVOKED", "SYSTEM", "使用情况访问权限已撤销", JSONObject().put("key", "permission.usage").toString(), 2)
        }
        lastUsagePermission = granted
        if (!granted) {
            SentryRuntime.probe("usage.foreground", "前台应用事件", "PERMISSION_REQUIRED", "未授权使用情况访问；不会将未知前台应用显示为正常")
            return
        }
        if (!usageClockValid) return
        if (!interactive) {
            SentryRuntime.probe("usage.foreground", "前台应用事件", "LIMITED", "设备处于锁屏或非交互状态；前台应用状态未知")
            return
        }
        try {
            val events = getSystemService(UsageStatsManager::class.java).queryEvents(max(sessionWall, cursor - 2_000), wall + 1)
            if (events == null) {
                SentryRuntime.probe("usage.foreground", "前台应用事件", "LIMITED", "系统未返回使用情况事件，未取得前台状态")
                return
            }
            val event = UsageEvents.Event()
            var accepted = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.timeStamp < sessionWall || event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                val packageName = event.packageName ?: continue
                val key = "${event.timeStamp}|${event.eventType}|$packageName|${event.className}"
                if (!seenUsage.add(key)) continue
                while (seenUsage.size > 512) seenUsage.remove(seenUsage.first())
                val observedElapsed = elapsed - (wall - event.timeStamp)
                if (observedElapsed < sessionElapsed || observedElapsed > elapsed + 500) continue
                if (!PermissionTools.isTransientPackage(this, packageName)) {
                    ForegroundEvidence.offer(packageName, "USAGE_STATS", observedElapsed, event.timeStamp)
                    accepted++
                }
            }
            cursor = wall
            SentryRuntime.probe("usage.foreground", "前台应用事件", "ACTIVE", "已授权，正在查询本次会话内应用前台事件；最近一轮接收 $accepted 条，系统事件可能延迟")
        } catch (_: SecurityException) {
            SentryRuntime.probe("usage.foreground", "前台应用事件", "PERMISSION_REQUIRED", "系统拒绝使用情况查询；前台状态未知")
        }
    }

    private fun checkPublicIp(expectedSession: String) {
        onlineJob = scope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://api64.ipify.org?format=json").openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val ip = JSONObject(payload).getString("ip")
                require(ip.matches(Regex("[0-9a-fA-F:.]{3,64}")))
                val digest = MessageDigest.getInstance("SHA-256").digest("$expectedSession|$ip".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                if (!isActive || SentryRuntime.state.value.activeSession?.id != expectedSession) return@launch
                if (firstIpHash == null) {
                    firstIpHash = digest
                    SentryRuntime.emit("IP_BASELINE", "NETWORK", "已取得出口地址基线摘要", JSONObject().put("addressHash", digest).put("endpoint", "api64.ipify.org").toString())
                } else if (lastIpHash != digest) {
                    SentryRuntime.emit("IP_CHANGED", "NETWORK", "公网出口地址发生变化", JSONObject().put("previousHash", lastIpHash).put("currentHash", digest).put("baselineHash", firstIpHash).toString(), 1)
                }
                lastIpHash = digest
                SentryRuntime.probe("network.public_ip", "出口 IP 变化", "ACTIVE", "已完成在线查询，仅保存会话内地址摘要；地址变化可能来自正常网络切换")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive && SentryRuntime.state.value.activeSession?.id == expectedSession) {
                    SentryRuntime.probe("network.public_ip", "出口 IP 变化", "ERROR", "本次在线查询失败：${error.javaClass.simpleName}；出口地址状态未知，保留此前基线")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun alertNewIncidents() {
        val incidents = SentryRuntime.state.value.incidents.filter { it.sessionId == sessionId }
        for (incident in incidents) {
            if (!notifiedIncidents.add(incident.id) || incident.severity < 2 || !notificationsGranted()) continue
            getSystemService(NotificationManager::class.java).notify((incident.id.hashCode() and 0x3fffffff) + 100,
                NotificationCompat.Builder(this, CHANNEL_ALERTS).setSmallIcon(R.drawable.ic_sentry).setContentTitle("检测到需要复核的异常")
                    .setContentText(incident.title).setContentIntent(openAppIntent()).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        }
    }

    private fun notificationsGranted() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun monitoringNotification(): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, MonitoringService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_MONITOR).setSmallIcon(R.drawable.ic_sentry).setContentTitle("考境哨兵 · 监测中")
            .setContentText("应用切换与环境检测正在运行").setContentIntent(openAppIntent()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "结束监测", stop).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        SentryRuntime.emit("TASK_REMOVED", "SYSTEM", "检测界面任务被移除；持续监测是否保留取决于系统策略")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (!intentionalStop && SentryRuntime.state.value.activeSession?.id == sessionId) {
            SentryRuntime.emit("MONITORING_INTERRUPTED", "SERVICE", "持续监测服务已断开；之后的行为没有连续采集保证", severity = 2)
            SentryRuntime.probe("service.monitor", "持续监测服务", "ERROR", "服务已停止；下一次启动会识别日志缺口")
        } else {
            SentryRuntime.probe("service.monitor", "持续监测服务", "DISABLED", "监测服务已结束")
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "cn.exam.sentry.STOP_MONITORING"
        private const val CHANNEL_MONITOR = "sentry_monitor"
        private const val CHANNEL_ALERTS = "sentry_alerts"
        private const val NOTIFICATION_MONITOR = 41

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }
    }
}
