package app.aemu.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val body: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val checksumsUrl: String?
)

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

object AppUpdateManager {
    // Releases are published by this fork, not by upstream: upstream has no APK built from this tree.
    private const val RELEASES_API = "https://api.github.com/repos/Knox-0x1-cmd/AEmulator/releases?per_page=100"
    const val GITHUB_REPO_URL = "https://github.com/Knox-0x1-cmd/AEmulator"

    /** A release APK is well under this; the cap stops a bad (or replaced) asset from filling the phone. */
    private const val MAX_APK_BYTES = 300L * 1024 * 1024
    private const val MAX_CHECKSUMS_BYTES = 64L * 1024
    private const val MAX_API_BYTES = 8L * 1024 * 1024

    /**
     * Запрос к GitHub API для получения последнего релиза с APK-файлом.
     * The asset is chosen for the installed flavour: app.aemu.v0003 takes the -v0003 APK, app.aemu the standard one.
     */
    suspend fun checkLatestRelease(context: Context): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(RELEASES_API)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "AEmulator-App")

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}")
                }

                val text = conn.inputStream.use { readLimited(it, MAX_API_BYTES) }
                val array = JSONArray(text)
                if (array.length() == 0) return@runCatching null

                val wantV0003 = context.packageName.endsWith(".v0003")
                var best: ReleaseInfo? = null
                for (i in 0 until array.length()) {
                    val rel = array.getJSONObject(i)
                    if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue

                    val tagName = rel.optString("tag_name", "").trim()
                    val versionName = tagName.removePrefix("v").removePrefix("V")
                    // build-<date> tags are CI artefacts, not versions the updater can compare.
                    if (versionName.isEmpty() || !versionName[0].isDigit()) continue
                    val title = rel.optString("name", tagName)
                    val body = rel.optString("body", "")

                    val assets = rel.optJSONArray("assets") ?: continue
                    val checksumsUrl = assets.jsonObjects()
                        .firstOrNull { it.optString("name") == "SHA256SUMS.txt" }
                        ?.optString("browser_download_url")
                        ?.takeIf { it.isNotEmpty() }
                        ?: continue
                    val candidates = assets.jsonObjects().filter {
                        it.optString("name").endsWith(".apk", ignoreCase = true)
                    }
                    val asset = candidates.firstOrNull {
                        it.optString("name") == "aemulator-$versionName${if (wantV0003) "-v0003.apk" else ".apk"}"
                    } ?: candidates.firstOrNull {
                        val n = it.optString("name")
                        if (wantV0003) n.endsWith("-v0003.apk", ignoreCase = true)
                        else !n.contains("-v0003", ignoreCase = true)
                    } ?: continue

                    val sizeBytes = asset.optLong("size", 0L)
                    if (sizeBytes > MAX_APK_BYTES) continue
                    val downloadUrl = asset.optString("browser_download_url")
                    val fileName = asset.optString("name")
                    if (downloadUrl.isEmpty() || fileName.isEmpty()) continue

                    val info = ReleaseInfo(
                        versionName = versionName,
                        tagName = tagName,
                        title = title,
                        body = body,
                        downloadUrl = downloadUrl,
                        fileName = fileName,
                        sizeBytes = sizeBytes,
                        checksumsUrl = checksumsUrl
                    )
                    if (best == null || isNewerVersion(versionName, best.versionName)) best = info
                }
                best
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun JSONArray.jsonObjects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    /**
     * Сравнение версий (семантическое сравнение сегментов чисел).
     * Возвращает true, если latestTag новее чем currentVersion.
     */
    fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val cleanLatest = latestTag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

        if (cleanLatest.equals(cleanCurrent, ignoreCase = true)) return false

        val latestParts = cleanLatest.split('.').map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val currentParts = cleanCurrent.split('.').map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        return false
    }

    /**
     * Скачивание APK с поддержкой редиректов (GitHub перенаправляет на AWS S3), проверкой SHA-256
     * из SHA256SUMS.txt и отслеживанием прогресса.
     */
    suspend fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Float, Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        // Очищаем старые apk перед новой загрузкой
        updatesDir.listFiles()?.forEach {
            if (it.extension.equals("apk", ignoreCase = true) || it.name.endsWith(".part")) it.delete()
        }

        val targetFile = File(updatesDir, safeFileName(release.fileName))
        val partFile = File(updatesDir, targetFile.name + ".part")
        val expectedSha = expectedSha256(release)

        var currentUrl = release.downloadUrl
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            val url = URL(currentUrl)
            if (url.protocol != "https") throw IOException("refusing a non-HTTPS download: $currentUrl")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "AEmulator-App")

            val code = conn.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val newUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (newUrl != null && redirects < 5) {
                    currentUrl = URL(url, newUrl).toString()
                    redirects++
                    continue
                }
                throw IOException("too many redirects")
            }
            break
        }

        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }

            val totalLength = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            if (totalLength > MAX_APK_BYTES) throw IOException("APK is larger than ${formatBytes(MAX_APK_BYTES)}")
            var downloadedBytes = 0L
            val digest = MessageDigest.getInstance("SHA-256")

            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        downloadedBytes += bytesRead
                        if (downloadedBytes > MAX_APK_BYTES) throw IOException("APK is larger than ${formatBytes(MAX_APK_BYTES)}")
                        if (release.sizeBytes > 0 && downloadedBytes > release.sizeBytes) {
                            throw IOException("APK is larger than the size GitHub reports")
                        }
                        digest.update(buffer, 0, bytesRead)
                        output.write(buffer, 0, bytesRead)
                        val progress = if (totalLength > 0) downloadedBytes.toFloat() / totalLength else 0f
                        onProgress(progress.coerceIn(0f, 1f), downloadedBytes, totalLength)
                    }
                    output.flush()
                }
            }

            if (release.sizeBytes > 0 && downloadedBytes != release.sizeBytes) {
                throw IOException("APK size mismatch: got $downloadedBytes, expected ${release.sizeBytes}")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                throw IOException("SHA-256 mismatch: the downloaded APK is not the published one")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            targetFile
        } finally {
            conn.disconnect()
            partFile.delete()
        }
    }

    /** The hash published in SHA256SUMS.txt for this release asset; the update is refused without one. */
    private fun expectedSha256(release: ReleaseInfo): String {
        val url = URL(release.checksumsUrl ?: throw IOException("release has no SHA256SUMS.txt"))
        if (url.protocol != "https") throw IOException("refusing a non-HTTPS checksum download")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AEmulator-App")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("SHA256SUMS.txt: HTTP ${conn.responseCode}")
            }
            val text = conn.inputStream.use { readLimited(it, MAX_CHECKSUMS_BYTES) }
            val wanted = release.fileName.substringAfterLast('/')
            for (line in text.lineSequence()) {
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) continue
                val name = parts[1].trim().removePrefix("*").removePrefix("./").substringAfterLast('/')
                if (name == wanted && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) {
                    return parts[0].lowercase(Locale.US)
                }
            }
            throw IOException("SHA256SUMS.txt has no entry for $wanted")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream, max: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("response is larger than ${formatBytes(max)}")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** The version/tag comes from the network: never let it choose a path. */
    private fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return (clean.ifEmpty { "aemulator.apk" }.take(80).let {
            if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk"
        })
    }

    /**
     * Запуск установки загруженного APK через системный PackageInstaller.
     */
    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return false
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        return true
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val group = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, group.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[group])
    }
}
