package com.lechugones.cocina.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.data.db.CocinaMigrations
import com.lechugones.cocina.data.image.RecipeImageStore
import com.lechugones.cocina.data.recipe.RoomRecipeRepository
import com.lechugones.cocina.data.recipe.testRecipe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-11-20T08:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: CocinaDatabase
    private val settingsFile = File(context.filesDir, BackupManager.SETTINGS_PATH)
    private val imagesDir = File(context.filesDir, RecipeImageStore.DIRECTORY)

    private fun openDatabase() =
        Room
            .databaseBuilder(context, CocinaDatabase::class.java, context.getDatabasePath(CocinaDatabase.NAME).absolutePath)
            .addMigrations(*CocinaMigrations.ALL)
            .allowMainThreadQueries()
            .build()

    private fun manager() = BackupManager(context, database, AppInfo("0.2.0-test"), clock)

    @Before
    fun setUp() {
        database = openDatabase()
        imagesDir.mkdirs()
        settingsFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seed() {
        RoomRecipeRepository(database.recipeDao()).save(testRecipe(imagePath = "tacos.jpg"))
        File(imagesDir, "tacos.jpg").writeBytes(byteArrayOf(9, 8, 7))
        File(imagesDir, "huerfana.jpg").writeBytes(byteArrayOf(1)) // not referenced: not backed up
        settingsFile.writeBytes(byteArrayOf(42))
    }

    private suspend fun backupBytes(): ByteArray = ByteArrayOutputStream().also { manager().createBackup(it) }.toByteArray()

    @Test
    fun `backup then restore brings everything back`() =
        runTest {
            seed()
            val bytes = backupBytes()

            // Change everything after the backup.
            RoomRecipeRepository(database.recipeDao()).delete("r1")
            File(imagesDir, "tacos.jpg").delete()
            settingsFile.writeBytes(byteArrayOf(0))

            val ready = assertIs<BackupInspection.Ready>(manager().inspect(ByteArrayInputStream(bytes)))
            assertEquals(1, ready.staged.manifest.recipes)
            assertEquals(1, ready.staged.manifest.images)
            assertEquals(CocinaDatabase.VERSION, ready.staged.manifest.schemaVersion)
            manager().restore(ready.staged)

            database = openDatabase()
            assertEquals(testRecipe(imagePath = "tacos.jpg"), RoomRecipeRepository(database.recipeDao()).get("r1"))
            assertTrue(File(imagesDir, "tacos.jpg").readBytes().contentEquals(byteArrayOf(9, 8, 7)))
            assertTrue(settingsFile.readBytes().contentEquals(byteArrayOf(42)))
            assertEquals(listOf("tacos.jpg"), imagesDir.list()?.toList(), "the restored photo set replaces the old one")
            assertEquals(1, manager().automaticBackups().size, "an automatic backup was taken first")
        }

    @Test
    fun `the automatic backup undoes a restore`() =
        runTest {
            seed()
            val withRecipe = backupBytes()
            RoomRecipeRepository(database.recipeDao()).delete("r1")
            manager().restore(assertIs<BackupInspection.Ready>(manager().inspect(ByteArrayInputStream(withRecipe))).staged)
            database = openDatabase()

            val automatic = manager().automaticBackups().first()
            manager().restore(assertIs<BackupInspection.Ready>(manager().inspect(automatic.inputStream())).staged)
            database = openDatabase()
            assertNull(RoomRecipeRepository(database.recipeDao()).get("r1"), "back to the state before the restore")
        }

    @Test
    fun `damaged files are rejected without touching data`() =
        runTest {
            seed()
            val tampered = rezip(backupBytes()) { path, bytes -> if (path == "images/tacos.jpg") byteArrayOf(0) else bytes }
            val result = assertIs<BackupInspection.Invalid>(manager().inspect(ByteArrayInputStream(tampered)))
            assertEquals(BackupInspection.Reason.CORRUPT, result.reason)
            assertEquals("Tacos de pollo con aguacate y mozzarella", RoomRecipeRepository(database.recipeDao()).get("r1")?.title)
        }

    @Test
    fun `backups from a newer app are refused`() =
        runTest {
            seed()
            val newer =
                rezip(backupBytes()) { path, bytes ->
                    if (path ==
                        BackupManager.MANIFEST
                    ) {
                        String(bytes).replace("\"schemaVersion\": ${CocinaDatabase.VERSION}", "\"schemaVersion\": 99").toByteArray()
                    } else {
                        bytes
                    }
                }
            assertEquals(
                BackupInspection.Reason.NEWER_VERSION,
                assertIs<BackupInspection.Invalid>(manager().inspect(ByteArrayInputStream(newer))).reason,
            )
        }

    @Test
    fun `recipe packs and random files are not backups`() =
        runTest {
            val pack = zip("tacos.md" to File("../../examples/recipe-packs/tacos-de-pollo.md").readBytes())
            assertEquals(
                BackupInspection.Reason.NOT_A_BACKUP,
                assertIs<BackupInspection.Invalid>(manager().inspect(ByteArrayInputStream(pack))).reason,
            )
            assertEquals(
                BackupInspection.Reason.NOT_A_BACKUP,
                assertIs<BackupInspection.Invalid>(manager().inspect(ByteArrayInputStream("hola".toByteArray()))).reason,
            )
        }

    @Test
    fun `entries outside the allowed layout are rejected`() =
        runTest {
            seed()
            val evil = rezip(backupBytes(), extra = "images/../escape.txt" to byteArrayOf(1)) { _, bytes -> bytes }
            assertEquals(
                BackupInspection.Reason.CORRUPT,
                assertIs<BackupInspection.Invalid>(manager().inspect(ByteArrayInputStream(evil))).reason,
            )
        }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun rezip(
        original: ByteArray,
        extra: Pair<String, ByteArray>? = null,
        transform: (String, ByteArray) -> ByteArray,
    ): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(original)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to transform(entry.name, zip.readBytes())
            }
        }
        extra?.let { entries += it }
        return zip(*entries.toTypedArray())
    }
}
