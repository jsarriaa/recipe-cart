package com.lechugones.cocina.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.data.image.RecipeImageStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Version of the running app, written into backups. Provided by `:app`. */
data class AppInfo(
    val versionName: String,
)

@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val schemaVersion: Int,
    /** ISO-8601 instant. */
    val createdAt: String,
    val recipes: Int,
    val images: Int,
    val files: List<BackupFileEntry>,
)

@Serializable
data class BackupFileEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

/** A validated backup unpacked into a staging folder, ready to restore. */
class StagedBackup(
    val directory: File,
    val manifest: BackupManifest,
)

sealed interface BackupInspection {
    data class Ready(
        val staged: StagedBackup,
    ) : BackupInspection

    data class Invalid(
        val reason: Reason,
    ) : BackupInspection

    enum class Reason {
        /** Not a zip with a backup manifest (maybe a recipe pack). */
        NOT_A_BACKUP,

        /** Made by a newer app version (newer format or database schema). */
        NEWER_VERSION,

        /** Checksums, structure or the database itself are damaged. */
        CORRUPT,
    }
}

/**
 * Single-file backup and restore (SPEC § 4.7): a zip with `backup-manifest.json`, a consistent
 * database snapshot, the settings file and the referenced recipe photos.
 */
@Singleton
class BackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: CocinaDatabase,
        private val appInfo: AppInfo,
        private val clock: Clock,
    ) {
        private val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

        private val databaseFile: File get() = context.getDatabasePath(CocinaDatabase.NAME)
        private val settingsFile: File get() = File(context.filesDir, SETTINGS_PATH)
        private val imagesDirectory: File get() = File(context.filesDir, RecipeImageStore.DIRECTORY).apply { mkdirs() }
        private val automaticDirectory: File get() = File(context.filesDir, AUTOMATIC_DIRECTORY).apply { mkdirs() }

        suspend fun createBackup(out: OutputStream): BackupManifest = withContext(Dispatchers.IO) { writeBackup(out) }

        /** Unpacks and validates a backup without touching the current data. */
        suspend fun inspect(input: InputStream): BackupInspection =
            withContext(Dispatchers.IO) {
                try {
                    stage(input)
                } catch (e: IOException) {
                    BackupInspection.Invalid(BackupInspection.Reason.CORRUPT)
                } catch (e: SerializationException) {
                    BackupInspection.Invalid(BackupInspection.Reason.CORRUPT)
                } catch (e: IllegalArgumentException) {
                    BackupInspection.Invalid(BackupInspection.Reason.CORRUPT)
                }
            }

        /**
         * Replaces all app data with [staged]. An automatic backup of the current data is written
         * first (the last [AUTOMATIC_KEPT] are kept). The database is closed: the caller must
         * restart the app process right after this returns.
         */
        suspend fun restore(staged: StagedBackup) =
            withContext(Dispatchers.IO) {
                val stamp = STAMP.format(Instant.now(clock))
                File(automaticDirectory, "pre-restore-$stamp.zip").outputStream().use { writeBackup(it) }
                automaticBackups().drop(AUTOMATIC_KEPT).forEach { it.delete() }

                database.close()
                DATABASE_SUFFIXES.forEach { File(databaseFile.path + it).delete() }
                File(staged.directory, DATABASE_PATH).copyTo(databaseFile, overwrite = true)

                val stagedSettings = File(staged.directory, SETTINGS_PATH)
                if (stagedSettings.isFile) stagedSettings.copyTo(settingsFile, overwrite = true) else settingsFile.delete()

                imagesDirectory.listFiles()?.forEach { it.delete() }
                File(staged.directory, RecipeImageStore.DIRECTORY).listFiles()?.forEach {
                    it.copyTo(File(imagesDirectory, it.name), overwrite = true)
                }
                staged.directory.deleteRecursively()
            }

        /** Automatic pre-restore backups, newest first. */
        fun automaticBackups(): List<File> =
            automaticDirectory
                .listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                .orEmpty()
                .sortedByDescending { it.name }

        private suspend fun writeBackup(out: OutputStream): BackupManifest {
            val snapshot = File(context.cacheDir, "backup-snapshot.db").apply { delete() }
            try {
                snapshotDatabase(snapshot)
                val dao = database.recipeDao()
                val files = linkedMapOf(DATABASE_PATH to snapshot)
                if (settingsFile.isFile) files[SETTINGS_PATH] = settingsFile
                val photos =
                    dao
                        .imagePaths()
                        .toSortedSet()
                        .map { File(imagesDirectory, it) }
                        .filter { it.isFile }
                photos.forEach { files["${RecipeImageStore.DIRECTORY}/${it.name}"] = it }

                val manifest =
                    BackupManifest(
                        format = FORMAT,
                        formatVersion = FORMAT_VERSION,
                        appVersion = appInfo.versionName,
                        schemaVersion = CocinaDatabase.VERSION,
                        createdAt = Instant.now(clock).toString(),
                        recipes = dao.count(),
                        images = photos.size,
                        files = files.map { (path, file) -> BackupFileEntry(path, file.length(), sha256(file)) },
                    )
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    files.forEach { (path, file) ->
                        zip.putNextEntry(ZipEntry(path))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                return manifest
            } finally {
                snapshot.delete()
            }
        }

        /** A transactionally consistent copy of the live database. */
        private fun snapshotDatabase(target: File) {
            val db = database.openHelper.writableDatabase
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    db.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath))
                    return
                } catch (e: SQLiteException) {
                    target.delete() // Fall through to checkpoint + copy.
                }
            }
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            databaseFile.copyTo(target, overwrite = true)
        }

        private fun stage(input: InputStream): BackupInspection {
            val staging = File(context.cacheDir, STAGING_DIRECTORY).apply { deleteRecursively() }
            staging.mkdirs()
            var manifestBytes: ByteArray? = null
            var unexpectedEntries = false
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val path = entry.name
                    if (path == MANIFEST) {
                        manifestBytes = zip.readBytesCapped(MANIFEST_MAX_BYTES)
                        continue
                    }
                    if (!isAllowedPath(path)) {
                        // Never written to disk. Judged after the loop: no manifest means "not a backup".
                        unexpectedEntries = true
                        continue
                    }
                    val target = File(staging, path)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_TOTAL_BYTES) return BackupInspection.Invalid(BackupInspection.Reason.CORRUPT)
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }

            val bytes = manifestBytes ?: return BackupInspection.Invalid(BackupInspection.Reason.NOT_A_BACKUP)
            val manifest = json.decodeFromString(BackupManifest.serializer(), String(bytes, Charsets.UTF_8))
            if (manifest.format != FORMAT) return BackupInspection.Invalid(BackupInspection.Reason.NOT_A_BACKUP)
            if (unexpectedEntries) return BackupInspection.Invalid(BackupInspection.Reason.CORRUPT)
            if (manifest.formatVersion > FORMAT_VERSION || manifest.schemaVersion > CocinaDatabase.VERSION) {
                return BackupInspection.Invalid(BackupInspection.Reason.NEWER_VERSION)
            }

            val listed = manifest.files.associateBy { it.path }
            val staged =
                staging
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(staging).invariantSeparatorsPath }
                    .toSet()
            val consistent =
                DATABASE_PATH in listed &&
                    staged == listed.keys &&
                    manifest.files.all { entry ->
                        val file = File(staging, entry.path)
                        file.length() == entry.size && sha256(file) == entry.sha256
                    }
            if (!consistent || !isHealthyDatabase(File(staging, DATABASE_PATH), manifest.schemaVersion)) {
                return BackupInspection.Invalid(BackupInspection.Reason.CORRUPT)
            }
            return BackupInspection.Ready(StagedBackup(staging, manifest))
        }

        private fun isHealthyDatabase(
            file: File,
            schemaVersion: Int,
        ): Boolean =
            try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val integrity = db.rawQuery("PRAGMA integrity_check", null).use { if (it.moveToFirst()) it.getString(0) else null }
                    integrity == "ok" && db.version == schemaVersion
                }
            } catch (e: SQLiteException) {
                false
            }

        private fun isAllowedPath(path: String): Boolean = path == DATABASE_PATH || path == SETTINGS_PATH || IMAGE_PATH.matches(path)

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun InputStream.readBytesCapped(max: Int): ByteArray {
            val bytes = readNBytesCompat(max + 1)
            if (bytes.size > max) throw IOException("Manifest too large")
            return bytes
        }

        private fun InputStream.readNBytesCompat(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var remaining = max
            while (remaining > 0) {
                val read = read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            return out.toByteArray()
        }

        companion object {
            const val FORMAT = "cpl-backup"
            const val FORMAT_VERSION = 1
            const val MANIFEST = "backup-manifest.json"
            const val DATABASE_PATH = "database/cocina.db"
            const val SETTINGS_PATH = "datastore/settings.preferences_pb"
            const val AUTOMATIC_DIRECTORY = "auto-backups"
            const val AUTOMATIC_KEPT = 3
            private const val STAGING_DIRECTORY = "restore-staging"
            private const val MANIFEST_MAX_BYTES = 1024 * 1024
            private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
            private const val BUFFER = 64 * 1024

            /** One file directly under images/, never `.` or `..`. */
            private val IMAGE_PATH = Regex("^images/(?!\\.{1,2}$)[A-Za-z0-9._-]+$")
            private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
            private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        }
    }
