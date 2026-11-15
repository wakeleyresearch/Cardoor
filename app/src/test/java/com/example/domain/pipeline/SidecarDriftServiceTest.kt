package com.example.domain.pipeline

import com.example.data.model.DocumentSection
import com.example.data.model.LibraryDocument
import com.example.data.model.ProvenanceStatus
import com.example.domain.parsers.DocumentContainerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SidecarDriftServiceTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun sampleDocAndSections(): Pair<LibraryDocument, List<DocumentSection>> {
        val text = "DITA 1.3 concepts remain locked inside the sealed Markov Blanket."
        val digest = DocumentContainerParser.sha256Hex(text)
        val manifestHash = DocumentContainerParser.sha256Hex("manifest:$digest")
        val merkleHash = DocumentContainerParser.sha256Hex("merkle:$manifestHash")

        val doc = LibraryDocument(
            id = "doc_sidecar_01",
            userId = "user_01",
            title = "Markovian Reference",
            sourceDigest = digest,
            provenanceStatus = ProvenanceStatus.CLOUD_VERIFIED.wireValue,
            truthManifestHash = manifestHash,
            merkleRootHash = merkleHash
        )
        val section = DocumentSection(
            id = "sec_0",
            documentId = "doc_sidecar_01",
            orderIndex = 0L,
            title = "Blanket",
            plainText = text,
            urn = "urn:foliograph:sha256:${digest.take(16)}",
            locusChecksum = digest,
            provenanceChainHash = merkleHash,
            dofScore = 0.08
        )
        return doc to listOf(section)
    }

    @Test
    fun verifiedSidecarMatchesFirestoreHashAndDetectsLocalOrCloudDrift() {
        val service = SidecarDriftService(tempDir.root)
        val (doc, sections) = sampleDocAndSections()

        // 1. Missing local sidecar is flagged
        val missingReport = service.compareLocalSidecarWithFirestore(doc, sections, checkedFromServer = true)
        assertEquals(DriftCheckStatus.MISSING_LOCAL_SIDECAR, missingReport.status)

        // 2. Writing authoritative sidecar yields Verified Match
        service.writeLocalSidecar(doc, sections)
        val verifiedReport = service.compareLocalSidecarWithFirestore(doc, sections, checkedFromServer = true)
        assertEquals(DriftCheckStatus.VERIFIED_MATCH, verifiedReport.status)
        assertEquals(verifiedReport.firestoreCanonicalHash, verifiedReport.localSidecarHash)
        assertTrue(verifiedReport.mismatchedSectionIds.isEmpty())
        assertTrue(verifiedReport.bayesianFeedbackBlocked)

        // 3. Simulated tampering in local sidecar triggers DRIFT_FLAGGED without mutating priors
        assertTrue(service.injectSimulatedLocalDrift(doc.id))
        val driftedReport = service.compareLocalSidecarWithFirestore(doc, sections, checkedFromServer = true)
        assertEquals(DriftCheckStatus.DRIFT_FLAGGED, driftedReport.status)
        assertTrue("sec_0" in driftedReport.mismatchedSectionIds)
        assertNotEquals(driftedReport.firestoreCanonicalHash, driftedReport.localSidecarHash)

        // 4. Restoring sidecar matches again, while a Firestore merkleRootHash change also triggers drift
        service.writeLocalSidecar(doc, sections)
        val updatedCloudDoc = doc.copy(merkleRootHash = DocumentContainerParser.sha256Hex("newer_cloud_state"))
        val cloudMismatchReport = service.compareLocalSidecarWithFirestore(updatedCloudDoc, sections, checkedFromServer = true)
        assertEquals(DriftCheckStatus.DRIFT_FLAGGED, cloudMismatchReport.status)
        assertTrue(cloudMismatchReport.driftReason.contains("merkleRootHash mismatch"))
    }
}
