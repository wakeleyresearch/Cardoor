package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AuditLogEntry
import com.example.data.model.DocumentSection
import com.example.data.model.LibraryDocument
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.domain.pipeline.DriftCheckStatus
import com.example.domain.pipeline.MarkovianKernel
import com.example.domain.pipeline.SidecarDriftReport
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun PipelineScreen(
    selectedDocument: LibraryDocument?,
    sections: List<DocumentSection>,
    activeEnvelope: MarkovianKernel.PipelineEnvelope?,
    driftReport: SidecarDriftReport?,
    isCheckingDrift: Boolean,
    auditLogsState: UiState<List<AuditLogEntry>>,
    userProfile: UserProfile?,
    dofThreshold: Double,
    onDofThresholdChange: (Double) -> Unit,
    onReplayCheckpoint: () -> Unit,
    onSimulateDriftTest: () -> Unit,
    onVerifySidecarHash: () -> Unit,
    onSimulateLocalSidecarDrift: () -> Unit,
    onRestoreSidecarFromCloud: () -> Unit,
    onSaveProfileSettings: (displayName: String, preferredTheme: String, cloudRestEndpoint: String?) -> Unit
) {
    val role = UserRole.fromWire(userProfile?.role ?: UserRole.CURATOR.wireValue)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)) {
            Text(
                text = stringResource(R.string.pipeline_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.pipeline_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Elastic Divergence -> Convergence Topology Diagram
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Elastic Divergence → Convergence Topology",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        TopologyCanvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(185.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TopologyCaption("A · Divergence", "DITA + RDF + Vector\npatch-01..04")
                            TopologyCaption("Compute Ring", "Blanket Sealed\npatch-05..06")
                            TopologyCaption("B · Convergence", "Feedback Inhibition\n0.0 Prior Drift")
                        }
                    }
                }
            }

            // 2. Parsing Sidecar & Feedback Inhibition Control Monitor
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "Parsing Sidecar & Feedback Inhibition",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Feedback Inhibition Control: ACTIVE · Bayesian posterior carryover strictly 0.0 (TruthManifest immutable).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        val manifestHash = activeEnvelope?.truthManifest?.manifestHash
                            ?: selectedDocument?.truthManifestHash
                            ?: "No folio selected"
                        val merkleHash = activeEnvelope?.currentStateHash
                            ?: selectedDocument?.merkleRootHash
                            ?: "—"

                        Text(
                            text = "TruthManifest: ${manifestHash.take(28)}…\nMerkle Root:   ${merkleHash.take(28)}…",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Sidecar DoF Breach Threshold",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "${"%.2f".format(dofThreshold)}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = dofThreshold.toFloat(),
                                onValueChange = { onDofThresholdChange(it.toDouble()) },
                                valueRange = 0.15f..0.85f,
                                modifier = Modifier.testTag("dof_threshold_slider")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onReplayCheckpoint,
                                enabled = selectedDocument != null && sections.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("replay_checkpoint_button")
                            ) {
                                Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.pipeline_replay_checkpoint),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            Button(
                                onClick = onSimulateDriftTest,
                                enabled = selectedDocument != null && sections.isNotEmpty() && role.canIngestOrReseed(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("simulate_drift_button")
                            ) {
                                Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.pipeline_simulate_drift),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        if (sections.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Text(
                                text = "Node Degrees of Freedom (${selectedDocument?.title ?: "Active Folio"})",
                                style = MaterialTheme.typography.labelLarge
                            )
                            sections.forEach { sec ->
                                val breached = sec.dofScore > dofThreshold
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${sec.id}: ${sec.title}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "DoF ${"%.2f".format(sec.dofScore)} · Drift ${"%.2f".format(sec.locusDriftDistance)} · Reseed ${sec.reseedCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (breached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { sec.dofScore.toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = if (breached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2B. Local Sidecar File vs. Firestore Hash Drift Audit
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(
                        1.dp,
                        if (driftReport?.status == DriftCheckStatus.DRIFT_FLAGGED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FactCheck,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.pipeline_sidecar_hash_heading),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            if (driftReport != null) {
                                val flagged = driftReport.status != DriftCheckStatus.VERIFIED_MATCH
                                Surface(
                                    color = if (flagged) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = driftReport.status.label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (flagged) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        if (isCheckingDrift) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        if (driftReport == null) {
                            Text(
                                text = "Compare the device-local sidecar JSON file against Firestore's stored Merkle & section checksums to detect silent drift.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = buildString {
                                    appendLine("Sidecar File:   ${driftReport.sidecarFileName}")
                                    appendLine("Local SHA-256:  ${driftReport.localSidecarHash.take(24)}…")
                                    appendLine("Cloud Canonical:${driftReport.firestoreCanonicalHash.take(24)}…")
                                    appendLine("Firestore Root: ${driftReport.firestoreStoredHash.take(24)}…")
                                    append("Source:         ${if (driftReport.checkedFromServer) "Cloud Firestore Server" else "Offline Cache"}")
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    lineHeight = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = driftReport.driftReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (driftReport.status == DriftCheckStatus.VERIFIED_MATCH)
                                    MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.error
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onVerifySidecarHash,
                                enabled = selectedDocument != null && !isCheckingDrift,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("verify_sidecar_hash_button")
                            ) {
                                Icon(Icons.Outlined.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.pipeline_compare_flag_cta), style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = onSimulateLocalSidecarDrift,
                                enabled = selectedDocument != null && !isCheckingDrift && role.canIngestOrReseed(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("simulate_local_sidecar_drift_button")
                            ) {
                                Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.pipeline_simulate_file_drift_cta), style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (driftReport != null && driftReport.status != DriftCheckStatus.VERIFIED_MATCH) {
                            OutlinedButton(
                                onClick = onRestoreSidecarFromCloud,
                                enabled = !isCheckingDrift,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("restore_sidecar_from_cloud_button")
                            ) {
                                Icon(Icons.Outlined.RestorePage, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.pipeline_restore_sidecar_cta))
                            }
                        }
                    }
                }
            }

            // 3. Cloud REST Ingestion Gateway & Workspace Settings
            item {
                var nameDraft by remember(userProfile?.displayName) {
                    mutableStateOf(userProfile?.displayName ?: "")
                }
                var endpointDraft by remember(userProfile?.cloudRestEndpoint) {
                    mutableStateOf(userProfile?.cloudRestEndpoint ?: "")
                }
                val themePref = userProfile?.preferredTheme ?: "system"

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Cloud Workspace & REST Gateway",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text("Archivist Display Name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profile_name_input")
                        )
                        OutlinedTextField(
                            value = endpointDraft,
                            onValueChange = { endpointDraft = it },
                            label = { Text("Optional Cloud REST Gateway (https://...)") },
                            placeholder = { Text("https://api.example.org/v1/documents/ingest") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rest_endpoint_input")
                        )
                        Button(
                            onClick = {
                                onSaveProfileSettings(
                                    nameDraft.trim(),
                                    themePref,
                                    endpointDraft.trim().ifBlank { null }
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .heightIn(min = 48.dp)
                                .testTag("save_pipeline_settings_button")
                        ) {
                            Text("Commit Cloud Settings")
                        }
                    }
                }
            }

            // 4. RESTful Operations & Replication Latency Playbook
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.pipeline_arch_heading),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = """
                                • POST /v1/documents/ingest — OAuth2 Bearer + SHA-256 idempotency key; dispatches elastic OCR/DITA worker nodes.
                                • GET /v1/documents/{id}/dita — Streams verified DITA 1.3 XML topics inside sealed Markov Blanket.
                                • GET /v1/documents/{id}/graph — Exports RDF/JSON-LD triples & SHACL validation receipts.
                                • GET /v1/audit/history — Paginated append-only Merkle transition ledger.
                                
                                Replication Latency Mitigation Under Heavy Write Traffic:
                                1. Write-Sharded Append Logs: Partition high-frequency audit_logs with random shard prefixes to eliminate hotspotting.
                                2. Bounded CQRS Commit Batches: Stage sections & triples in discrete WriteBatches with field-level immutable guards.
                                3. Attestation State Gating: Surface MetadataChanges.INCLUDE hasPendingWrites as "Pending Attestation" until Cloud server timestamps resolve.
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 5. Immutable Historical Audit Ledger
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.pipeline_audit_heading),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            when (auditLogsState) {
                is UiState.Loading -> {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is UiState.Error -> {
                    item {
                        Text(
                            text = auditLogsState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is UiState.Success -> {
                    if (auditLogsState.data.isEmpty()) {
                        item {
                            Text(
                                text = "No cloud provenance transitions logged yet. Ingest a document or run the canonical DITA spec from Library.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(auditLogsState.data, key = { it.id }) { log ->
                            AuditLogCard(log = log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyCanvas(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surface

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val inputCenter = Offset(w * 0.08f, h * 0.44f)
        val divNodes = listOf(
            Offset(w * 0.28f, h * 0.16f),
            Offset(w * 0.28f, h * 0.35f),
            Offset(w * 0.28f, h * 0.54f),
            Offset(w * 0.28f, h * 0.73f)
        )
        val ringCenter = Offset(w * 0.50f, h * 0.44f)
        val convNodes = listOf(
            Offset(w * 0.72f, h * 0.22f),
            Offset(w * 0.72f, h * 0.44f),
            Offset(w * 0.72f, h * 0.66f)
        )
        val outputCenter = Offset(w * 0.92f, h * 0.44f)

        // Divergence edges
        divNodes.forEach { node ->
            drawLine(
                color = outlineColor,
                start = inputCenter,
                end = node,
                strokeWidth = 3f
            )
            drawLine(
                color = secondaryColor.copy(alpha = 0.65f),
                start = node,
                end = ringCenter,
                strokeWidth = 3f
            )
        }

        // Convergence edges
        convNodes.forEach { node ->
            drawLine(
                color = primaryColor.copy(alpha = 0.65f),
                start = ringCenter,
                end = node,
                strokeWidth = 3f
            )
            drawLine(
                color = outlineColor,
                start = node,
                end = outputCenter,
                strokeWidth = 3f
            )
        }

        // Feedback Inhibition Control barrier & memoryless reseed path at bottom
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        drawLine(
            color = tertiaryColor,
            start = Offset(w * 0.88f, h * 0.88f),
            end = Offset(w * 0.50f, h * 0.88f),
            strokeWidth = 3.5f,
            pathEffect = dash
        )
        drawLine(
            color = tertiaryColor,
            start = Offset(w * 0.50f, h * 0.88f),
            end = Offset(w * 0.50f, h * 0.62f),
            strokeWidth = 3.5f,
            pathEffect = dash
        )

        // Sealed Markov Blanket ring
        drawCircle(
            color = surfaceColor,
            radius = minOf(w, h) * 0.16f,
            center = ringCenter
        )
        drawCircle(
            color = secondaryColor,
            radius = minOf(w, h) * 0.16f,
            center = ringCenter,
            style = Stroke(width = 4f, pathEffect = dash)
        )

        // Draw nodes
        drawNode(inputCenter, primaryColor, 14f)
        divNodes.forEach { drawNode(it, secondaryColor, 11f) }
        drawNode(ringCenter, primaryColor, 15f)
        convNodes.forEach { drawNode(it, tertiaryColor, 11f) }
        drawNode(outputCenter, secondaryColor, 14f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNode(
    center: Offset,
    fill: Color,
    radius: Float
) {
    drawCircle(color = fill, radius = radius, center = center)
}

@Composable
private fun TopologyCaption(title: String, detail: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AuditLogCard(log: AuditLogEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (log.action == "SIDECAR_RESEED") MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${log.action} · ${log.patchId}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (log.action == "SIDECAR_RESEED") MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = "DoF ${"%.2f".format(log.dofScore)} · ${log.latencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (log.details.isNotBlank()) {
                Text(
                    text = log.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "${log.inputStateHash.take(10)} → ${log.outputStateHash.take(10)} · Feedback Inhibition: ${if (log.bayesianFeedbackBlocked) "Active" else "Off"}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
