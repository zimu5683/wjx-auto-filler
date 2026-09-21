package com.wjx.autofill.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Release 中可用于升级的信息。 */
data class UpdateInfo(
    val tagName: String,
    val version: String,
    val body: String,
    val htmlUrl: String,
    val apkUrl: String = "",
)

/** 下载 / 安装过程事件；回调一律在主线程触发。 */
sealed class UpdateEvent {
    data class Progress(val percent: Int) : UpdateEvent()
    data class Ready(val apk: File) : UpdateEvent()
    data class Failed(val reason: String) : UpdateEvent()
    data class NeedInstallPermission(val message: String) : UpdateEvent()
}

/**
 * 应用内自动更新：只负责「检查 + 下载 + 交给系统安装器」，不静默安装。
 *
 * 流程与 yikou-light-food 的 AppUpdater.kt 对齐：
 *   GitHub Releases latest API -> tag 去 v -> 与 BuildConfig.VERSION_NAME 比较
 *   -> 遍历 assets 优先 arm64 / universal 的 .apk -> 没有 APK 资产视为非发布包不提示
 *   -> 下载到 cacheDir/updates/wjx-<version>.apk -> FileProvider + ACTION_VIEW 拉起安装器
 *   -> 异常回退打开 Release 网页
 *
 * 仓库：zimu5683/wjx-auto-filler
 */
object AppUpdater {
    const val DEFAULT_REPOSITORY = "zimu5683/wjx-auto-filler"

    private const val USER_AGENT = "wjx-auto-filler-android"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    // Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES（API 26）的字面量。
    // minSdk 24 下直接引用该常量字段会被 lint NewApi 判为 error，字面量值完全相同且不会误报。
    private const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 查询最新 Release；没有可用 APK 资产、版本不可比或网络失败都返回 null。 */
    fun checkForUpdate(
        currentVersion: String,
        repository: String = DEFAULT_REPOSITORY,
    ): UpdateInfo? {
        val payload = httpGetJson("https://api.github.com/repos/$repository/releases/latest")
            ?: return null
        val tag = payload.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val version = tag.removePrefix("v")
        if (!VersionCompare.isNewer(version, currentVersion)) return null
        val assets = payload.optJSONArray("assets") ?: return null
        var apkUrl = ""
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            val url = asset.optString("browser_download_url")
            // 优先明确的 arm64 包；没有则退回第一个 APK，便于手工下载通用包。
            if (name.contains("arm64") || name.contains("universal")) {
                apkUrl = url
                break
            }
            if (apkUrl.isEmpty()) apkUrl = url
        }
        // 没有 APK 资产的 Release 不是 Android 发布包，不弹「发现新版本」，
        // 避免用户点了却下载不到可安装文件。
        if (apkUrl.isBlank()) return null
        return UpdateInfo(
            tagName = tag,
            version = version,
            body = payload.optString("body"),
            htmlUrl = payload.optString("html_url"),
            apkUrl = apkUrl,
        )
    }

    /** Android 8+ 必须由用户为本应用开启「安装未知应用」；未开启返回 true。 */
    fun needsUnknownSourcesPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return !context.packageManager.canRequestPackageInstalls()
    }

    /** 跳到「安装未知应用」授权页；个别 ROM 无此页面时回退到应用详情页。 */
    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context.applicationContext
        try {
            appContext.startActivity(
                Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
            openAppDetailsSettings(appContext)
        }
    }

    /** 下载并拉起系统安装器；任何异常回退打开 Release 网页，不打断主流程。 */
    fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onEvent: (UpdateEvent) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        if (info.apkUrl.isBlank()) {
            openExternal(appContext, info.htmlUrl)
            post(onEvent, UpdateEvent.Failed("该版本没有可下载的安装包，已打开 Release 页面"))
            return
        }
        // 新设备适配：Android 8+ 未开启「安装未知应用」时先引导，避免下载完才发现装不上。
        if (needsUnknownSourcesPermission(appContext)) {
            openUnknownSourcesSettings(appContext)
            post(
                onEvent,
                UpdateEvent.NeedInstallPermission("请先允许「安装未知应用」，返回后重新点击更新"),
            )
            return
        }
        Thread {
            try {
                val directory = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val apk = File(directory, "wjx-${info.version}.apk")
                // 清理历史包，避免 cacheDir 堆积旧 APK
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && file.name != apk.name) file.delete()
                }
                val staging = File(directory, "wjx-${info.version}.apk.tmp")
                download(info.apkUrl, staging) { percent -> post(onEvent, UpdateEvent.Progress(percent)) }
                if (apk.exists()) apk.delete()
                if (!staging.renameTo(apk)) {
                    staging.copyTo(apk, overwrite = true)
                    staging.delete()
                }
                post(onEvent, UpdateEvent.Ready(apk))
                install(appContext, apk)
            } catch (t: Throwable) {
                post(onEvent, UpdateEvent.Failed(t.message ?: "更新包下载失败"))
                openExternal(appContext, info.htmlUrl)
            }
        }.start()
    }

    /** FileProvider + ACTION_VIEW 拉起系统安装器。 */
    fun install(context: Context, apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            openAppDetailsSettings(context)
        }
    }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("下载失败：HTTP $code")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt()
                            if (percent != lastPercent && percent % 5 == 0) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openExternal(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
            // 没有浏览器时静默失败；更新只是提示，不应打断主流程。
        }
    }

    private fun openAppDetailsSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
            // 连设置页都打不开时静默；不能因为更新链路崩掉主流程。
        }
    }

    private fun post(callback: (UpdateEvent) -> Unit, event: UpdateEvent) {
        mainHandler.post { callback(event) }
    }

    private fun httpGetJson(url: String): JSONObject? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
