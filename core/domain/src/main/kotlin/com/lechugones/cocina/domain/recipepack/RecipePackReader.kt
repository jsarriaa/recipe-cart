package com.lechugones.cocina.domain.recipepack

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** One recipe document found in a pack. */
data class PackDocument(
    /** File name inside the pack, or the name of the shared file/text. */
    val name: String,
    val parsed: ParsedDocument,
)

data class PackReadResult(
    val documents: List<PackDocument>,
    /** Image bytes by pack path (`images/foo.jpg`). */
    val images: Map<String, ByteArray>,
    /** Pack-level findings (`P01`–`P06`). */
    val issues: List<PackIssue>,
) {
    /** The input is unusable as a whole (a pack error). */
    val isRejected: Boolean get() = issues.any { it.isError }

    /** The input is an app backup and belongs in Restore (`P05`). */
    val isBackup: Boolean get() = issues.any { it.code == IssueCode.P05 }
}

/**
 * Reads recipe packs (RECIPE_FORMAT § 2): a single `.md` document, shared text, or a `.zip`.
 * Content is sniffed, never trusted by MIME type. All limits are enforced while reading, so a
 * zip bomb never inflates more than [IMAGE_MAX_BYTES] per entry or [UNCOMPRESSED_MAX_BYTES] in total.
 */
object RecipePackReader {
    const val ZIP_MAX_BYTES = 50L * 1024 * 1024
    const val UNCOMPRESSED_MAX_BYTES = 100L * 1024 * 1024
    const val ENTRIES_MAX = 500
    const val IMAGE_MAX_BYTES = 5 * 1024 * 1024
    const val RECIPES_MAX = 100
    const val BACKUP_MANIFEST = "backup-manifest.json"

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val IMAGE_ENTRY = Regex("^images/[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
    private val DRIVE_LETTER = Regex("^[A-Za-z]:")

    /** Reads a file or stream of unknown type. [name] is used for single documents. */
    fun read(
        input: InputStream,
        name: String,
        maxBytes: Long = ZIP_MAX_BYTES,
    ): PackReadResult {
        val counting = CountingInputStream(input, maxBytes)
        return try {
            val head = readUpTo(counting, ZIP_MAGIC.size)
            if (head.contentEquals(ZIP_MAGIC)) {
                readZip(head, counting)
            } else {
                readSingle(head, counting, name)
            }
        } catch (e: LimitExceededException) {
            packFailure(IssueCode.P02)
        } catch (e: IOException) {
            packFailure(IssueCode.P01)
        }
    }

    /** Text shared through the share sheet: exactly one document. */
    fun readText(
        text: String,
        name: String,
    ): PackReadResult = PackReadResult(listOf(PackDocument(name, RecipeDocumentParser.parse(text))), emptyMap(), emptyList())

    private fun readSingle(
        head: ByteArray,
        rest: InputStream,
        name: String,
    ): PackReadResult {
        val bytes = head + readCapped(rest, RecipeRules.DOCUMENT_MAX_BYTES + 1 - head.size)
        val document =
            if (bytes.size > RecipeRules.DOCUMENT_MAX_BYTES) {
                ParsedDocument(null, listOf(PackIssue(IssueCode.E12)))
            } else {
                val text = decodeUtf8(bytes) ?: return packFailure(IssueCode.P01)
                RecipeDocumentParser.parse(text)
            }
        return PackReadResult(listOf(PackDocument(name, document)), emptyMap(), emptyList())
    }

    private fun readZip(
        head: ByteArray,
        rest: InputStream,
    ): PackReadResult {
        val issues = mutableListOf<PackIssue>()
        val texts = mutableListOf<Pair<String, ByteArray>>()
        val images = mutableMapOf<String, ByteArray>()
        var entries = 0
        var uncompressed = 0L

        ZipInputStream(SequenceStream(head, rest)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                if (isUnsafe(path)) return packFailure(IssueCode.P03, path)
                if (++entries > ENTRIES_MAX) return packFailure(IssueCode.P02)
                if (entry.isDirectory || isSilentlyIgnored(path)) continue
                if (path == BACKUP_MANIFEST) return packFailure(IssueCode.P05)

                val isRootDocument = '/' !in path && path.endsWith(".md", ignoreCase = true)
                val limit =
                    when {
                        isRootDocument -> {
                            RecipeRules.DOCUMENT_MAX_BYTES
                        }

                        IMAGE_ENTRY.matches(path) -> {
                            IMAGE_MAX_BYTES
                        }

                        else -> {
                            issues += PackIssue(IssueCode.P06, path)
                            continue
                        }
                    }
                val bytes = readCapped(zip, limit + 1)
                uncompressed += bytes.size
                if (uncompressed > UNCOMPRESSED_MAX_BYTES) return packFailure(IssueCode.P02)
                when {
                    isRootDocument -> texts += path to bytes
                    bytes.size > limit -> issues += PackIssue(IssueCode.P06, path)
                    else -> images[path] = bytes
                }
            }
        }

        if (texts.isEmpty()) return packFailure(IssueCode.P04)
        if (texts.size > RECIPES_MAX) return packFailure(IssueCode.P02)
        val documents =
            texts.map { (path, bytes) ->
                val parsed =
                    when {
                        bytes.size > RecipeRules.DOCUMENT_MAX_BYTES -> {
                            ParsedDocument(null, listOf(PackIssue(IssueCode.E12)))
                        }

                        else -> {
                            decodeUtf8(bytes)?.let { RecipeDocumentParser.parse(it, images.keys) }
                                ?: ParsedDocument(null, listOf(PackIssue(IssueCode.E01)))
                        }
                    }
                PackDocument(path, parsed)
            }
        return PackReadResult(documents, images, issues)
    }

    private fun isUnsafe(path: String): Boolean =
        '\\' in path ||
            path.startsWith('/') ||
            DRIVE_LETTER.containsMatchIn(path) ||
            path.split('/').any { it == ".." }

    private fun isSilentlyIgnored(path: String): Boolean =
        path.startsWith("__MACOSX/") || path.substringAfterLast('/').let { it == ".DS_Store" || it == "Thumbs.db" }

    private fun packFailure(
        code: IssueCode,
        path: String = "",
    ) = PackReadResult(emptyList(), emptyMap(), listOf(PackIssue(code, path)))

    private fun decodeUtf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

    /** Reads up to [max] bytes; stops early at end of stream. */
    private fun readCapped(
        input: InputStream,
        max: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = max
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun readUpTo(
        input: InputStream,
        count: Int,
    ): ByteArray = readCapped(input, count)

    private class LimitExceededException : IOException()

    /** Fails once more than [max] bytes have been read from the underlying stream. */
    private class CountingInputStream(
        input: InputStream,
        private val max: Long,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private fun count(n: Long) {
            count += n
            if (count > max) throw LimitExceededException()
        }
    }

    /** Re-attaches the sniffed [head] bytes in front of [rest]. */
    private class SequenceStream(
        private val head: ByteArray,
        private val rest: InputStream,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < head.size) head[position++].toInt() and 0xFF else rest.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            if (position < head.size) {
                val n = minOf(len, head.size - position)
                System.arraycopy(head, position, b, off, n)
                position += n
                return n
            }
            return rest.read(b, off, len)
        }

        override fun close() = rest.close()
    }
}
