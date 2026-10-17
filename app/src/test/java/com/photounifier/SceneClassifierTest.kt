package com.photounifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClassifierTest {
    @Test
    fun labelsDocumentAsDocument() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Document", 0.91f), LabelSignal("Text", 0.83f))
        )
        assertEquals(ImageKind.DOCUMENT, result.kind)
    }

    @Test
    fun labelsPosterAsPosterEvenWhenTextIsPresent() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Poster", 0.87f), LabelSignal("Text", 0.93f))
        )
        assertEquals(ImageKind.POSTER, result.kind)
    }

    @Test
    fun labelsInteriorScene() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Interior design", 0.88f), LabelSignal("Furniture", 0.76f))
        )
        assertEquals(ImageKind.INTERIOR, result.kind)
    }

    @Test
    fun labelsExteriorScene() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Landscape", 0.91f), LabelSignal("Outdoor", 0.85f))
        )
        assertEquals(ImageKind.EXTERIOR, result.kind)
    }

    @Test
    fun nearlyTiedDocumentAndPosterEvidenceFallsBackToGeneral() {
        val result = SceneClassifier.classifyLabels(
            listOf(
                LabelSignal("document", 0.5333333f),
                LabelSignal("poster", 0.5f)
            )
        )
        assertEquals(ImageKind.GENERAL, result.kind)
    }

    @Test
    fun weakEvidenceFallsBackToGeneral() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Object", 0.91f), LabelSignal("Pattern", 0.72f))
        )
        assertEquals(ImageKind.GENERAL, result.kind)
    }

    @Test
    fun genericTextAloneDoesNotTriggerDocumentProcessing() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Text", 0.99f))
        )
        assertEquals(ImageKind.GENERAL, result.kind)
    }

    @Test
    fun genericSkyOrBuildingDoesNotTriggerExteriorProcessing() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Sky", 0.99f), LabelSignal("Building", 0.88f))
        )
        assertEquals(ImageKind.GENERAL, result.kind)
    }

    @Test
    fun lowConfidenceSpecificLabelFallsBackToGeneral() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Document", 0.25f), LabelSignal("Text", 0.98f))
        )
        assertEquals(ImageKind.GENERAL, result.kind)
    }

    @Test
    fun labelsContainingKeywordsAsSubstringsDoNotMatch() {
        val result = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Outdoorish", 0.99f), LabelSignal("Bookcase", 0.96f))
        )
        assertEquals(ImageKind.GENERAL, result.kind)
    }

    @Test
    fun emptyAndInvalidSignalsFallbackSafely() {
        assertEquals(
            ImageKind.GENERAL,
            SceneClassifier.classifyLabels(emptyList()).kind
        )
        val invalid = SceneClassifier.classifyLabels(
            listOf(LabelSignal("Document", Float.NaN), LabelSignal(" ", 0.8f))
        )
        assertEquals(ImageKind.GENERAL, invalid.kind)
        assertTrue(invalid.matchedLabels.isEmpty())
    }
}
