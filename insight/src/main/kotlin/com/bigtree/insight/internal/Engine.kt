package com.bigtree.insight.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bigtree.insight.Insight
import com.bigtree.insight.InsightConfig
import com.bigtree.insight.InsightListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object Engine {
    const val TAG = "Insight"

    val main: Handler = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "insight-io").apply { isDaemon = true }
    }

    @Volatile
    var listener: InsightListener? = null

    @Volatile
    var config: InsightConfig = InsightConfig.NONE

    lateinit var app: Application
        private set
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        }
    }

    lateinit var root: File
        private set
    lateinit var outbox: File
        private set
    lateinit var nativeDir: File
        private set

    val store = RecordStore()
    private val crumbs = ArrayDeque<String>()
    private val lock = Any()
    private var ready = false
    private var debuggable = false
    private var sessionId = ""
    private var appJson = JSONObject()
    private var deviceJson = JSONObject()

    fun install(context: Context) {
        val application = context.applicationContext as? Application ?: return
        if (!isMainProcess(application)) {
            return
        }
        var scheduleSignals = false
        synchronized(lock) {
            if (ready) {
                return
            }
            ready = true
            scheduleSignals = true
            app = application
            debuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            sessionId = "${android.os.Process.myPid()}-${SystemClock.elapsedRealtime()}"
            root = File(application.filesDir, "insight").apply { mkdirs() }
            outbox = File(root, "outbox").apply { mkdirs() }
            nativeDir = File(root, "native").apply { mkdirs() }
            appJson = buildAppJson()
            deviceJson = buildDeviceJson()
            application.registerActivityLifecycleCallbacks(LifecycleBridge)
            CrashReporter.install()
            StartupMonitor.arm(application)
            BlockWatch.start()
            io.execute {
                try {
                    ExitCollector.collect()
                    logPendingCount()
                    scheduleUpload()
                } catch (error: Throwable) {
                    Log.w(TAG, "collect previous process failed", error)
                }
            }
        }
        if (scheduleSignals) {
            main.post { installSignals() }
        }
    }

    fun configure(value: InsightConfig) {
        config = value
        if (ready) {
            scheduleUpload()
        }
    }

    fun installSignals() {
        if (!ready) {
            return
        }
        try {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            com.bigtree.insight.NativeBridge.install(nativeDir.absolutePath, abi)
        } catch (error: Throwable) {
            Log.w(TAG, "native crash hook skipped", error)
        }
    }

    fun breadcrumb(kind: String, name: String) {
        val line = "$kind $name"
        synchronized(crumbs) {
            crumbs.addLast(line)
            while (crumbs.size > 20) {
                crumbs.removeFirst()
            }
        }
        if (ready) {
            io.execute { persistCrumbs() }
        }
    }

    fun snapshotCrumbs(): JSONArray {
        val array = JSONArray()
        synchronized(crumbs) {
            crumbs.forEach { array.put(it) }
        }
        return array
    }

    fun finishSpan(name: String, beganElapsedMs: Long, extras: Map<String, String>) {
        val cost = SystemClock.elapsedRealtime() - beganElapsedMs
        val slow = cost >= config.slowTaskMs
        val summary = buildString {
            append("Insight task ")
            append(name)
            append(' ')
            append(cost)
            append("ms")
            if (slow) {
                append(" slow")
            }
        }
        val data = JSONObject()
            .put("name", name)
            .put("durationMs", cost)
            .put("slow", slow)
        if (extras.isNotEmpty()) {
            data.put("extras", JSONObject(extras))
        }
        emit("task", summary, data)
    }

    fun reportFullyDrawn(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        runCatching { activity.reportFullyDrawn() }
        StartupMonitor.onFullyDrawn(activity, now)
    }

    fun memory(reason: String) {
        if (!ready) {
            return
        }
        io.execute {
            val info = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(info)
            emit(
                "memory",
                "Insight memory $reason pss=${info.totalPss}KB",
                JSONObject()
                    .put("reason", reason)
                    .put("totalPssKb", info.totalPss)
                    .put("nativePssKb", info.nativePss)
                    .put("dalvikPssKb", info.dalvikPss),
            )
        }
    }

    fun envelope(
        type: String,
        data: JSONObject,
        atMillis: Long = System.currentTimeMillis(),
    ): JSONObject {
        return JSONObject()
            .put("type", type)
            .put("ts", atMillis)
            .put("time", formatTime(atMillis))
            .put("v", Insight.VERSION)
            .put("session", sessionId)
            .put("app", appJson)
            .put("device", deviceJson)
            .put("data", data)
    }

    fun emit(
        type: String,
        summary: String,
        data: JSONObject,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        val record = envelope(type, data, atMillis)
        val stamped = stamp(record.getString("time"), summary)
        if (shouldLog()) {
            Log.i(TAG, stamped)
        }
        val callback = listener
        if (callback != null) {
            main.post {
                runCatching { callback.onRecord(type, stamped) }
            }
        }
        if (!ready) {
            return
        }
        io.execute { store.write(outbox, record) }
    }

    fun writeSync(record: JSONObject): File? {
        if (!ready) {
            return null
        }
        return store.write(outbox, record)
    }

    fun scheduleUpload() {
        if (!ready) {
            return
        }
        val endpoint = config.endpoint ?: return
        io.execute {
            runCatching { Uploader.upload(endpoint, outbox) }
                .onFailure { Log.w(TAG, "upload failed", it) }
        }
    }

    fun pendingSummaries(): List<String> {
        if (!ready) {
            return emptyList()
        }
        val files = outbox.listFiles()?.filter { it.isFile } ?: return emptyList()
        val crashes = files.filter { file ->
            file.name.startsWith("java_crash") ||
                file.name.startsWith("native_crash") ||
                file.name.startsWith("anr")
        }.sortedByDescending { it.lastModified() }
        val rest = files.filter { it !in crashes }
            .sortedByDescending { it.lastModified() }
            .take(6)
        val lines = (crashes + rest).map { summarizeRecord(it) }
        lines.forEach { Log.i(TAG, it) }
        return lines
    }

    private fun summarizeRecord(file: File): String {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: return file.name
        val type = json.optString("type", file.name)
        val data = json.optJSONObject("data") ?: JSONObject()
        val body = when {
            type == "native_crash" -> {
                val frames = data.optString("signalReport")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("#") }
                    .take(6)
                    .joinToString("\n")
                val signal = data.optString("signal").ifEmpty { data.optString("reason") }
                if (frames.isEmpty()) "native_crash $signal" else "native_crash $signal\n$frames"
            }
            type.startsWith("java_crash") -> {
                val stack = data.optString("stack").lineSequence().take(6).joinToString("\n")
                val head = "java_crash ${data.optString("exception")}: ${data.optString("message")}"
                if (stack.isEmpty()) head else "$head\n$stack"
            }
            type == "anr" -> "anr ${data.optString("reason")}"
            type == "startup" -> "startup ${data.optString("kind")} ${data.optLong("durationMs")}ms ${data.optString("activity")}"
            type == "screen" -> "screen ${data.optString("name")} ${data.optLong("drawMs")}ms"
            type == "task" -> "task ${data.optString("name")} ${data.optLong("durationMs")}ms"
            type == "block" -> "block ${data.optLong("blockedMs")}ms"
            else -> type
        }
        val occurredAt = data.optLong("timestamp").takeIf { it > 1_000_000_000_000L }
            ?: json.optLong("ts").takeIf { it > 0 }
        val time = json.optString("time").takeIf { it.isNotEmpty() }
            ?: occurredAt?.let { formatTime(it) }
        return if (time.isNullOrEmpty()) body else stamp(time, body)
    }

    fun formatTime(millis: Long): String = timeFormat.get()!!.format(Date(millis))

    fun stamp(time: String, summary: String): String {
        val breakAt = summary.indexOf('\n')
        return if (breakAt < 0) {
            "$time $summary"
        } else {
            "$time ${summary.substring(0, breakAt)}\n${summary.substring(breakAt + 1)}"
        }
    }

    fun shouldLog(): Boolean {
        return if (config.debugSpecified) config.debug else debuggable
    }

    private fun logPendingCount() {
        val count = outbox.listFiles()?.size ?: 0
        if (count > 0) {
            Log.i(TAG, "Insight pending $count records at ${outbox.absolutePath}")
        }
    }

    private fun persistCrumbs() {
        val text = synchronized(crumbs) { crumbs.joinToString("\n") }
        runCatching {
            val file = File(root, "crumbs.txt")
            file.writeText(text)
        }
    }

    private fun buildAppJson(): JSONObject {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                app.packageManager.getPackageInfo(
                    app.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
        }.getOrNull()
        val versionCode = if (packageInfo == null) {
            0L
        } else if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return JSONObject()
            .put("id", app.packageName)
            .put("version", packageInfo?.versionName ?: "")
            .put("versionCode", versionCode)
            .put("process", processName(app))
    }

    private fun buildDeviceJson(): JSONObject {
        return JSONObject()
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("os", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
    }

    private fun isMainProcess(application: Application): Boolean {
        val name = processName(application)
        return name.isEmpty() || name == application.packageName
    }

    private fun processName(application: Application): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName()
        }
        val pid = android.os.Process.myPid()
        val manager = application.getSystemService(Application.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
    }
}

internal class RecordStore {
    private val seq = AtomicInteger()

    fun write(outbox: File, record: JSONObject): File {
        outbox.mkdirs()
        val type = record.optString("type", "event")
        val file = File(outbox, "$type-${System.currentTimeMillis()}-${seq.incrementAndGet()}.json")
        file.writeText(record.toString())
        trim(outbox)
        return file
    }

    private fun trim(outbox: File) {
        val files = outbox.listFiles()?.toMutableList() ?: return
        val disposable = files.filter { file ->
            val name = file.name
            !name.startsWith("java_crash") &&
                !name.startsWith("native_crash") &&
                !name.startsWith("anr")
        }.sortedBy { it.lastModified() }
        val overflow = disposable.size - 80
        if (overflow > 0) {
            disposable.take(overflow).forEach { it.delete() }
        }
    }
}
