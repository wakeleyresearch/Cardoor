package com.example.domain.pipeline

import com.example.data.model.ConvergenceType
import com.example.data.model.SidecarStatus
import com.example.domain.parsers.DocumentContainerParser
import com.example.domain.parsers.RawSourceBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkovianKernelTest {

    @Test
    fun divergenceAndCentralRingSealTruthManifestAndBlockBayesianFeedbackUpdating() {
        val rawText = "Deterministic DITA 1.3 topics anchor SHA-256 URNs inside a sealed Markov Blanket."
        val digest = DocumentContainerParser.sha256Hex(rawText)
        val initial = MarkovianKernel.PipelineEnvelope(
            envelopeId = "env_test_01",
            documentId = "doc_test_01",
            title = "Markovian Spec",
            sourceDigest = digest,
            rawBlocks = listOf(
                RawSourceBlock(
                    title = "Blanket Isolation",
                    text = rawText,
                    sourceSpan = "dita:0-84"
                )
            )
        )

        val divergence = MarkovianKernel.runDivergenceStage(initial)
        val manifest = requireNotNull(divergence.truthManifest)
        assertEquals(1, manifest.allowedBlockUrns.size)
        assertTrue(manifest.bayesianFeedbackBlocked)
        assertEquals(4, divergence.checkpoints.size)

        // Inject hallucinated candidate with an out-of-bounds URN
        val hallucinatedInput = listOf(
            MarkovianKernel.CloudSynthesizedSectionInput(
                sectionId = "sec_0",
                title = "Blanket Isolation",
                reflowText = "Completely fabricated cosmic narrative unrelated to XML provenance.",
                citedUrns = listOf("urn:foliograph:sha256:out_of_bounds_urn"),
                prosodyTone = "analytical",
                triples = emptyList()
            )
        )

        val converged = MarkovianKernel.runCentralRingAndConvergence(
            frozenDivergenceEnvelope = divergence,
            synthesizedInputs = hallucinatedInput,
            register = MarkovianKernel.ProsodyRegister.WRITTEN_TO_BE_READ_OUT_LOUD,
            dofThreshold = 0.45,
            cloudAttested = true
        )

        // Feedback Inhibition Control invariant: prior TruthManifest remains bit-for-bit identical
        assertEquals(manifest.manifestHash, converged.truthManifest?.manifestHash)
        assertTrue(converged.checkpoints.all { it.feedbackInhibitionVerified && it.outputPosteriorCarryover == 0.0 })

        // Parsing Sidecar must have reseeded the hallucinated output back to the source locus
        val output = converged.compiledOutputs.single()
        assertEquals(1, output.reseedCount)
        assertEquals(SidecarStatus.RESEEDED, output.sidecarStatus)
        assertEquals(ConvergenceType.CONDITIONAL_GUARD, output.convergenceType)
        assertTrue(output.reflowText.contains("Deterministic DITA 1.3"))
        assertNotEquals("Completely fabricated cosmic narrative unrelated to XML provenance.", output.reflowText)
        assertTrue(output.ssmlProsody.contains("<speak version=\"1.1\""))
    }
}
