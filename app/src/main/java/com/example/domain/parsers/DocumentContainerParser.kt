package com.example.domain.parsers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.example.data.model.SourceFormat
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class ExtractedContainerPayload(
    val title: String,
    val author: String,
    val sourceFormat: SourceFormat,
    val sourceDigest: String,
    val rawTextBlocks: List<RawSourceBlock>,
    val pageBitmaps: List<Bitmap> = emptyList()
)

data class RawSourceBlock(
    val title: String,
    val text: String,
    val sourceSpan: String
)

object DocumentContainerParser {

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun parseUri(context: Context, uri: Uri, displayNameHint: String = "Imported Document"): ExtractedContainerPayload {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to read selected document stream.")
        val sourceDigest = sha256Hex(rawBytes)
        val cleanName = displayNameHint.substringBeforeLast('.').ifBlank { "Imported Folio" }

        return when {
            mime.contains("pdf") || displayNameHint.endsWith(".pdf", ignoreCase = true) -> {
                parsePdf(context, uri, rawBytes, cleanName, sourceDigest)
            }
            mime.contains("epub") || displayNameHint.endsWith(".epub", ignoreCase = true) -> {
                parseEpub(rawBytes, cleanName, sourceDigest)
            }
            displayNameHint.endsWith(".mobi", ignoreCase = true) ||
                displayNameHint.endsWith(".prc", ignoreCase = true) ||
                mime.contains("mobipocket") -> {
                parseMobi(rawBytes, cleanName, sourceDigest)
            }
            mime.startsWith("image/") -> {
                parseImage(rawBytes, cleanName, sourceDigest)
            }
            else -> {
                val text = rawBytes.toString(Charsets.UTF_8)
                    .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), " ")
                    .trim()
                val blocks = chunkTextIntoBlocks(cleanName, text, "byte")
                ExtractedContainerPayload(
                    title = cleanName,
                    author = "Uploaded Source",
                    sourceFormat = SourceFormat.MARKDOWN_DITA,
                    sourceDigest = sourceDigest,
                    rawTextBlocks = blocks
                )
            }
        }
    }

    private fun parsePdf(
        context: Context,
        uri: Uri,
        rawBytes: ByteArray,
        title: String,
        sourceDigest: String
    ): ExtractedContainerPayload {
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                PdfRenderer(pfd).use { renderer ->
                    val maxPages = minOf(renderer.pageCount, 4)
                    for (i in 0 until maxPages) {
                        renderer.openPage(i).use { page ->
                            val targetWidth = 1240
                            val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                            val targetHeight = (page.height * scale).toInt().coerceIn(400, 1800)
                            val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmaps.add(bmp)
                        }
                    }
                }
            }
        } finally {
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }

        // Extract any uncompressed ASCII literal strings from PDF text streams as auxiliary locus hint
        val asciiHint = extractPdfLiteralStrings(rawBytes)
        val blocks = if (asciiHint.isNotBlank()) {
            chunkTextIntoBlocks(title, asciiHint, "pdf-page")
        } else {
            listOf(
                RawSourceBlock(
                    title = title,
                    text = "PDF rasterized ${bitmaps.size} page(s) for cloud multimodal OCR.",
                    sourceSpan = "pdf:pages:1-${bitmaps.size.coerceAtLeast(1)}"
                )
            )
        }

        return ExtractedContainerPayload(
            title = title,
            author = "PDF Document",
            sourceFormat = SourceFormat.PDF,
            sourceDigest = sourceDigest,
            rawTextBlocks = blocks,
            pageBitmaps = bitmaps
        )
    }

    private fun extractPdfLiteralStrings(pdfBytes: ByteArray): String {
        val raw = String(pdfBytes, Charsets.ISO_8859_1)
        val regex = Regex("\\(([\\x20-\\x7E]{4,240})\\)\\s*Tj")
        val matches = regex.findAll(raw).map { it.groupValues[1] }.take(80).toList()
        return matches.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun parseEpub(
        rawBytes: ByteArray,
        fallbackTitle: String,
        sourceDigest: String
    ): ExtractedContainerPayload {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.size < 4_000_000L) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var read = zis.read(buf)
                    while (read != -1) {
                        out.write(buf, 0, read)
                        read = zis.read(buf)
                    }
                    entries[entry.name] = out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8).orEmpty()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)
        val opfBytes = opfPath?.let { entries[it] }
            ?: entries.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }?.value
        val opfBaseDir = opfPath?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""

        var bookTitle = fallbackTitle
        var bookAuthor = "EPUB Author"
        val spinePaths = mutableListOf<String>()

        if (opfBytes != null) {
            val opfText = opfBytes.toString(Charsets.UTF_8)
            Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
                .find(opfText)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { bookTitle = decodeXmlEntities(it) }
            Regex("<dc:creator[^>]*>(.*?)</dc:creator>", RegexOption.DOT_MATCHES_ALL)
                .find(opfText)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { bookAuthor = decodeXmlEntities(it) }

            val idToHref = mutableMapOf<String, String>()
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(opfBytes), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                            if (id != null && href != null && (mediaType.contains("html") || href.endsWith(".xhtml") || href.endsWith(".html"))) {
                                idToHref[id] = opfBaseDir + href
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref")
                            val resolved = idref?.let { idToHref[it] }
                            if (resolved != null) spinePaths.add(resolved)
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        val htmlKeys = if (spinePaths.isNotEmpty()) {
            spinePaths
        } else {
            entries.keys.filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }.sorted()
        }

        val blocks = mutableListOf<RawSourceBlock>()
        htmlKeys.take(12).forEachIndexed { idx, path ->
            val html = entries[path]?.toString(Charsets.UTF_8).orEmpty()
            val stripped = stripHtmlToCleanProse(html)
            if (stripped.isNotBlank()) {
                val heading = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1)?.let { stripHtmlToCleanProse(it) }
                    ?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                blocks.addAll(chunkTextIntoBlocks(heading, stripped, "epub:${path.substringAfterLast('/')}"))
            }
        }

        if (blocks.isEmpty()) {
            blocks.add(
                RawSourceBlock(
                    title = bookTitle,
                    text = "EPUB container parsed (${entries.size} entries). Ready for cloud synthesis.",
                    sourceSpan = "epub:root:0"
                )
            )
        }

        return ExtractedContainerPayload(
            title = bookTitle,
            author = bookAuthor,
            sourceFormat = SourceFormat.EPUB,
            sourceDigest = sourceDigest,
            rawTextBlocks = blocks.take(12)
        )
    }

    private fun parseMobi(
        rawBytes: ByteArray,
        fallbackTitle: String,
        sourceDigest: String
    ): ExtractedContainerPayload {
        // Parse PalmDB (PDB) header + PalmDOC compression
        if (rawBytes.size < 86) {
            throw IllegalArgumentException("File is too small to be a valid MOBI/PalmDOC container.")
        }
        val titleBytes = rawBytes.copyOfRange(0, 32)
        val pdbTitle = titleBytes.takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.UTF_8).trim()
            .ifBlank { fallbackTitle }

        val buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)
        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        if (numRecords < 2 || 78 + numRecords * 8 > rawBytes.size) {
            val fallback = stripHtmlToCleanProse(rawBytes.toString(Charsets.UTF_8))
            return ExtractedContainerPayload(
                title = pdbTitle,
                author = "MOBI Container",
                sourceFormat = SourceFormat.MOBI,
                sourceDigest = sourceDigest,
                rawTextBlocks = chunkTextIntoBlocks(pdbTitle, fallback.ifBlank { "MOBI binary record unpacked." }, "mobi")
            )
        }

        val recordOffsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            recordOffsets[i] = buf.getInt(78 + i * 8)
        }

        val rec0Start = recordOffsets[0]
        val rec0End = recordOffsets[1].coerceAtMost(rawBytes.size)
        val compression = if (rec0Start + 2 <= rec0End) {
            ByteBuffer.wrap(rawBytes, rec0Start, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        } else 1
        val textRecordCount = if (rec0Start + 10 <= rec0End) {
            (ByteBuffer.wrap(rawBytes, rec0Start + 8, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF)
                .coerceIn(1, numRecords - 1)
        } else (numRecords - 1).coerceAtMost(16)

        val textOut = ByteArrayOutputStream()
        val maxRecords = minOf(textRecordCount, 12)
        for (i in 1..maxRecords) {
            val start = recordOffsets[i].coerceIn(0, rawBytes.size)
            val end = (if (i + 1 < numRecords) recordOffsets[i + 1] else rawBytes.size).coerceIn(start, rawBytes.size)
            val slice = rawBytes.copyOfRange(start, end)
            val decoded = when (compression) {
                1 -> slice
                2 -> decompressPalmDocLz77(slice)
                else -> slice
            }
            textOut.write(decoded)
        }

        val rawHtml = textOut.toString(Charsets.UTF_8)
        val cleanProse = stripHtmlToCleanProse(rawHtml)
            .ifBlank { "MOBI container unpacked ($numRecords PalmDB records, compression=$compression)." }

        return ExtractedContainerPayload(
            title = pdbTitle,
            author = "MOBI / PalmDOC",
            sourceFormat = SourceFormat.MOBI,
            sourceDigest = sourceDigest,
            rawTextBlocks = chunkTextIntoBlocks(pdbTitle, cleanProse, "mobi:rec")
        )
    }

    private fun decompressPalmDocLz77(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 2)
        var i = 0
        while (i < input.size) {
            val b = input[i++].toInt() and 0xFF
            when {
                b == 0 -> out.write(0)
                b in 1..8 -> {
                    val count = minOf(b, input.size - i)
                    out.write(input, i, count)
                    i += count
                }
                b in 0x09..0x7F -> out.write(b)
                b >= 0xC0 -> {
                    out.write(' '.code)
                    out.write(b xor 0x80)
                }
                else -> {
                    if (i >= input.size) break
                    val next = input[i++].toInt() and 0xFF
                    val combined = (b shl 8) or next
                    val distance = (combined shr 3) and 0x07FF
                    val length = (combined and 0x0007) + 3
                    val current = out.toByteArray()
                    if (distance in 1..current.size) {
                        val copyStart = current.size - distance
                        for (k in 0 until length) {
                            val idx = copyStart + (k % distance)
                            out.write(current[idx].toInt())
                        }
                    }
                }
            }
        }
        return out.toByteArray()
    }

    private fun parseImage(
        rawBytes: ByteArray,
        title: String,
        sourceDigest: String
    ): ExtractedContainerPayload {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
        val maxDim = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (maxDim / sampleSize > 1600) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: throw IllegalStateException("Unable to decode selected image for cloud OCR.")

        return ExtractedContainerPayload(
            title = title,
            author = "Scanned Page Capture",
            sourceFormat = SourceFormat.OCR_IMAGE,
            sourceDigest = sourceDigest,
            rawTextBlocks = listOf(
                RawSourceBlock(
                    title = title,
                    text = "Scanned document image (${bitmap.width}x${bitmap.height}) queued for cloud OCR.",
                    sourceSpan = "image:0-${rawBytes.size}"
                )
            ),
            pageBitmaps = listOf(bitmap)
        )
    }

    fun chunkTextIntoBlocks(title: String, text: String, spanPrefix: String): List<RawSourceBlock> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return emptyList()
        val maxChunk = 1400
        val result = mutableListOf<RawSourceBlock>()
        var cursor = 0
        var idx = 0
        while (cursor < cleaned.length && idx < 10) {
            val end = (cursor + maxChunk).coerceAtMost(cleaned.length)
            val sliceEnd = if (end < cleaned.length) {
                cleaned.lastIndexOf('.', end).takeIf { it > cursor + 200 }?.plus(1) ?: end
            } else end
            val chunk = cleaned.substring(cursor, sliceEnd).trim()
            if (chunk.isNotBlank()) {
                result.add(
                    RawSourceBlock(
                        title = if (idx == 0) title else "$title (Part ${idx + 1})",
                        text = chunk,
                        sourceSpan = "$spanPrefix:$cursor-$sliceEnd"
                    )
                )
                idx++
            }
            cursor = sliceEnd
        }
        return result
    }

    private fun stripHtmlToCleanProse(html: String): String {
        val noScripts = html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<(p|div|br|h[1-6]|li|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
        return decodeXmlEntities(noScripts)
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n\n")
            .trim()
    }

    private fun decodeXmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}
