package com.lechugones.cocina.feature.settings.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.lechugones.cocina.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The app's public GitHub repository: releases carry the signed APK. */
object AppLinks {
    const val OWNER = "jsarriaa"
    const val REPO = "recipe-cart"
    const val PROFILE_URL = "https://github.com/$OWNER"
    const val REPO_URL = "https://github.com/$OWNER/$REPO"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
}

/** Dotted version numbers ("1.10.0" > "1.9.2"); a "v" prefix and a "-debug" suffix are ignored. */
object AppVersion {
    fun parts(version: String): List<Int> =
        version
            .trim()
            .removePrefix("v")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    fun isNewer(
        remote: String,
        local: String,
    ): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    @SerialName("html_url") val pageUrl: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    data class Available(
        val version: String,
        val apk: GitHubAsset,
    ) : UpdateCheck

    /** A newer release without an APK attached. */
    data class NoApk(
        val version: String,
    ) : UpdateCheck

    data object Failed : UpdateCheck
}

/**
 * "Buscar actualizaciones": asks GitHub for the latest release of [AppLinks.REPO] and downloads its
 * APK. Only when the user taps the button (CLAUDE.md, hard rule 4 lists GitHub for this).
 */
@Singleton
class AppUpdates
    @Inject
    constructor(
        private val client: OkHttpClient,
        @param:ApplicationContext private val context: Context,
    ) {
        /** Debug builds are a different app: they may check, but never install the release APK. */
        val canInstall: Boolean = !BuildConfig.APPLICATION_ID.endsWith(".debug")

        /**
         * Downloads [update]'s APK and hands it to Android's installer, which asks the user to
         * confirm. False when the download fails.
         */
        suspend fun downloadAndInstall(update: UpdateCheck.Available): Boolean {
            if (!canInstall) return false
            val file = download(update.apk, File(context.cacheDir, UPDATES_DIR)) ?: return false
            install(file)
            return true
        }

        private fun install(file: File) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                // Android asks once per app: "Permitir desde esta fuente". Then the user taps again.
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ("package:" + context.packageName).toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        private val json = Json { ignoreUnknownKeys = true }

        suspend fun check(currentVersion: String): UpdateCheck =
            withContext(Dispatchers.IO) {
                try {
                    val request =
                        Request
                            .Builder()
                            .url(AppLinks.LATEST_RELEASE_API)
                            .header("Accept", "application/vnd.github+json")
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext UpdateCheck.Failed
                        val release = json.decodeFromString(GitHubRelease.serializer(), response.body.string())
                        val version = release.tag.removePrefix("v")
                        when {
                            !AppVersion.isNewer(version, currentVersion) -> {
                                UpdateCheck.UpToDate
                            }

                            else -> {
                                release.assets.firstOrNull { it.name.endsWith(".apk") }?.let { UpdateCheck.Available(version, it) }
                                    ?: UpdateCheck.NoApk(version)
                            }
                        }
                    }
                } catch (e: IOException) {
                    UpdateCheck.Failed
                } catch (e: SerializationException) {
                    UpdateCheck.Failed
                }
            }

        /** Downloads [asset] into [directory]; null when it fails or the file is incomplete. */
        suspend fun download(
            asset: GitHubAsset,
            directory: File,
        ): File? =
            withContext(Dispatchers.IO) {
                try {
                    directory.mkdirs()
                    directory.listFiles()?.forEach { it.delete() }
                    val target = File(directory, "CocinaParaLechugones-update.apk")
                    client.newCall(Request.Builder().url(asset.downloadUrl).build()).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                    }
                    target.takeIf { it.length() > 0 && (asset.size == 0L || it.length() == asset.size) }
                } catch (e: IOException) {
                    null
                }
            }

        private companion object {
            const val UPDATES_DIR = "updates"
        }
    }
