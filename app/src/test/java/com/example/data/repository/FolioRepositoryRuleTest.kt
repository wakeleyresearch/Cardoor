package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.AuditLogEntry
import com.example.data.model.ConvergenceType
import com.example.data.model.DocumentSection
import com.example.data.model.KnowledgeTriple
import com.example.data.model.LibraryDocument
import com.example.data.model.ProvenanceStatus
import com.example.data.model.SidecarStatus
import com.example.data.model.SourceFormat
import com.example.data.model.UserRole
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FolioRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun createRepo(): FolioRepository = FolioRepository(firestore) { auth }

    private fun sampleDocument(uid: String, docId: String) = LibraryDocument(
        id = docId,
        userId = uid,
        title = "Markovian Treatise ${docId.take(6)}",
        author = "FolioGraph Archivist",
        sourceFormat = SourceFormat.PDF.wireValue,
        ditaTopicType = "concept",
        sourceDigest = "sha256_0123456789abcdef0123456789abcdef",
        provenanceStatus = ProvenanceStatus.CLOUD_VERIFIED.wireValue,
        attestedModel = "gemini-3.8-flash",
        truthManifestHash = "manifest_0123456789abcdef",
        merkleRootHash = "merkle_0123456789abcdef",
        shaclScore = 0.96,
        dofScore = 0.14,
        bayesianFeedbackBlocked = true,
        sectionCount = 1L,
        tripleCount = 1L,
        summary = "Grounded neuro-symbolic sample."
    )

    private fun sampleSection(docId: String) = DocumentSection(
        id = "sec_0",
        documentId = docId,
        orderIndex = 0L,
        title = "Axiom 1",
        plainText = "Strict Markovian isolation blocks output-to-prior Bayesian drift.",
        ditaXml = "<concept id=\"sec_0\"><title>Axiom 1</title><conbody><p>Strict Markovian isolation blocks output-to-prior Bayesian drift.</p></conbody></concept>",
        ssmlProsody = "<speak><prosody rate=\"medium\">Strict Markovian isolation blocks output-to-prior Bayesian drift.</prosody></speak>",
        urn = "urn:foliograph:sha256:0123456789abcdef",
        sourceSpan = "page:1:0-64",
        locusChecksum = "locus_0123456789abcdef",
        provenanceChainHash = "chain_0123456789abcdef",
        dofScore = 0.11,
        locusDriftDistance = 0.06,
        reseedCount = 0L,
        sidecarStatus = SidecarStatus.CLOUD_LOCUS_LOCKED.wireValue,
        convergenceType = ConvergenceType.DIRECT_PROJECTION.wireValue
    )

    @Test
    fun ensureProfileAndSaveBundle_authenticatedOwner_succeedsAndStreams() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = createRepo()

        val profileResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("Alice", ALICE_EMAIL, UserRole.CURATOR)
        }
        assertTrue(profileResult.isSuccess)

        val docId = "doc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val bundleResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveIngestedDocumentBundle(
                document = sampleDocument(uid, docId),
                sections = listOf(sampleSection(docId)),
                triples = listOf(
                    KnowledgeTriple(
                        id = "tr_0",
                        documentId = docId,
                        sectionId = "sec_0",
                        urn = "urn:foliograph:sha256:triple01234567",
                        subject = "MarkovianKernel",
                        predicate = "blocks",
                        objectValue = "BayesianFalseBeliefUpdating",
                        jsonLdContext = "https://schema.org/",
                        shaclStatus = "PASSED",
                        confidence = 0.99
                    )
                ),
                auditLogs = listOf(
                    AuditLogEntry(
                        id = "log_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                        action = "PROVENANCE_COMMIT",
                        documentId = docId,
                        patchId = "patch-06",
                        sourceDigest = "sha256_0123456789abcdef",
                        inputStateHash = "in_0123456789abcdef",
                        outputStateHash = "out_0123456789abcdef",
                        truthManifestHash = "manifest_0123456789abcdef",
                        dofScore = 0.11,
                        bayesianFeedbackBlocked = true,
                        latencyMs = 250L,
                        details = "Verified Markovian bundle."
                    )
                )
            )
        }
        assertTrue("Bundle save failed: ${bundleResult.exceptionOrNull()}", bundleResult.isSuccess)

        val docs = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeDocuments(uid).first { list -> list.any { it.id == docId } }
        }
        assertTrue(docs.any { it.id == docId && it.bayesianFeedbackBlocked })

        val annResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addStandoffAnnotation(
                docId = docId,
                sectionId = "sec_0",
                startOffset = 0,
                endOffset = 25,
                selectedText = "Strict Markovian isolation",
                note = "Core invariant",
                tagColor = "TERRACOTTA",
                authorRole = UserRole.CURATOR
            )
        }
        assertTrue(annResult.isSuccess)

        val flagResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.flagDocumentParsingDrift(
                docId = docId,
                provenanceStatus = ProvenanceStatus.RESEED_REQUIRED,
                dofScore = 0.85,
                auditLog = AuditLogEntry(
                    id = "log_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                    action = "SIDECAR_CHECK",
                    documentId = docId,
                    patchId = "patch-06",
                    sourceDigest = "sha256_0123456789abcdef",
                    inputStateHash = "local_0123456789abcdef",
                    outputStateHash = "cloud_0123456789abcdef",
                    truthManifestHash = "manifest_0123456789abcdef",
                    dofScore = 0.85,
                    bayesianFeedbackBlocked = true,
                    latencyMs = 12L,
                    details = "[Parsing Drift Flagged] Simulated hash mismatch"
                )
            )
        }
        assertTrue("Drift flagging failed: ${flagResult.exceptionOrNull()}", flagResult.isSuccess)

        val fetched = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchDocumentAndSectionsForDriftCheck(docId)
        }
        assertTrue("Drift check fetch failed: ${fetched.exceptionOrNull()}", fetched.isSuccess)
        assertEquals(ProvenanceStatus.RESEED_REQUIRED.wireValue, fetched.getOrThrow().first.provenanceStatus)
        assertEquals(1, fetched.getOrThrow().second.size)
    }

    @Test
    fun observeDocuments_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = createRepo()
        val docId = "doc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.saveIngestedDocumentBundle(
                document = sampleDocument(aliceUid, docId),
                sections = listOf(sampleSection(docId)),
                triples = emptyList(),
                auditLogs = emptyList()
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = createRepo()
        var thrown: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeDocuments(aliceUid).first()
            }
        } catch (t: Throwable) {
            thrown = t
        }
        val firestoreException = checkNotNull(thrown?.unwrapFirestoreException()) {
            "Expected FirebaseFirestoreException for cross-user read, got $thrown"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    @Test
    fun observeDocuments_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = createRepo()
        var thrown: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeDocuments("alice_unauth_target").first()
            }
        } catch (t: Throwable) {
            thrown = t
        }
        val firestoreException = checkNotNull(thrown?.unwrapFirestoreException()) {
            "Expected FirebaseFirestoreException for unauthenticated read, got $thrown"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice.foliograph@test.com"
        const val BOB_EMAIL = "bob.foliograph@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
