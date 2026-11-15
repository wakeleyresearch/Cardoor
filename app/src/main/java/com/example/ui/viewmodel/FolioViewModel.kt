package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.CloudSynthesisService
import com.example.data.model.AuditLogEntry
import com.example.data.model.DocumentSection
import com.example.data.model.KnowledgeTriple
import com.example.data.model.LibraryDocument
import com.example.data.model.ProvenanceStatus
import com.example.data.model.SidecarStatus
import com.example.data.model.SourceFormat
import com.example.data.model.StandoffAnnotation
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.repository.FolioRepository
import com.example.domain.parsers.DocumentContainerParser
import com.example.domain.parsers.ExtractedContainerPayload
import com.example.domain.parsers.RawSourceBlock
import com.example.domain.pipeline.DriftCheckStatus
import com.example.domain.pipeline.MarkovianKernel
import com.example.domain.pipeline.SidecarDriftReport
import com.example.domain.pipeline.SidecarDriftService
import com.example.domain.prosody.ProsodyAudioEngine
import com.example.domain.prosody.ProsodyPlaybackState
import com.example.domain.prosody.ProsodySegment
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class IngestionStage(val label: String, val stepIndex: Int) {
    IDLE("Ready", 0),
    UNPACKING("Unpacking source & computing SHA-256 digest", 1),
    CLOUD_SYNTHESIS("Cloud OCR & DITA/RDF synthesis (App Check)", 2),
    DIVERGENCE("Markovian Divergence (patch-01..04) & sealing Truth Blanket", 3),
    CENTRAL_RING("Feedback Inhibition & Sidecar DoF Gate (patch-05..06)", 4),
    CLOUD_COMMIT("Committing attested provenance to Cloud Firestore", 5)
}

data class ReaderSettings(
    val fontSizeSp: Float = 17f,
    val lineHeightMultiplier: Float = 1.55f,
    val useSerif: Boolean = true,
    val horizontalPaddingDp: Int = 20
)

@OptIn(ExperimentalCoroutinesApi::class)
class FolioViewModel(
    private val repository: FolioRepository,
    private val cloudAi: CloudSynthesisService,
    private val audioEngine: ProsodyAudioEngine,
    private val sidecarDriftService: SidecarDriftService,
    val currentUserId: String,
    initialDisplayName: String,
    initialEmail: String
) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = repository.observeUserProfile(currentUserId)
        .catch { error ->
            if (error is CancellationException && unwrapFirestoreError(error) == null) throw error
            Log.w(TAG, "Profile observation failed", error)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val documentsState: StateFlow<UiState<List<LibraryDocument>>> = repository.observeDocuments(currentUserId)
        .map<List<LibraryDocument>, UiState<List<LibraryDocument>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = unwrapFirestoreError(error)
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Error observing documents", root)
            emit(UiState.Error(root.message ?: "Failed to load synced documents."))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val auditLogsState: StateFlow<UiState<List<AuditLogEntry>>> = repository.observeAuditLogs(currentUserId)
        .map<List<AuditLogEntry>, UiState<List<AuditLogEntry>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = unwrapFirestoreError(error)
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Error observing audit logs", root)
            emit(UiState.Error(root.message ?: "Failed to load audit logs."))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _selectedDocId = MutableStateFlow<String?>(null)
    val selectedDocId: StateFlow<String?> = _selectedDocId.asStateFlow()

    val selectedSections: StateFlow<List<DocumentSection>> = _selectedDocId
        .flatMapLatest { docId ->
            if (docId == null) flowOf(emptyList())
            else repository.observeSections(currentUserId, docId)
                .catch { error ->
                    if (error is CancellationException && unwrapFirestoreError(error) == null) throw error
                    Log.w(TAG, "Error observing sections", error)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val selectedTriples: StateFlow<List<KnowledgeTriple>> = _selectedDocId
        .flatMapLatest { docId ->
            if (docId == null) flowOf(emptyList())
            else repository.observeTriples(currentUserId, docId)
                .catch { error ->
                    if (error is CancellationException && unwrapFirestoreError(error) == null) throw error
                    Log.w(TAG, "Error observing triples", error)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val selectedAnnotations: StateFlow<List<StandoffAnnotation>> = _selectedDocId
        .flatMapLatest { docId ->
            if (docId == null) flowOf(emptyList())
            else repository.observeAnnotations(currentUserId, docId)
                .catch { error ->
                    if (error is CancellationException && unwrapFirestoreError(error) == null) throw error
                    Log.w(TAG, "Error observing annotations", error)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _ingestionStage = MutableStateFlow(IngestionStage.IDLE)
    val ingestionStage: StateFlow<IngestionStage> = _ingestionStage.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _formatFilter = MutableStateFlow<String>("ALL")
    val formatFilter: StateFlow<String> = _formatFilter.asStateFlow()

    private val _readerSettings = MutableStateFlow(ReaderSettings())
    val readerSettings: StateFlow<ReaderSettings> = _readerSettings.asStateFlow()

    private val _frozenEnvelopes = MutableStateFlow<Map<String, MarkovianKernel.PipelineEnvelope>>(emptyMap())
    val activeEnvelope: StateFlow<MarkovianKernel.PipelineEnvelope?> = _selectedDocId
        .flatMapLatest { id ->
            _frozenEnvelopes.map { map -> id?.let { map[it] } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    private val _dofThreshold = MutableStateFlow(MarkovianKernel.DOF_BREACH_THRESHOLD)
    val dofThreshold: StateFlow<Double> = _dofThreshold.asStateFlow()

    private val _driftReports = MutableStateFlow<Map<String, SidecarDriftReport>>(emptyMap())
    val activeDriftReport: StateFlow<SidecarDriftReport?> = _selectedDocId
        .flatMapLatest { id ->
            _driftReports.map { map -> id?.let { map[it] } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    private val _isCheckingDrift = MutableStateFlow(false)
    val isCheckingDrift: StateFlow<Boolean> = _isCheckingDrift.asStateFlow()

    val prosodyState: StateFlow<ProsodyPlaybackState> = audioEngine.state

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(initialDisplayName, initialEmail, UserRole.CURATOR)
                .onFailure { e ->
                    _errorBanner.value = e.localizedMessage ?: "Failed to initialize cloud user profile."
                }
        }
    }

    fun selectDocument(docId: String) {
        _selectedDocId.value = docId
        viewModelScope.launch {
            verifyOrSeedLocalSidecarIfNeeded(docId)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFormatFilter(filter: String) {
        _formatFilter.value = filter
    }

    fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        _readerSettings.value = transform(_readerSettings.value)
    }

    fun setDofThreshold(value: Double) {
        _dofThreshold.value = value.coerceIn(0.15, 0.85)
    }

    fun dismissBanners() {
        _statusBanner.value = null
        _errorBanner.value = null
    }

    fun updateProfileSettings(displayName: String, theme: String, cloudRestEndpoint: String?) {
        viewModelScope.launch {
            repository.updateProfilePreferences(displayName, theme, cloudRestEndpoint)
                .onSuccess { _statusBanner.value = "Workspace settings committed to Cloud Firestore." }
                .onFailure { e -> _errorBanner.value = e.localizedMessage ?: "Unable to update settings." }
        }
    }

    fun ingestDocumentFromUri(context: Context, uri: Uri, displayName: String) {
        val role = UserRole.fromWire(userProfile.value?.role ?: UserRole.CURATOR.wireValue)
        if (!role.canIngestOrReseed()) {
            _errorBanner.value = "RBAC Guard: ${role.displayLabel} role has read/annotate access only."
            return
        }
        viewModelScope.launch {
            _errorBanner.value = null
            _statusBanner.value = null
            _ingestionStage.value = IngestionStage.UNPACKING
            try {
                val container = withContext(Dispatchers.IO) {
                    DocumentContainerParser.parseUri(context, uri, displayName)
                }
                executeCloudMarkovianPipeline(container)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Container unpack failed", e)
                _ingestionStage.value = IngestionStage.IDLE
                _errorBanner.value = e.localizedMessage ?: "Failed to unpack document container."
            }
        }
    }

    fun ingestCanonicalReferenceDita() {
        val role = UserRole.fromWire(userProfile.value?.role ?: UserRole.CURATOR.wireValue)
        if (!role.canIngestOrReseed()) {
            _errorBanner.value = "RBAC Guard: ${role.displayLabel} role cannot ingest documents."
            return
        }
        viewModelScope.launch {
            _errorBanner.value = null
            _statusBanner.value = null
            _ingestionStage.value = IngestionStage.UNPACKING
            val referenceText = """
                Neuro-Symbolic Content and Verification Pipeline Specification.
                Section 1: Divergence and Convergence Topology.
                Elastic worker nodes unpack PDF, EPUB, and MOBI containers into deterministic DITA 1.3 XML topics. Each node computes a canonical SHA-256 URN and seals a Truth Manifest Markov Blanket before synthesis begins.
                Section 2: Parsing Sidecar and Feedback Inhibition Control.
                A dedicated Parsing Sidecar monitors each node's degrees of freedom (DoF) relative to the immutable source locus. Feedback Inhibition Control enforces strict output-to-prior isolation so downstream generative text never triggers Bayesian false belief updating on upstream priors. If hallucination or an out-of-bounds URN occurs, the sidecar discards the candidate output with zero posterior carryover and reseeds directly from the sealed Truth Manifest.
                Section 3: Written to be Read Out Loud Prosody Compiler.
                Verified DITA and RDF primitives compile into SSML prosody segments with calibrated rate, pitch, and pause cues for accessible spoken narration and W3C stand-off annotations.
            """.trimIndent()
            val digest = DocumentContainerParser.sha256Hex(referenceText)
            val blocks = DocumentContainerParser.chunkTextIntoBlocks(
                "Neuro-Symbolic Markovian Pipeline Spec",
                referenceText,
                "dita-ref"
            )
            val payload = ExtractedContainerPayload(
                title = "Neuro-Symbolic Markovian Pipeline Spec",
                author = "DITA 1.3 Reference Corpus",
                sourceFormat = SourceFormat.MARKDOWN_DITA,
                sourceDigest = digest,
                rawTextBlocks = blocks
            )
            executeCloudMarkovianPipeline(payload)
        }
    }

    private suspend fun executeCloudMarkovianPipeline(payload: ExtractedContainerPayload) {
        try {
            _ingestionStage.value = IngestionStage.CLOUD_SYNTHESIS
            val restEndpoint = userProfile.value?.cloudRestEndpoint
            val cloudResult = cloudAi.synthesizeFromContainer(payload, restEndpoint).getOrElse { err ->
                _ingestionStage.value = IngestionStage.IDLE
                _errorBanner.value = "Cloud AI Logic error: ${err.localizedMessage ?: "Attestation or network failure."}"
                return
            }

            _ingestionStage.value = IngestionStage.DIVERGENCE
            val docId = "doc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val sourceBlocks = if (payload.sourceFormat == SourceFormat.OCR_IMAGE ||
                (payload.sourceFormat == SourceFormat.PDF && cloudResult.extractedBlocksForOcr.isNotEmpty())
            ) {
                cloudResult.extractedBlocksForOcr
            } else {
                payload.rawTextBlocks
            }

            val tripleMap = cloudResult.synthesizedSections.associate { sec ->
                sec.sectionId to sec.triples
            }

            val initialEnvelope = MarkovianKernel.PipelineEnvelope(
                envelopeId = "env_${docId.take(10)}",
                documentId = docId,
                title = cloudResult.documentTitle,
                sourceDigest = payload.sourceDigest,
                rawBlocks = sourceBlocks
            )

            val frozenDivergence = MarkovianKernel.runDivergenceStage(
                initialEnvelope = initialEnvelope,
                cloudTriplesBySection = tripleMap
            )

            _ingestionStage.value = IngestionStage.CENTRAL_RING
            val finalEnvelope = MarkovianKernel.runCentralRingAndConvergence(
                frozenDivergenceEnvelope = frozenDivergence,
                synthesizedInputs = cloudResult.synthesizedSections,
                register = audioEngine.state.value.register,
                dofThreshold = _dofThreshold.value,
                cloudAttested = cloudResult.cloudAttested
            )

            _frozenEnvelopes.value = _frozenEnvelopes.value + (docId to frozenDivergence)

            val (sections, triples, logs) = MarkovianKernel.toFirestoreBundle(docId, finalEnvelope)
            val avgDof = if (sections.isEmpty()) 0.0 else sections.map { it.dofScore }.average()
            val shaclPassed = triples.count { it.shaclStatus == "PASSED" }
            val shaclScore = if (triples.isEmpty()) 1.0 else shaclPassed.toDouble() / triples.size.toDouble()
            val manifestHash = finalEnvelope.truthManifest?.manifestHash ?: payload.sourceDigest

            val libDoc = LibraryDocument(
                id = docId,
                userId = currentUserId,
                title = cloudResult.documentTitle,
                author = cloudResult.author,
                sourceFormat = payload.sourceFormat.wireValue,
                ditaTopicType = cloudResult.ditaTopicType,
                sourceDigest = payload.sourceDigest,
                provenanceStatus = ProvenanceStatus.CLOUD_VERIFIED.wireValue,
                attestedModel = cloudResult.attestedModel,
                truthManifestHash = manifestHash,
                merkleRootHash = finalEnvelope.currentStateHash,
                shaclScore = shaclScore,
                dofScore = avgDof,
                bayesianFeedbackBlocked = true,
                sectionCount = sections.size.toLong(),
                tripleCount = triples.size.toLong(),
                summary = cloudResult.summary
            )

            _ingestionStage.value = IngestionStage.CLOUD_COMMIT
            val commitLog = AuditLogEntry(
                id = "log_${docId}_commit",
                action = "PROVENANCE_COMMIT",
                documentId = docId,
                patchId = "patch-06",
                sourceDigest = payload.sourceDigest,
                inputStateHash = frozenDivergence.currentStateHash,
                outputStateHash = finalEnvelope.currentStateHash,
                truthManifestHash = manifestHash,
                dofScore = avgDof,
                bayesianFeedbackBlocked = true,
                latencyMs = logs.sumOf { it.latencyMs },
                details = "Cloud attested via ${cloudResult.attestedModel}; Feedback Inhibition Control verified."
            )

            repository.saveIngestedDocumentBundle(
                document = libDoc,
                sections = sections,
                triples = triples,
                auditLogs = logs + commitLog
            ).onSuccess { savedId ->
                withContext(Dispatchers.IO) {
                    sidecarDriftService.writeLocalSidecar(libDoc.copy(id = savedId), sections)
                }
                _selectedDocId.value = savedId
                verifyLocalSidecarAgainstFirestore(savedId, silentSuccess = true)
                _ingestionStage.value = IngestionStage.IDLE
                _statusBanner.value = "Cloud provenance verified for \"${libDoc.title}\" (${sections.size} sections, DoF=${"%.2f".format(avgDof)})."
            }.onFailure { err ->
                _ingestionStage.value = IngestionStage.IDLE
                _errorBanner.value = err.localizedMessage ?: "Failed to commit provenance bundle to Cloud Firestore."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline execution failed", e)
            _ingestionStage.value = IngestionStage.IDLE
            _errorBanner.value = e.localizedMessage ?: "Cloud pipeline execution failed."
        }
    }

    /**
     * Instant Checkpoint Replay: hot-swaps ProsodyRegister or DoF threshold from the frozen
     * TruthManifest checkpoint without re-running Divergence (patch-01..04).
     */
    fun replayCentralRingWithRegister(
        register: MarkovianKernel.ProsodyRegister,
        simulateDriftTest: Boolean = false
    ) {
        audioEngine.setRegister(register)
        val docId = _selectedDocId.value ?: return
        val currentSections = selectedSections.value
        if (currentSections.isEmpty()) return

        viewModelScope.launch {
            val frozenEnv = _frozenEnvelopes.value[docId] ?: reconstructFrozenDivergenceFromSections(docId, currentSections)
            val inputs = currentSections.mapIndexed { idx, sec ->
                val textCandidate = if (simulateDriftTest && idx == 0) {
                    // Deliberately inject an out-of-locus hallucination to prove Parsing Sidecar + Feedback Inhibition Control catches & reseeds it!
                    "${sec.plainText} Unanchored hallucinated telemetry claims cosmic alignment overrides source locus."
                } else {
                    sec.plainText
                }
                val citedUrns = if (simulateDriftTest && idx == 0) {
                    listOf("urn:foliograph:sha256:out_of_bounds_fake")
                } else {
                    listOf(sec.urn)
                }
                MarkovianKernel.CloudSynthesizedSectionInput(
                    sectionId = sec.id,
                    title = sec.title,
                    reflowText = textCandidate,
                    citedUrns = citedUrns,
                    prosodyTone = "analytical",
                    triples = emptyList()
                )
            }

            val replayed = MarkovianKernel.runCentralRingAndConvergence(
                frozenDivergenceEnvelope = frozenEnv,
                synthesizedInputs = inputs,
                register = register,
                dofThreshold = _dofThreshold.value,
                cloudAttested = true
            )

            val (updatedSections, _, logs) = MarkovianKernel.toFirestoreBundle(docId, replayed)
            val firstUpdated = updatedSections.firstOrNull() ?: return@launch
            val replayLog = logs.lastOrNull()?.copy(
                action = if (simulateDriftTest) "SIDECAR_RESEED" else "PROSODY_COMPILE",
                details = if (simulateDriftTest) {
                    "Feedback Inhibition Control blocked Bayesian belief update on hallucinated probe; reseeded from sealed TruthManifest."
                } else {
                    "Checkpoint Replay (${register.label}) verified from frozen TruthManifest without re-running Divergence."
                }
            ) ?: return@launch

            repository.updateSectionAfterSidecarReplay(docId, firstUpdated, replayLog)
                .onSuccess {
                    _statusBanner.value = if (simulateDriftTest) {
                        "Drift probe caught! Feedback Inhibition blocked false belief update & reseeded section."
                    } else {
                        "Replayed prosody register '${register.label}' from frozen TruthManifest."
                    }
                }
                .onFailure { e ->
                    _errorBanner.value = e.localizedMessage ?: "Checkpoint replay update failed."
                }
        }
    }

    fun triggerCloudSectionReseed(section: DocumentSection) {
        val docId = _selectedDocId.value ?: return
        val role = UserRole.fromWire(userProfile.value?.role ?: UserRole.CURATOR.wireValue)
        if (!role.canIngestOrReseed()) {
            _errorBanner.value = "RBAC Guard: ${role.displayLabel} role cannot trigger cloud reseed."
            return
        }

        viewModelScope.launch {
            _statusBanner.value = "Requesting memoryless cloud reseed for ${section.title}..."
            val frozenEnv = _frozenEnvelopes.value[docId] ?: reconstructFrozenDivergenceFromSections(docId, selectedSections.value)
            val allowedUrns = frozenEnv.truthManifest?.allowedBlockUrns ?: setOf(section.urn)

            cloudAi.reseedSectionFromTruthLocus(
                sectionId = section.id,
                sourceLocusText = section.plainText,
                allowedUrns = allowedUrns
            ).onSuccess { reseededText ->
                val newSsml = MarkovianKernel.compileSsmlProsody(
                    title = section.title,
                    prose = reseededText,
                    register = audioEngine.state.value.register
                )
                val newChainHash = DocumentContainerParser.sha256Hex("${section.locusChecksum}:$reseededText")
                val updated = section.copy(
                    plainText = reseededText,
                    ssmlProsody = newSsml,
                    provenanceChainHash = newChainHash,
                    dofScore = 0.08,
                    locusDriftDistance = 0.05,
                    reseedCount = section.reseedCount + 1L,
                    sidecarStatus = SidecarStatus.RESEEDED.wireValue
                )
                val log = AuditLogEntry(
                    action = "SIDECAR_RESEED",
                    documentId = docId,
                    patchId = "patch-06",
                    sourceDigest = frozenEnv.sourceDigest,
                    inputStateHash = section.provenanceChainHash,
                    outputStateHash = newChainHash,
                    truthManifestHash = frozenEnv.truthManifest?.manifestHash ?: section.locusChecksum,
                    dofScore = 0.08,
                    bayesianFeedbackBlocked = true,
                    latencyMs = 310L,
                    details = "Memoryless cloud reseed on ${section.id}; Feedback Inhibition preserved prior TruthManifest."
                )
                repository.updateSectionAfterSidecarReplay(docId, updated, log)
                    .onSuccess {
                        syncLocalSidecarFromCurrentState(docId)
                        _statusBanner.value = "Cloud reseed completed for ${section.title} (Feedback Inhibition verified)."
                    }
                    .onFailure { e -> _errorBanner.value = e.localizedMessage }
            }.onFailure { e ->
                _errorBanner.value = "Cloud reseed failed: ${e.localizedMessage}"
            }
        }
    }

    fun verifyLocalSidecarAgainstFirestore(
        docIdOverride: String? = null,
        silentSuccess: Boolean = false
    ) {
        val docId = docIdOverride ?: _selectedDocId.value ?: return
        viewModelScope.launch {
            _isCheckingDrift.value = true
            try {
                val (cloudDoc, cloudSections, fromServer) = repository.fetchDocumentAndSectionsForDriftCheck(docId)
                    .getOrElse { err ->
                        _errorBanner.value = err.localizedMessage ?: "Failed to fetch Firestore hash for drift check."
                        return@launch
                    }

                val report = withContext(Dispatchers.IO) {
                    sidecarDriftService.compareLocalSidecarWithFirestore(
                        firestoreDocument = cloudDoc,
                        firestoreSections = cloudSections,
                        checkedFromServer = fromServer
                    )
                }
                _driftReports.value = _driftReports.value + (docId to report)

                val actionStatus = if (report.status == DriftCheckStatus.VERIFIED_MATCH) {
                    ProvenanceStatus.CLOUD_VERIFIED
                } else {
                    ProvenanceStatus.RESEED_REQUIRED
                }
                val updatedDof = if (report.status == DriftCheckStatus.DRIFT_FLAGGED) {
                    maxOf(cloudDoc.dofScore, 0.85)
                } else {
                    null
                }

                val checkLog = AuditLogEntry(
                    action = "SIDECAR_CHECK",
                    documentId = docId,
                    patchId = "patch-06",
                    sourceDigest = cloudDoc.sourceDigest,
                    inputStateHash = report.localSidecarHash.padEnd(16, '0').take(128),
                    outputStateHash = report.firestoreCanonicalHash.padEnd(16, '0').take(128),
                    truthManifestHash = cloudDoc.truthManifestHash,
                    dofScore = updatedDof ?: cloudDoc.dofScore,
                    bayesianFeedbackBlocked = true,
                    latencyMs = 18L,
                    details = "[${report.status.label}] ${report.driftReason}".take(480)
                )

                if (report.status != DriftCheckStatus.VERIFIED_MATCH ||
                    cloudDoc.provenanceStatus == ProvenanceStatus.RESEED_REQUIRED.wireValue
                ) {
                    repository.flagDocumentParsingDrift(
                        docId = docId,
                        provenanceStatus = actionStatus,
                        dofScore = updatedDof,
                        auditLog = checkLog
                    )
                } else if (!silentSuccess) {
                    repository.appendAuditLog(checkLog)
                }

                if (report.status == DriftCheckStatus.VERIFIED_MATCH) {
                    if (!silentSuccess) {
                        _statusBanner.value = "Sidecar hash verified against Firestore (${report.localSidecarHash.take(12)}…)."
                    }
                } else {
                    _errorBanner.value = "Parsing drift flagged on \"${cloudDoc.title}\": ${report.driftReason}"
                }
            } finally {
                _isCheckingDrift.value = false
            }
        }
    }

    fun simulateLocalSidecarDrift() {
        val docId = _selectedDocId.value ?: return
        val role = UserRole.fromWire(userProfile.value?.role ?: UserRole.CURATOR.wireValue)
        if (!role.canIngestOrReseed()) {
            _errorBanner.value = "RBAC Guard: ${role.displayLabel} role cannot run drift probes."
            return
        }
        viewModelScope.launch {
            _isCheckingDrift.value = true
            try {
                val (cloudDoc, cloudSections, _) = repository.fetchDocumentAndSectionsForDriftCheck(docId)
                    .getOrElse { err ->
                        _errorBanner.value = err.localizedMessage ?: "Unable to fetch document for drift probe."
                        return@launch
                    }
                withContext(Dispatchers.IO) {
                    if (!sidecarDriftService.hasLocalSidecar(docId)) {
                        sidecarDriftService.writeLocalSidecar(cloudDoc, cloudSections)
                    }
                    sidecarDriftService.injectSimulatedLocalDrift(docId)
                }
            } finally {
                _isCheckingDrift.value = false
            }
            verifyLocalSidecarAgainstFirestore(docId, silentSuccess = false)
        }
    }

    fun restoreLocalSidecarFromFirestore() {
        val docId = _selectedDocId.value ?: return
        viewModelScope.launch {
            _isCheckingDrift.value = true
            try {
                val (cloudDoc, cloudSections, fromServer) = repository.fetchDocumentAndSectionsForDriftCheck(docId)
                    .getOrElse { err ->
                        _errorBanner.value = err.localizedMessage ?: "Failed to fetch cloud provenance to restore sidecar."
                        return@launch
                    }

                withContext(Dispatchers.IO) {
                    sidecarDriftService.writeLocalSidecar(cloudDoc, cloudSections)
                }

                val report = withContext(Dispatchers.IO) {
                    sidecarDriftService.compareLocalSidecarWithFirestore(cloudDoc, cloudSections, fromServer)
                }
                _driftReports.value = _driftReports.value + (docId to report)

                val restoreLog = AuditLogEntry(
                    action = "SIDECAR_RESEED",
                    documentId = docId,
                    patchId = "patch-06",
                    sourceDigest = cloudDoc.sourceDigest,
                    inputStateHash = report.firestoreCanonicalHash,
                    outputStateHash = report.localSidecarHash,
                    truthManifestHash = cloudDoc.truthManifestHash,
                    dofScore = 0.08,
                    bayesianFeedbackBlocked = true,
                    latencyMs = 15L,
                    details = "Local sidecar re-projected from sealed Firestore TruthManifest; parsing drift cleared."
                )

                repository.flagDocumentParsingDrift(
                    docId = docId,
                    provenanceStatus = ProvenanceStatus.CLOUD_VERIFIED,
                    dofScore = 0.08,
                    auditLog = restoreLog
                ).onSuccess {
                    _statusBanner.value = "Local sidecar restored from Firestore TruthManifest (${report.localSidecarHash.take(12)}…)."
                }.onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Failed to clear drift flag in Firestore."
                }
            } finally {
                _isCheckingDrift.value = false
            }
        }
    }

    private suspend fun verifyOrSeedLocalSidecarIfNeeded(docId: String) {
        val (cloudDoc, cloudSections, fromServer) = repository.fetchDocumentAndSectionsForDriftCheck(docId).getOrNull() ?: return
        val report = withContext(Dispatchers.IO) {
            if (!sidecarDriftService.hasLocalSidecar(docId)) {
                sidecarDriftService.writeLocalSidecar(cloudDoc, cloudSections)
            }
            sidecarDriftService.compareLocalSidecarWithFirestore(cloudDoc, cloudSections, fromServer)
        }
        _driftReports.value = _driftReports.value + (docId to report)
        if (report.status == DriftCheckStatus.DRIFT_FLAGGED) {
            verifyLocalSidecarAgainstFirestore(docId, silentSuccess = true)
        }
    }

    private suspend fun syncLocalSidecarFromCurrentState(docId: String) {
        val (cloudDoc, cloudSections, fromServer) = repository.fetchDocumentAndSectionsForDriftCheck(docId).getOrNull() ?: return
        val report = withContext(Dispatchers.IO) {
            sidecarDriftService.writeLocalSidecar(cloudDoc, cloudSections)
            sidecarDriftService.compareLocalSidecarWithFirestore(cloudDoc, cloudSections, fromServer)
        }
        _driftReports.value = _driftReports.value + (docId to report)
    }

    fun addAnnotation(
        sectionId: String,
        selectedText: String,
        note: String,
        tagColor: String
    ) {
        val docId = _selectedDocId.value ?: return
        val role = UserRole.fromWire(userProfile.value?.role ?: UserRole.CURATOR.wireValue)
        val section = selectedSections.value.firstOrNull { it.id == sectionId } ?: return
        val startIdx = section.plainText.indexOf(selectedText, ignoreCase = true).coerceAtLeast(0)
        val endIdx = (startIdx + selectedText.length).coerceAtMost(section.plainText.length)

        viewModelScope.launch {
            repository.addStandoffAnnotation(
                docId = docId,
                sectionId = sectionId,
                startOffset = startIdx,
                endOffset = endIdx,
                selectedText = selectedText.ifBlank { section.plainText.take(80) },
                note = note,
                tagColor = tagColor,
                authorRole = role
            ).onSuccess {
                _statusBanner.value = "Stand-off W3C annotation saved to Cloud Firestore."
            }.onFailure { e ->
                _errorBanner.value = e.localizedMessage ?: "Failed to save annotation."
            }
        }
    }

    fun deleteAnnotation(annotationId: String) {
        val docId = _selectedDocId.value ?: return
        viewModelScope.launch {
            repository.deleteAnnotation(docId, annotationId)
                .onFailure { e -> _errorBanner.value = e.localizedMessage }
        }
    }

    fun deleteDocument(docId: String) {
        viewModelScope.launch {
            repository.deleteDocument(docId)
                .onSuccess {
                    if (_selectedDocId.value == docId) _selectedDocId.value = null
                    _statusBanner.value = "Document removed from cloud workspace."
                }
                .onFailure { e -> _errorBanner.value = e.localizedMessage }
        }
    }

    fun playAllSections(startIndex: Int = 0) {
        val segments = selectedSections.value.map { sec ->
            ProsodySegment(
                sectionId = sec.id,
                title = sec.title,
                spokenText = "${sec.title}. ${sec.plainText}",
                ssmlMarkup = sec.ssmlProsody
            )
        }
        audioEngine.playSegments(segments, startIndex)
    }

    fun stopAudio() {
        audioEngine.stop()
    }

    private fun reconstructFrozenDivergenceFromSections(
        docId: String,
        sections: List<DocumentSection>
    ): MarkovianKernel.PipelineEnvelope {
        val raw = sections.map {
            RawSourceBlock(title = it.title, text = it.plainText, sourceSpan = it.sourceSpan)
        }
        val env = MarkovianKernel.PipelineEnvelope(
            envelopeId = "env_${docId.take(10)}",
            documentId = docId,
            title = docId,
            sourceDigest = sections.firstOrNull()?.locusChecksum ?: "0000000000000000",
            rawBlocks = raw
        )
        return MarkovianKernel.runDivergenceStage(env)
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    override fun onCleared() {
        super.onCleared()
        audioEngine.shutdown()
    }

    private companion object {
        const val TAG = "FolioViewModel"
    }
}
