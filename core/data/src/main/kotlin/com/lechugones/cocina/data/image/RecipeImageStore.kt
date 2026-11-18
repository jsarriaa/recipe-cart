package com.lechugones.cocina.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recipe photos in app-private storage (SPEC § 4.9): `files/images/<uuid>.jpg`, rotated per EXIF,
 * at most [MAX_SIDE] px on the long side, JPEG quality [QUALITY]. Recipes store only the file name.
 */
@Singleton
class RecipeImageStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val directory: File get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

        fun file(name: String): File = File(directory, name)

        /** Decodes, rotates, downscales and stores an image picked by the user. Null if it cannot be read. */
        suspend fun importUri(uri: Uri): String? =
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                process(
                    open = { resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri") },
                )
            }

        /** Same as [importUri] for image bytes from a recipe pack. */
        suspend fun importBytes(bytes: ByteArray): String? = withContext(Dispatchers.IO) { process(open = { ByteArrayInputStream(bytes) }) }

        /** Copies a stored photo (for "Duplicar"). */
        suspend fun copy(name: String): String? =
            withContext(Dispatchers.IO) {
                val source = file(name)
                if (!source.isFile) return@withContext null
                val target = newName()
                source.copyTo(file(target))
                target
            }

        suspend fun readBytes(name: String): ByteArray? = withContext(Dispatchers.IO) { file(name).takeIf { it.isFile }?.readBytes() }

        suspend fun delete(name: String?) {
            if (name == null) return
            withContext(Dispatchers.IO) { file(name).delete() }
        }

        /** Deletes photo files that no recipe references any more. */
        suspend fun deleteOrphans(referenced: Set<String>) =
            withContext(Dispatchers.IO) {
                directory.listFiles()?.filter { it.isFile && it.name !in referenced }?.forEach { it.delete() }
            }

        private fun process(open: () -> InputStream): String? =
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open().use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
                val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
                val rotation = open().use { ExifInterface(it).rotationDegrees }
                val result = scaleAndRotate(decoded, rotation)

                val name = newName()
                file(name).outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                if (result !== decoded) result.recycle()
                decoded.recycle()
                name
            } catch (e: IOException) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }

        private fun scaleAndRotate(
            bitmap: Bitmap,
            rotation: Int,
        ): Bitmap {
            val scale = scaleFactor(bitmap.width, bitmap.height)
            if (scale == 1f && rotation == 0) return bitmap
            val matrix =
                Matrix().apply {
                    postScale(scale, scale)
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        private fun newName() = "${UUID.randomUUID()}.jpg"

        companion object {
            const val DIRECTORY = "images"
            const val MAX_SIDE = 1600
            const val QUALITY = 85

            /** Largest power of two that keeps the decoded image at least [MAX_SIDE] on its long side. */
            fun sampleSize(
                width: Int,
                height: Int,
            ): Int {
                var sample = 1
                while (maxOf(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
                return sample
            }

            /** Factor that brings the long side down to [MAX_SIDE]; 1 when it already fits. */
            fun scaleFactor(
                width: Int,
                height: Int,
            ): Float {
                val longSide = maxOf(width, height)
                return if (longSide <= MAX_SIDE) 1f else MAX_SIDE.toFloat() / longSide
            }
        }
    }
