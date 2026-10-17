package com.photounifier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextOcrEnhancerRoutingTest {
    @Test
    fun onlyClassifierConfirmedDocumentsUseOcrEnhancement() {
        assertTrue(OcrRoutingPolicy.shouldRunDocumentOcr(ImageKind.DOCUMENT))
        assertFalse(OcrRoutingPolicy.shouldRunDocumentOcr(ImageKind.GENERAL))
        assertFalse(OcrRoutingPolicy.shouldRunDocumentOcr(ImageKind.INTERIOR))
        assertFalse(OcrRoutingPolicy.shouldRunDocumentOcr(ImageKind.EXTERIOR))
        assertFalse(OcrRoutingPolicy.shouldRunDocumentOcr(ImageKind.POSTER))
    }

    @Test
    fun photographicCategoriesNeverEnterDocumentOcrRoute() {
        val photoKinds = listOf(ImageKind.GENERAL, ImageKind.INTERIOR, ImageKind.EXTERIOR)
        assertTrue(photoKinds.none(OcrRoutingPolicy::shouldRunDocumentOcr))
    }
}
