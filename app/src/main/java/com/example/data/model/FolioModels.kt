package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class UserRole(val wireValue: String, val displayLabel: String) {
    CURATOR("curator", "Curator"),
    EDITOR("editor", "Editor"),
    READER("reader", "Reader");

    fun canIngestOrReseed(): Boolean = this == CURATOR || this == EDITOR
    fun canAnnotate(): Boolean = true

    companion object {
        fun fromWire(value: String): UserRole =
            entries.firstOrNull { it.wireValue == value } ?: CURATOR
    }
}

enum class SourceFormat(val wireValue: String) {
    PDF("PDF"),
    EPUB("EPUB"),
    MOBI("MOBI"),
    OCR_IMAGE("OCR_IMAGE"),
    MARKDOWN_DITA("MARKDOWN_DITA");

    companion object {
        fun fromWire(value: String): SourceFormat =
            entries.firstOrNull { it.wireValue == value } ?: PDF
    }
}

enum class ProvenanceStatus(val wireValue: String, val label: String) {
    CLOUD_VERIFIED("CLOUD_VERIFIED", "Cloud Verified"),
    PENDING_ATTESTATION("PENDING_ATTESTATION", "Pending Attestation"),
    RESEED_REQUIRED("RESEED_REQUIRED", "Reseed Required"),
    ERROR("ERROR", "Error");

    companion object {
        fun fromWire(value: String): ProvenanceStatus =
            entries.firstOrNull { it.wireValue == value } ?: CLOUD_VERIFIED
    }
}

enum class SidecarStatus(val wireValue: String, val label: String) {
    CLOUD_LOCUS_LOCKED("CLOUD_LOCUS_LOCKED", "Locus Locked"),
    RESEEDED("RESEEDED", "Reseeded"),
    PENDING_CLOUD_ATTESTATION("PENDING_CLOUD_ATTESTATION", "Pending Cloud"),
    FALLBACK_LITERAL("FALLBACK_LITERAL", "Literal Passthrough");

    companion object {
        fun fromWire(value: String): SidecarStatus =
            entries.firstOrNull { it.wireValue == value } ?: CLOUD_LOCUS_LOCKED
    }
}

enum class ConvergenceType(val wireValue: String, val label: String) {
    DIRECT_PROJECTION("DIRECT_PROJECTION", "Direct Projection"),
    CONJUNCTIVE_MERGE("CONJUNCTIVE_MERGE", "Conjunctive Merge"),
    CONDITIONAL_GUARD("CONDITIONAL_GUARD", "Conditional Guard"),
    SAFETY_OVERRIDE("SAFETY_OVERRIDE", "Safety Override");

    companion object {
        fun fromWire(value: String): ConvergenceType =
            entries.firstOrNull { it.wireValue == value } ?: DIRECT_PROJECTION
    }
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val role: String = UserRole.CURATOR.wireValue,
    val cloudRestEndpoint: String? = null,
    val preferredTheme: String = "system",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class LibraryDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val author: String = "",
    val sourceFormat: String = SourceFormat.PDF.wireValue,
    val ditaTopicType: String = "concept",
    val sourceDigest: String = "",
    val provenanceStatus: String = ProvenanceStatus.CLOUD_VERIFIED.wireValue,
    val attestedModel: String = "gemini-3.8-flash",
    val truthManifestHash: String = "",
    val merkleRootHash: String = "",
    val shaclScore: Double = 1.0,
    val dofScore: Double = 0.0,
    val bayesianFeedbackBlocked: Boolean = true,
    val sectionCount: Long = 0L,
    val tripleCount: Long = 0L,
    val summary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    val hasPendingWrites: Boolean = false
)

@IgnoreExtraProperties
data class DocumentSection(
    @DocumentId val id: String = "",
    val documentId: String = "",
    val orderIndex: Long = 0L,
    val title: String = "",
    val plainText: String = "",
    val ditaXml: String = "",
    val ssmlProsody: String = "",
    val urn: String = "",
    val sourceSpan: String = "",
    val locusChecksum: String = "",
    val provenanceChainHash: String = "",
    val dofScore: Double = 0.0,
    val locusDriftDistance: Double = 0.0,
    val reseedCount: Long = 0L,
    val sidecarStatus: String = SidecarStatus.CLOUD_LOCUS_LOCKED.wireValue,
    val convergenceType: String = ConvergenceType.DIRECT_PROJECTION.wireValue,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class KnowledgeTriple(
    @DocumentId val id: String = "",
    val documentId: String = "",
    val sectionId: String = "",
    val urn: String = "",
    val subject: String = "",
    val predicate: String = "",
    val objectValue: String = "",
    val jsonLdContext: String = "https://schema.org/",
    val shaclStatus: String = "PASSED",
    val confidence: Double = 1.0,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StandoffAnnotation(
    @DocumentId val id: String = "",
    val documentId: String = "",
    val sectionId: String = "",
    val startOffset: Long = 0L,
    val endOffset: Long = 0L,
    val selectedText: String = "",
    val note: String = "",
    val tagColor: String = "TERRACOTTA",
    val authorRole: String = UserRole.CURATOR.wireValue,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AuditLogEntry(
    @DocumentId val id: String = "",
    val action: String = "INGEST",
    val documentId: String = "",
    val patchId: String = "patch-06",
    val sourceDigest: String = "",
    val inputStateHash: String = "",
    val outputStateHash: String = "",
    val truthManifestHash: String = "",
    val dofScore: Double = 0.0,
    val bayesianFeedbackBlocked: Boolean = true,
    val latencyMs: Long = 0L,
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
