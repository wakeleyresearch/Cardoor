package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AuditLogEntry
import com.example.data.model.DocumentSection
import com.example.data.model.KnowledgeTriple
import com.example.data.model.LibraryDocument
import com.example.data.model.ProvenanceStatus
import com.example.data.model.StandoffAnnotation
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FolioRepository(
    private val db: FirebaseFirestore,
    private val authProvider: () -> com.google.firebase.auth.FirebaseAuth = { Firebase.auth }
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return authProvider().currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun isValidDocId(id: String): String {
        val cleaned = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return if (cleaned.isBlank()) UUID.randomUUID().toString() else cleaned.take(120)
    }

    suspend fun ensureUserProfile(
        displayName: String,
        email: String,
        preferredRole: UserRole = UserRole.CURATOR
    ): Result<UserProfile> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val snapshot = docRef.get().await()
        if (snapshot.exists()) {
            snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: UserProfile(id = uid, userId = uid, displayName = displayName, email = email)
        } else {
            val safeName = displayName.ifBlank { "Archivist" }.take(100)
            val safeEmail = email.ifBlank { "archivist@foliograph.app" }.take(200)
            val safeRole = if (preferredRole == UserRole.EDITOR) UserRole.CURATOR.wireValue else preferredRole.wireValue
            val payload = mapOf(
                "userId" to uid,
                "displayName" to safeName,
                "email" to safeEmail,
                "role" to safeRole,
                "preferredTheme" to "system",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            UserProfile(
                id = uid,
                userId = uid,
                displayName = safeName,
                email = safeEmail,
                role = safeRole,
                preferredTheme = "system"
            )
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid")
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    unwrapAndLogFirestoreError(error, OperationType.GET, path, userId)
                }
        )
    }

    suspend fun updateProfilePreferences(
        displayName: String,
        preferredTheme: String,
        cloudRestEndpoint: String?
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val updates = mutableMapOf<String, Any?>(
            "displayName" to displayName.ifBlank { "Archivist" }.take(100),
            "preferredTheme" to preferredTheme.take(20),
            "cloudRestEndpoint" to cloudRestEndpoint?.take(300),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.update(updates.filterValues { it != null }).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
    }

    fun observeDocuments(userId: String): Flow<List<LibraryDocument>> = flow {
        val path = "users/$userId/documents"
        emitAll(
            db.collection("users").document(userId).collection("documents")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots(MetadataChanges.INCLUDE)
                .map { snapshot ->
                    val pending = snapshot.metadata.hasPendingWrites()
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(LibraryDocument::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?.copy(
                                hasPendingWrites = doc.metadata.hasPendingWrites() || pending,
                                provenanceStatus = if (doc.metadata.hasPendingWrites()) {
                                    ProvenanceStatus.PENDING_ATTESTATION.wireValue
                                } else {
                                    doc.getString("provenanceStatus") ?: ProvenanceStatus.CLOUD_VERIFIED.wireValue
                                }
                            )
                    }
                }
                .catch { error ->
                    unwrapAndLogFirestoreError(error, OperationType.LIST, path, userId)
                }
        )
    }

    fun observeSections(userId: String, docId: String): Flow<List<DocumentSection>> = flow {
        val path = "users/$userId/documents/$docId/sections"
        emitAll(
            db.collection("users").document(userId)
                .collection("documents").document(docId)
                .collection("sections")
                .orderBy("orderIndex", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(DocumentSection::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    unwrapAndLogFirestoreError(error, OperationType.LIST, path, userId)
                }
        )
    }

    fun observeTriples(userId: String, docId: String): Flow<List<KnowledgeTriple>> = flow {
        val path = "users/$userId/documents/$docId/triples"
        emitAll(
            db.collection("users").document(userId)
                .collection("documents").document(docId)
                .collection("triples")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(KnowledgeTriple::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    unwrapAndLogFirestoreError(error, OperationType.LIST, path, userId)
                }
        )
    }

    fun observeAnnotations(userId: String, docId: String): Flow<List<StandoffAnnotation>> = flow {
        val path = "users/$userId/documents/$docId/annotations"
        emitAll(
            db.collection("users").document(userId)
                .collection("documents").document(docId)
                .collection("annotations")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(StandoffAnnotation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    unwrapAndLogFirestoreError(error, OperationType.LIST, path, userId)
                }
        )
    }

    fun observeAuditLogs(userId: String): Flow<List<AuditLogEntry>> = flow {
        val path = "users/$userId/audit_logs"
        emitAll(
            db.collection("users").document(userId)
                .collection("audit_logs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(60)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(AuditLogEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    unwrapAndLogFirestoreError(error, OperationType.LIST, path, userId)
                }
        )
    }

    suspend fun saveIngestedDocumentBundle(
        document: LibraryDocument,
        sections: List<DocumentSection>,
        triples: List<KnowledgeTriple>,
        auditLogs: List<AuditLogEntry>
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docId = isValidDocId(document.id.ifBlank { "doc_${UUID.randomUUID().toString().take(12)}" })
        val userRef = db.collection("users").document(uid)
        val docRef = userRef.collection("documents").document(docId)

        val batch = db.batch()
        val docPayload = mapOf(
            "userId" to uid,
            "title" to document.title.ifBlank { "Untitled Folio" }.take(200),
            "author" to document.author.take(150),
            "sourceFormat" to document.sourceFormat,
            "ditaTopicType" to document.ditaTopicType,
            "sourceDigest" to document.sourceDigest.padEnd(16, '0').take(128),
            "provenanceStatus" to document.provenanceStatus,
            "attestedModel" to document.attestedModel.ifBlank { "gemini-3.8-flash" }.take(80),
            "truthManifestHash" to document.truthManifestHash.padEnd(16, '0').take(128),
            "merkleRootHash" to document.merkleRootHash.padEnd(16, '0').take(128),
            "shaclScore" to document.shaclScore.coerceIn(0.0, 1.0),
            "dofScore" to document.dofScore.coerceIn(0.0, 1.0),
            "bayesianFeedbackBlocked" to true,
            "sectionCount" to sections.size.toLong(),
            "tripleCount" to triples.size.toLong(),
            "summary" to document.summary.take(1000),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(docRef, docPayload)

        sections.take(40).forEachIndexed { index, sec ->
            val secId = isValidDocId(sec.id.ifBlank { "sec_${index}_${UUID.randomUUID().toString().take(6)}" })
            val secRef = docRef.collection("sections").document(secId)
            val secPayload = mapOf(
                "documentId" to docId,
                "orderIndex" to sec.orderIndex,
                "title" to sec.title.take(200),
                "plainText" to sec.plainText.ifBlank { " " }.take(20000),
                "ditaXml" to sec.ditaXml.ifBlank { "<section/>" }.take(30000),
                "ssmlProsody" to sec.ssmlProsody.ifBlank { "<speak/>" }.take(30000),
                "urn" to sec.urn.ifBlank { "urn:foliograph:sha256:00000000" }.take(150),
                "sourceSpan" to sec.sourceSpan.ifBlank { "span:0" }.take(120),
                "locusChecksum" to sec.locusChecksum.padEnd(16, '0').take(128),
                "provenanceChainHash" to sec.provenanceChainHash.padEnd(16, '0').take(128),
                "dofScore" to sec.dofScore.coerceIn(0.0, 1.0),
                "locusDriftDistance" to sec.locusDriftDistance.coerceIn(0.0, 1.0),
                "reseedCount" to sec.reseedCount.coerceIn(0L, 10L),
                "sidecarStatus" to sec.sidecarStatus,
                "convergenceType" to sec.convergenceType,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            batch.set(secRef, secPayload)
        }

        triples.take(100).forEachIndexed { index, triple ->
            val tripleId = isValidDocId(triple.id.ifBlank { "tr_${index}_${UUID.randomUUID().toString().take(6)}" })
            val tripleRef = docRef.collection("triples").document(tripleId)
            val triplePayload = mapOf(
                "documentId" to docId,
                "sectionId" to isValidDocId(triple.sectionId.ifBlank { "sec_0" }),
                "urn" to triple.urn.ifBlank { "urn:foliograph:sha256:00000000" }.take(150),
                "subject" to triple.subject.ifBlank { "Document" }.take(200),
                "predicate" to triple.predicate.ifBlank { "schema:about" }.take(200),
                "objectValue" to triple.objectValue.ifBlank { "Entity" }.take(300),
                "jsonLdContext" to triple.jsonLdContext.ifBlank { "https://schema.org/" }.take(250),
                "shaclStatus" to triple.shaclStatus,
                "confidence" to triple.confidence.coerceIn(0.0, 1.0),
                "createdAt" to FieldValue.serverTimestamp()
            )
            batch.set(tripleRef, triplePayload)
        }

        auditLogs.take(20).forEach { log ->
            val logId = isValidDocId(log.id.ifBlank { "log_${UUID.randomUUID().toString().take(12)}" })
            val logRef = userRef.collection("audit_logs").document(logId)
            val logPayload = mapOf(
                "action" to log.action,
                "documentId" to docId,
                "patchId" to log.patchId.ifBlank { "patch-06" }.take(40),
                "sourceDigest" to log.sourceDigest.take(128),
                "inputStateHash" to log.inputStateHash.padEnd(16, '0').take(128),
                "outputStateHash" to log.outputStateHash.padEnd(16, '0').take(128),
                "truthManifestHash" to log.truthManifestHash.padEnd(16, '0').take(128),
                "dofScore" to log.dofScore.coerceIn(0.0, 1.0),
                "bayesianFeedbackBlocked" to true,
                "latencyMs" to log.latencyMs.coerceIn(0L, 600000L),
                "details" to log.details.take(500),
                "createdAt" to FieldValue.serverTimestamp()
            )
            batch.set(logRef, logPayload)
        }

        batch.commit().await()
        docId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid/documents")
    }

    suspend fun updateSectionAfterSidecarReplay(
        docId: String,
        section: DocumentSection,
        auditLog: AuditLogEntry
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(section.id.isNotBlank()) { "Section ID must not be blank" }
        val userRef = db.collection("users").document(uid)
        val secRef = userRef.collection("documents").document(docId)
            .collection("sections").document(section.id)
        val logId = isValidDocId("log_${UUID.randomUUID().toString().take(12)}")
        val logRef = userRef.collection("audit_logs").document(logId)

        val batch = db.batch()
        batch.update(
            secRef,
            mapOf(
                "title" to section.title.take(200),
                "plainText" to section.plainText.ifBlank { " " }.take(20000),
                "ditaXml" to section.ditaXml.ifBlank { "<section/>" }.take(30000),
                "ssmlProsody" to section.ssmlProsody.ifBlank { "<speak/>" }.take(30000),
                "urn" to section.urn.take(150),
                "provenanceChainHash" to section.provenanceChainHash.padEnd(16, '0').take(128),
                "dofScore" to section.dofScore.coerceIn(0.0, 1.0),
                "locusDriftDistance" to section.locusDriftDistance.coerceIn(0.0, 1.0),
                "reseedCount" to section.reseedCount.coerceIn(0L, 10L),
                "sidecarStatus" to section.sidecarStatus,
                "convergenceType" to section.convergenceType,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            logRef,
            mapOf(
                "action" to auditLog.action,
                "documentId" to docId,
                "patchId" to auditLog.patchId.take(40),
                "sourceDigest" to auditLog.sourceDigest.take(128),
                "inputStateHash" to auditLog.inputStateHash.padEnd(16, '0').take(128),
                "outputStateHash" to auditLog.outputStateHash.padEnd(16, '0').take(128),
                "truthManifestHash" to auditLog.truthManifestHash.padEnd(16, '0').take(128),
                "dofScore" to auditLog.dofScore.coerceIn(0.0, 1.0),
                "bayesianFeedbackBlocked" to true,
                "latencyMs" to auditLog.latencyMs.coerceIn(0L, 600000L),
                "details" to auditLog.details.take(500),
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid/documents/$docId/sections/${section.id}")
    }

    suspend fun addStandoffAnnotation(
        docId: String,
        sectionId: String,
        startOffset: Int,
        endOffset: Int,
        selectedText: String,
        note: String,
        tagColor: String,
        authorRole: UserRole
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val annId = isValidDocId("ann_${UUID.randomUUID().toString().take(12)}")
        val docRef = db.collection("users").document(uid)
            .collection("documents").document(docId)
            .collection("annotations").document(annId)

        val payload = mapOf(
            "documentId" to docId,
            "sectionId" to isValidDocId(sectionId),
            "startOffset" to startOffset.coerceAtLeast(0).toLong(),
            "endOffset" to endOffset.coerceAtLeast(startOffset).toLong(),
            "selectedText" to selectedText.ifBlank { "Excerpt" }.take(1000),
            "note" to note.take(2000),
            "tagColor" to tagColor,
            "authorRole" to authorRole.wireValue,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()
        annId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.CREATE, "users/$uid/documents/$docId/annotations")
    }

    suspend fun deleteAnnotation(docId: String, annotationId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        db.collection("users").document(uid)
            .collection("documents").document(docId)
            .collection("annotations").document(annotationId)
            .delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/documents/$docId/annotations/$annotationId")
    }

    suspend fun deleteDocument(docId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        db.collection("users").document(uid)
            .collection("documents").document(docId)
            .delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/documents/$docId")
    }

    suspend fun fetchDocumentAndSectionsForDriftCheck(
        docId: String
    ): Result<Triple<LibraryDocument, List<DocumentSection>, Boolean>> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("documents").document(docId)
        val sectionsQuery = docRef.collection("sections").orderBy("orderIndex", Query.Direction.ASCENDING)

        var checkedFromServer = true
        val docSnap = try {
            docRef.get(Source.SERVER).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            checkedFromServer = false
            docRef.get(Source.DEFAULT).await()
        }

        val doc = docSnap.toObject(LibraryDocument::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?: throw IllegalStateException("Document '$docId' does not exist in Firestore.")

        val secSnap = try {
            if (checkedFromServer) sectionsQuery.get(Source.SERVER).await() else sectionsQuery.get(Source.DEFAULT).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            checkedFromServer = false
            sectionsQuery.get(Source.DEFAULT).await()
        }

        val sections = secSnap.toObjects(DocumentSection::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        Triple(doc, sections, checkedFromServer)
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.GET, "users/$uid/documents/$docId")
    }

    suspend fun flagDocumentParsingDrift(
        docId: String,
        provenanceStatus: ProvenanceStatus,
        dofScore: Double? = null,
        auditLog: AuditLogEntry
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val docRef = userRef.collection("documents").document(docId)
        val logId = isValidDocId(auditLog.id.ifBlank { "log_${UUID.randomUUID().toString().take(12)}" })
        val logRef = userRef.collection("audit_logs").document(logId)

        val updates = mutableMapOf<String, Any>(
            "provenanceStatus" to provenanceStatus.wireValue,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (dofScore != null) {
            updates["dofScore"] = dofScore.coerceIn(0.0, 1.0)
        }

        val batch = db.batch()
        batch.update(docRef, updates)
        batch.set(
            logRef,
            mapOf(
                "action" to auditLog.action,
                "documentId" to docId,
                "patchId" to auditLog.patchId.ifBlank { "patch-06" }.take(40),
                "sourceDigest" to auditLog.sourceDigest.take(128),
                "inputStateHash" to auditLog.inputStateHash.padEnd(16, '0').take(128),
                "outputStateHash" to auditLog.outputStateHash.padEnd(16, '0').take(128),
                "truthManifestHash" to auditLog.truthManifestHash.padEnd(16, '0').take(128),
                "dofScore" to auditLog.dofScore.coerceIn(0.0, 1.0),
                "bayesianFeedbackBlocked" to true,
                "latencyMs" to auditLog.latencyMs.coerceIn(0L, 600000L),
                "details" to auditLog.details.take(500),
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid/documents/$docId")
    }

    suspend fun appendAuditLog(entry: AuditLogEntry): Result<String> = runCatching {
        val uid = requireUserId()
        val logId = isValidDocId(entry.id.ifBlank { "log_${UUID.randomUUID().toString().take(12)}" })
        val logRef = db.collection("users").document(uid)
            .collection("audit_logs").document(logId)
        val payload = mapOf(
            "action" to entry.action,
            "documentId" to entry.documentId.ifBlank { "workspace" }.take(128),
            "patchId" to entry.patchId.ifBlank { "patch-00" }.take(40),
            "sourceDigest" to entry.sourceDigest.take(128),
            "inputStateHash" to entry.inputStateHash.padEnd(16, '0').take(128),
            "outputStateHash" to entry.outputStateHash.padEnd(16, '0').take(128),
            "truthManifestHash" to entry.truthManifestHash.padEnd(16, '0').take(128),
            "dofScore" to entry.dofScore.coerceIn(0.0, 1.0),
            "bayesianFeedbackBlocked" to true,
            "latencyMs" to entry.latencyMs.coerceIn(0L, 600000L),
            "details" to entry.details.take(500),
            "createdAt" to FieldValue.serverTimestamp()
        )
        logRef.set(payload).await()
        logId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = authProvider().currentUser?.uid ?: "unknown"
        handleFirestoreError(e, OperationType.CREATE, "users/$uid/audit_logs")
    }

    private fun unwrapAndLogFirestoreError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        uid: String
    ): Nothing {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: error
        if (authProvider().currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
        throw error
    }
}
