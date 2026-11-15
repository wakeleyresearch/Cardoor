package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.SourceFormat
import com.example.domain.parsers.ExtractedContainerPayload
import com.example.domain.parsers.RawSourceBlock
import com.example.domain.pipeline.MarkovianKernel
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "CloudSynthesisService"

data class CloudIngestionResult(
    val documentTitle: String,
    val author: String,
    val ditaTopicType: String,
    val summary: String,
    val synthesizedSections: List<MarkovianKernel.CloudSynthesizedSectionInput>,
    val extractedBlocksForOcr: List<RawSourceBlock>,
    val attestedModel: String,
    val cloudAttested: Boolean
)

class CloudSynthesisService {

    private val ingestionSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "title" to Schema.string(description = "Document title grounded strictly in the provided source"),
                "author" to Schema.string(description = "Author or provenance source name"),
                "ditaTopicType" to Schema.enumeration(
                    values = listOf("concept", "task", "reference"),
                    description = "DITA 1.3 topic classification"
                ),
                "summary" to Schema.string(description = "Concise factual summary strictly anchored to source content"),
                "sections" to Schema.array(
                    description = "Ordered reflowed document sections",
                    items = Schema.obj(
                        properties = mapOf(
                            "sectionId" to Schema.string(description = "Section ID e.g. sec_0"),
                            "title" to Schema.string(description = "Section heading"),
                            "reflowText" to Schema.string(description = "Clean, readable reflowed editorial prose grounded strictly in the source"),
                            "prosodyTone" to Schema.enumeration(
                                values = listOf("balanced", "analytical", "narrative"),
                                description = "Spoken-cadence prosody register cue"
                            ),
                            "triples" to Schema.array(
                                description = "Grounded RDF subject-predicate-object triples",
                                items = Schema.obj(
                                    properties = mapOf(
                                        "subject" to Schema.string(),
                                        "predicate" to Schema.string(),
                                        "objectValue" to Schema.string()
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = ingestionSchema
                temperature = 0.2f
            }
        )
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    suspend fun synthesizeFromContainer(
        payload: ExtractedContainerPayload,
        customRestEndpoint: String? = null
    ): Result<CloudIngestionResult> = runCatching {
        val promptHeader = buildString {
            appendLine("You are the Cloud Neuro-Symbolic DITA/RDF Synthesizer inside a Strict Markovian Pipeline.")
            appendLine("RULES:")
            appendLine("1. Extract and reflow ONLY facts present in the source document or page images. Do not invent external facts.")
            appendLine("2. Preserve technical precision, headings, and logical paragraph cadence for 'Written to be Read Out Loud' prosody.")
            appendLine("3. Emit sectionIds matching sec_0, sec_1, ... in order.")
            appendLine("4. Source digest: ${payload.sourceDigest}")
            appendLine("5. Source format: ${payload.sourceFormat.wireValue}")
            appendLine("6. Title hint: ${payload.title}")
        }

        val response = withTimeout(60_000L) {
            if (payload.pageBitmaps.isNotEmpty() &&
                (payload.sourceFormat == SourceFormat.OCR_IMAGE || payload.sourceFormat == SourceFormat.PDF)
            ) {
                model.generateContent(
                    content {
                        text(promptHeader)
                        payload.pageBitmaps.take(3).forEachIndexed { idx, bmp ->
                            text("Source Page ${idx + 1}:")
                            image(scaleBitmapForPayload(bmp))
                        }
                        if (payload.rawTextBlocks.isNotEmpty()) {
                            text("Auxiliary extracted text locus:\n" +
                                payload.rawTextBlocks.take(4).joinToString("\n\n") { "[${it.sourceSpan}] ${it.title}: ${it.text.take(1200)}" })
                        }
                    }
                )
            } else {
                val blocksText = payload.rawTextBlocks.take(6).joinToString("\n\n") {
                    "[${it.sourceSpan}] ${it.title}\n${it.text.take(1500)}"
                }
                model.generateContent("$promptHeader\nSOURCE TEXT LOCUS:\n$blocksText")
            }
        }

        val jsonText = response.text ?: throw IllegalStateException("Cloud model returned empty JSON payload.")
        val root = JSONObject(jsonText)
        val sectionsArr = root.optJSONArray("sections") ?: JSONArray()

        val synthesized = mutableListOf<MarkovianKernel.CloudSynthesizedSectionInput>()
        val ocrBlocks = mutableListOf<RawSourceBlock>()

        for (i in 0 until sectionsArr.length()) {
            val secObj = sectionsArr.getJSONObject(i)
            val secId = secObj.optString("sectionId", "sec_$i").ifBlank { "sec_$i" }
            val secTitle = secObj.optString("title", "Section ${i + 1}")
            val reflow = secObj.optString("reflowText", "").trim()
            val tone = secObj.optString("prosodyTone", "balanced")
            val triplesArr = secObj.optJSONArray("triples") ?: JSONArray()
            val triples = mutableListOf<Triple<String, String, String>>()
            for (t in 0 until triplesArr.length()) {
                val tr = triplesArr.getJSONObject(t)
                val s = tr.optString("subject").trim()
                val p = tr.optString("predicate").trim()
                val o = tr.optString("objectValue").trim()
                if (s.isNotBlank() && p.isNotBlank() && o.isNotBlank()) {
                    triples.add(Triple(s, p, o))
                }
            }
            if (reflow.isNotBlank()) {
                synthesized.add(
                    MarkovianKernel.CloudSynthesizedSectionInput(
                        sectionId = secId,
                        title = secTitle,
                        reflowText = reflow,
                        citedUrns = emptyList(),
                        prosodyTone = tone,
                        triples = triples
                    )
                )
                ocrBlocks.add(
                    RawSourceBlock(
                        title = secTitle,
                        text = reflow,
                        sourceSpan = "cloud-ocr:sec_$i"
                    )
                )
            }
        }

        // Optional external Cloud REST Ingestion Gateway notification if user configured an endpoint
        if (!customRestEndpoint.isNullOrBlank() && customRestEndpoint.startsWith("https://")) {
            notifyCustomRestGateway(customRestEndpoint, payload.sourceDigest, root.optString("title", payload.title))
        }

        CloudIngestionResult(
            documentTitle = root.optString("title", payload.title).ifBlank { payload.title },
            author = root.optString("author", payload.author).ifBlank { payload.author },
            ditaTopicType = root.optString("ditaTopicType", "concept"),
            summary = root.optString("summary", "Cloud-attested neuro-symbolic ingestion complete."),
            synthesizedSections = synthesized,
            extractedBlocksForOcr = ocrBlocks.ifEmpty { payload.rawTextBlocks },
            attestedModel = "gemini-3.8-flash",
            cloudAttested = true
        )
    }.onFailure { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Cloud synthesis failed", e)
    }

    /**
     * Memoryless reseed anchored strictly to the sealed TruthManifest URN whitelist and narrow source locus.
     */
    suspend fun reseedSectionFromTruthLocus(
        sectionId: String,
        sourceLocusText: String,
        allowedUrns: Set<String>
    ): Result<String> = runCatching {
        val reseedModel = Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.05f
            }
        )
        val prompt = buildString {
            appendLine("STRICT MARKOVIAN RESEED — FEEDBACK INHIBITION ACTIVE")
            appendLine("Ignore all prior failed outputs. Rewrite ONLY the verbatim facts in the source locus below into clean readable prose.")
            appendLine("Allowed URN boundary: ${allowedUrns.take(4).joinToString(", ")}")
            appendLine("Section: $sectionId")
            appendLine("SOURCE LOCUS:")
            appendLine(sourceLocusText.take(1800))
        }
        val response = withTimeout(45_000L) {
            reseedModel.generateContent(prompt)
        }
        response.text?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Empty reseed response from cloud model.")
    }.onFailure { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Cloud reseed failed", e)
    }

    private suspend fun notifyCustomRestGateway(
        endpointUrl: String,
        sourceDigest: String,
        title: String
    ) = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("sourceDigest", sourceDigest)
                put("title", title)
                put("pipeline", "markovian-dita-rdf-v1")
            }.toString()
            val request = Request.Builder()
                .url(endpointUrl)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Log.d(TAG, "Custom REST ingestion gateway responded: ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Optional custom REST gateway call skipped/failed: ${e.message}")
        }
    }

    private fun scaleBitmapForPayload(bmp: Bitmap): Bitmap {
        val maxDim = maxOf(bmp.width, bmp.height)
        if (maxDim <= 1280) return bmp
        val scale = 1280f / maxDim.toFloat()
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
