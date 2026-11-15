package com.example.domain.pipeline

import com.example.data.model.DocumentSection
import com.example.data.model.LibraryDocument
import com.example.domain.parsers.DocumentContainerParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class DriftCheckStatus(val label: String) {
    VERIFIED_MATCH("Verified Match"),
    DRIFT_FLAGGED("Parsing Drift Flagged"),
    MISSING_LOCAL_SIDECAR("Missing Local Sidecar")
}

data class SidecarDriftReport(
    val documentId: String,
    val sidecarFileName: String,
    val localSidecarHash: String,
    val localRecordedMerkleHash: String,
    val firestoreStoredHash: String,
    val firestoreCanonicalHash: String,
    val status: DriftCheckStatus,
    val mismatchedSectionIds: List<String>,
    val driftReason: String,
    val checkedFromServer: Boolean,
    val bayesianFeedbackBlocked: Boolean = true
)

/**
 * Compares the current local state of a document's app-private sidecar file
 * (`filesDir/sidecars/<docId>.sidecar.json`) against the authoritative Firestore-stored hash
 * (`merkleRootHash` + canonical section locus checksums) to detect and flag parsing drift.
 *
 * Feedback Inhibition Control invariant: drift detection only flags output status and triggers
 * memoryless sidecar re-projection; it never mutates or feeds back into upstream source priors.
 */
class SidecarDriftService(private val rootFilesDir: File) {

    private val sidecarDir: File
        get() = File(rootFilesDir, "sidecars").apply {
            if (!exists()) mkdirs()
        }

    fun sidecarFileFor(documentId: String): File {
        val safeId = documentId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "unknown" }
        return File(sidecarDir, "$safeId.sidecar.json")
    }

    fun hasLocalSidecar(documentId: String): Boolean = sidecarFileFor(documentId).exists()

    /**
     * Writes the authoritative local sidecar file from the verified document and section state.
     */
    fun writeLocalSidecar(
        document: LibraryDocument,
        sections: List<DocumentSection>
    ): File {
        val sortedSections = sections.sortedBy { it.orderIndex }
        val canonicalHash = computeCanonicalSectionsHash(
            documentId = document.id,
            sourceDigest = document.sourceDigest,
            truthManifestHash = document.truthManifestHash,
            sections = sortedSections
        )

        val sectionsArr = JSONArray()
        sortedSections.forEach { sec ->
            sectionsArr.put(
                JSONObject().apply {
                    put("sectionId", sec.id)
                    put("orderIndex", sec.orderIndex)
                    put("urn", sec.urn)
                    put("locusChecksum", sec.locusChecksum)
                    put("provenanceChainHash", sec.provenanceChainHash)
                    put("dofScore", sec.dofScore)
                    put("plainTextHash", DocumentContainerParser.sha256Hex(sec.plainText))
                }
            )
        }

        val root = JSONObject().apply {
            put("schemaVersion", "1.0")
            put("documentId", document.id)
            put("sourceDigest", document.sourceDigest)
            put("truthManifestHash", document.truthManifestHash)
            put("firestoreMerkleRootHash", document.merkleRootHash)
            put("canonicalSidecarHash", canonicalHash)
            put("sections", sectionsArr)
        }

        val file = sidecarFileFor(document.id)
        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    /**
     * Simulates local sidecar tampering or uncalibrated parsing drift on disk without touching
     * Firestore priors, allowing verification of hash mismatch detection and drift flagging.
     */
    fun injectSimulatedLocalDrift(documentId: String): Boolean {
        val file = sidecarFileFor(documentId)
        if (!file.exists()) return false
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val sectionsArr = root.optJSONArray("sections") ?: JSONArray()
        if (sectionsArr.length() > 0) {
            val first = sectionsArr.getJSONObject(0)
            first.put("plainTextHash", DocumentContainerParser.sha256Hex("drifted_unanchored_local_sidecar_mutation"))
            first.put("dofScore", 0.91)
        }
        // Also tamper with local recorded merkle hash or leave canonicalSidecarHash stale to emulate silent drift
        root.put("simulatedDriftInjected", true)
        file.writeText(root.toString(2), Charsets.UTF_8)
        return true
    }

    /**
     * Compares the current on-disk sidecar file state against the Firestore-stored hashes.
     * Recomputes SHA-256 directly from the local file's section state so silent edits or corruption
     * are detected even if metadata fields inside the file were left unchanged.
     */
    fun compareLocalSidecarWithFirestore(
        firestoreDocument: LibraryDocument,
        firestoreSections: List<DocumentSection>,
        checkedFromServer: Boolean
    ): SidecarDriftReport {
        val file = sidecarFileFor(firestoreDocument.id)
        val sortedFirestoreSections = firestoreSections.sortedBy { it.orderIndex }
        val firestoreCanonicalHash = computeCanonicalSectionsHash(
            documentId = firestoreDocument.id,
            sourceDigest = firestoreDocument.sourceDigest,
            truthManifestHash = firestoreDocument.truthManifestHash,
            sections = sortedFirestoreSections
        )

        if (!file.exists()) {
            return SidecarDriftReport(
                documentId = firestoreDocument.id,
                sidecarFileName = "sidecars/${file.name}",
                localSidecarHash = "MISSING",
                localRecordedMerkleHash = "MISSING",
                firestoreStoredHash = firestoreDocument.merkleRootHash,
                firestoreCanonicalHash = firestoreCanonicalHash,
                status = DriftCheckStatus.MISSING_LOCAL_SIDECAR,
                mismatchedSectionIds = sortedFirestoreSections.map { it.id },
                driftReason = "Local sidecar file not present on device; restore from Cloud Firestore.",
                checkedFromServer = checkedFromServer
            )
        }

        val parsed = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
        if (parsed == null) {
            return SidecarDriftReport(
                documentId = firestoreDocument.id,
                sidecarFileName = "sidecars/${file.name}",
                localSidecarHash = DocumentContainerParser.sha256Hex(file.readBytes()),
                localRecordedMerkleHash = "CORRUPT_JSON",
                firestoreStoredHash = firestoreDocument.merkleRootHash,
                firestoreCanonicalHash = firestoreCanonicalHash,
                status = DriftCheckStatus.DRIFT_FLAGGED,
                mismatchedSectionIds = sortedFirestoreSections.map { it.id },
                driftReason = "Local sidecar file is malformed JSON and fails Firestore hash verification.",
                checkedFromServer = checkedFromServer
            )
        }

        val localRecordedMerkle = parsed.optString("firestoreMerkleRootHash", "")
        val localSourceDigest = parsed.optString("sourceDigest", "")
        val localManifestHash = parsed.optString("truthManifestHash", "")
        val localSectionsArr = parsed.optJSONArray("sections") ?: JSONArray()

        val localSectionHashes = mutableMapOf<String, String>()
        val localMaterial = buildString {
            append(firestoreDocument.id).append('|')
            append(localSourceDigest).append('|')
            append(localManifestHash).append('|')
            for (i in 0 until localSectionsArr.length()) {
                val obj = localSectionsArr.getJSONObject(i)
                val secId = obj.optString("sectionId")
                val urn = obj.optString("urn")
                val locus = obj.optString("locusChecksum")
                val plainHash = obj.optString("plainTextHash")
                val chain = obj.optString("provenanceChainHash")
                val secComposite = "$secId:$urn:$locus:$plainHash:$chain"
                localSectionHashes[secId] = DocumentContainerParser.sha256Hex(secComposite)
                append(secComposite).append(';')
            }
        }
        val computedLocalHash = DocumentContainerParser.sha256Hex(localMaterial)

        val mismatchedSections = mutableListOf<String>()
        sortedFirestoreSections.forEach { sec ->
            val expectedSecHash = DocumentContainerParser.sha256Hex(
                "${sec.id}:${sec.urn}:${sec.locusChecksum}:${DocumentContainerParser.sha256Hex(sec.plainText)}:${sec.provenanceChainHash}"
            )
            if (localSectionHashes[sec.id] != expectedSecHash) {
                mismatchedSections.add(sec.id)
            }
        }
        if (localSectionsArr.length() != sortedFirestoreSections.size) {
            sortedFirestoreSections.forEach { if (it.id !in mismatchedSections) mismatchedSections.add(it.id) }
        }

        val reasons = mutableListOf<String>()
        if (localRecordedMerkle != firestoreDocument.merkleRootHash) {
            reasons.add("merkleRootHash mismatch (local=${localRecordedMerkle.take(12)} vs cloud=${firestoreDocument.merkleRootHash.take(12)})")
        }
        if (localManifestHash != firestoreDocument.truthManifestHash) {
            reasons.add("TruthManifest hash diverged")
        }
        if (localSourceDigest != firestoreDocument.sourceDigest) {
            reasons.add("sourceDigest diverged")
        }
        if (computedLocalHash != firestoreCanonicalHash) {
            reasons.add("canonical section payload hash mismatch")
        }
        if (mismatchedSections.isNotEmpty()) {
            reasons.add("${mismatchedSections.size} drifted section(s): ${mismatchedSections.joinToString(", ")}")
        }

        val status = if (reasons.isEmpty()) DriftCheckStatus.VERIFIED_MATCH else DriftCheckStatus.DRIFT_FLAGGED
        val summaryReason = if (reasons.isEmpty()) {
            "Local sidecar file matches Firestore-stored hash and all ${sortedFirestoreSections.size} section loci."
        } else {
            reasons.joinToString("; ")
        }

        return SidecarDriftReport(
            documentId = firestoreDocument.id,
            sidecarFileName = "sidecars/${file.name}",
            localSidecarHash = computedLocalHash,
            localRecordedMerkleHash = localRecordedMerkle,
            firestoreStoredHash = firestoreDocument.merkleRootHash,
            firestoreCanonicalHash = firestoreCanonicalHash,
            status = status,
            mismatchedSectionIds = mismatchedSections,
            driftReason = summaryReason,
            checkedFromServer = checkedFromServer
        )
    }

    fun computeCanonicalSectionsHash(
        documentId: String,
        sourceDigest: String,
        truthManifestHash: String,
        sections: List<DocumentSection>
    ): String {
        val material = buildString {
            append(documentId).append('|')
            append(sourceDigest).append('|')
            append(truthManifestHash).append('|')
            sections.sortedBy { it.orderIndex }.forEach { sec ->
                val plainHash = DocumentContainerParser.sha256Hex(sec.plainText)
                append("${sec.id}:${sec.urn}:${sec.locusChecksum}:$plainHash:${sec.provenanceChainHash}").append(';')
            }
        }
        return DocumentContainerParser.sha256Hex(material)
    }
}
