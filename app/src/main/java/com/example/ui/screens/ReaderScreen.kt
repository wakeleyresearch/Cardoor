package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DocumentSection
import com.example.data.model.KnowledgeTriple
import com.example.data.model.LibraryDocument
import com.example.data.model.StandoffAnnotation
import com.example.data.model.UserRole
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NewsreaderFontFamily
import com.example.ui.theme.PlusJakartaFontFamily
import com.example.ui.viewmodel.ReaderSettings

@Composable
fun ReaderScreen(
    document: LibraryDocument?,
    sections: List<DocumentSection>,
    triples: List<KnowledgeTriple>,
    annotations: List<StandoffAnnotation>,
    readerSettings: ReaderSettings,
    userRole: UserRole,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onReseedSection: (DocumentSection) -> Unit,
    onAddAnnotation: (sectionId: String, excerpt: String, note: String, tagColor: String) -> Unit,
    onDeleteAnnotation: (String) -> Unit,
    onPlayFromSection: (Int) -> Unit
) {
    if (document == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_empty_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.reader_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showTypographyBar by remember { mutableStateOf(false) }
    var annotateTarget by remember { mutableStateOf<DocumentSection?>(null) }

    val tabs = listOf(
        stringResource(R.string.reader_tab_reflow),
        stringResource(R.string.reader_tab_dita),
        stringResource(R.string.reader_tab_rdf),
        stringResource(R.string.reader_tab_receipt)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        // Header + Mode Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
                Text(
                    text = "${document.sourceFormat} · ${document.attestedModel} · SHACL ${(document.shaclScore * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            IconButton(
                onClick = { showTypographyBar = !showTypographyBar },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("toggle_typography_controls")
            ) {
                Icon(Icons.Outlined.FormatSize, contentDescription = "Typography controls")
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            tabs.forEachIndexed { idx, label ->
                SegmentedButton(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                    modifier = Modifier.testTag("reader_mode_tab_$idx")
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }

        if (showTypographyBar) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = readerSettings.useSerif,
                        onClick = { onUpdateSettings { it.copy(useSerif = !it.useSerif) } },
                        label = { Text(if (readerSettings.useSerif) "Newsreader Serif" else "Jakarta Sans") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    OutlinedButton(
                        onClick = { onUpdateSettings { it.copy(fontSizeSp = (it.fontSizeSp - 1.5f).coerceAtLeast(13f)) } },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("A-") }
                    OutlinedButton(
                        onClick = { onUpdateSettings { it.copy(fontSizeSp = (it.fontSizeSp + 1.5f).coerceAtMost(24f)) } },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("A+") }
                    OutlinedButton(
                        onClick = {
                            onUpdateSettings {
                                val next = if (it.lineHeightMultiplier >= 1.8f) 1.4f else it.lineHeightMultiplier + 0.15f
                                it.copy(lineHeightMultiplier = next)
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Leading ${"%.1f".format(readerSettings.lineHeightMultiplier)}") }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // Reflowable Editorial Prose + Stand-off Annotations
                val bodyFont: FontFamily = if (readerSettings.useSerif) NewsreaderFontFamily else PlusJakartaFontFamily
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(sections, key = { _, s -> s.id }) { idx, sec ->
                        val sectionAnnotations = annotations.filter { it.sectionId == sec.id }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 680.dp)
                                .padding(horizontal = (readerSettings.horizontalPaddingDp - 16).coerceAtLeast(0).dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sec.title,
                                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = bodyFont),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ProvenanceBadge(
                                    label = sec.sidecarStatus.replace('_', ' '),
                                    isHighlight = sec.sidecarStatus == "RESEEDED"
                                )
                                ProvenanceBadge(label = "DoF ${"%.2f".format(sec.dofScore)}")
                                ProvenanceBadge(label = sec.convergenceType.replace('_', ' '))
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = sec.plainText,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = bodyFont,
                                    fontSize = readerSettings.fontSizeSp.sp,
                                    lineHeight = (readerSettings.fontSizeSp * readerSettings.lineHeightMultiplier).sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { onPlayFromSection(idx) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("listen_sec_${sec.id}")
                                ) {
                                    Icon(Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(stringResource(R.string.reader_listen_cta))
                                }

                                OutlinedButton(
                                    onClick = { annotateTarget = sec },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("annotate_sec_${sec.id}")
                                ) {
                                    Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(stringResource(R.string.reader_annotate_cta))
                                }

                                if (userRole.canIngestOrReseed()) {
                                    TextButton(
                                        onClick = { onReseedSection(sec) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("reseed_sec_${sec.id}")
                                    ) {
                                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text(stringResource(R.string.reader_reseed_cta))
                                    }
                                }
                            }

                            if (sectionAnnotations.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                sectionAnnotations.forEach { ann ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                                        shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Stand-off [${ann.startOffset}..${ann.endOffset}] · ${ann.tagColor}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                                Text(
                                                    text = "\"${ann.selectedText}\"",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (ann.note.isNotBlank()) {
                                                    Text(
                                                        text = ann.note,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = { onDeleteAnnotation(ann.id) },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.DeleteOutline,
                                                    contentDescription = "Delete annotation"
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            1 -> {
                // DITA 1.3 XML AST View
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(sections, key = { _, s -> s.id }) { _, sec ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "${sec.title} · ${sec.urn}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = sec.ditaXml,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        lineHeight = 18.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            2 -> {
                // RDF / SHACL Knowledge Graph Triples
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(triples, key = { _, t -> t.id }) { _, triple ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = triple.urn,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Surface(
                                        color = if (triple.shaclStatus == "PASSED")
                                            MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "SHACL ${triple.shaclStatus} (${(triple.confidence * 100).toInt()}%)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (triple.shaclStatus == "PASSED")
                                                MaterialTheme.colorScheme.secondary
                                            else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "${triple.subject}  —[ ${triple.predicate} ]→  ${triple.objectValue}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            3 -> {
                // Cloud Provenance & Markovian Receipt
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Text("Cloud-Attested Provenance Certificate", style = MaterialTheme.typography.titleMedium)
                                }
                                ReceiptLine("Source SHA-256 Digest", document.sourceDigest)
                                ReceiptLine("Truth Manifest Hash (Markov Blanket)", document.truthManifestHash)
                                ReceiptLine("Merkle Lineage Root", document.merkleRootHash)
                                ReceiptLine("Attested Cloud Model", document.attestedModel)
                                ReceiptLine("Provenance Status", document.provenanceStatus)
                                ReceiptLine(
                                    "Feedback Inhibition Control",
                                    if (document.bayesianFeedbackBlocked) "ACTIVE (0.0 Bayesian false belief carryover)" else "WARNING"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val target = annotateTarget
    if (target != null) {
        var excerpt by remember(target.id) { mutableStateOf(target.plainText.take(64)) }
        var noteText by remember(target.id) { mutableStateOf("") }
        var selectedColor by remember(target.id) { mutableStateOf("TERRACOTTA") }

        AlertDialog(
            onDismissRequest = { annotateTarget = null },
            title = { Text("W3C Stand-Off Annotation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = excerpt,
                        onValueChange = { excerpt = it },
                        label = { Text("Anchored Excerpt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Editorial Note") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("TERRACOTTA", "SAGE", "OCHRE").forEach { tag ->
                            FilterChip(
                                selected = selectedColor == tag,
                                onClick = { selectedColor = tag },
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddAnnotation(target.id, excerpt, noteText, selectedColor)
                        annotateTarget = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Save Note")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { annotateTarget = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProvenanceBadge(label: String, isHighlight: Boolean = false) {
    Surface(
        color = if (isHighlight) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ReceiptLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
