package com.siftalpha.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTextFilePolicyTest {

    @Test
    fun environmentFilesAndTemplatesAreEditableEvenWithOctetStreamMime() {
        val mime = "application/octet-stream"
        assertTrue(ProjectTextFilePolicy.isEditableTextFileName(".env", mime))
        assertTrue(ProjectTextFilePolicy.isEditableTextFileName(".env.example", mime))
        assertTrue(ProjectTextFilePolicy.isEditableTextFileName(".env.sample", mime))
        assertTrue(ProjectTextFilePolicy.isEditableTextFileName(".env.template", mime))
        assertTrue(ProjectTextFilePolicy.isEditableTextFileName(".env.production", mime))
        assertTrue(ProjectTextFilePolicy.isEditableTextFileName(".env.production.example", mime))
    }

    @Test
    fun templateTargetPreservesEnvironmentVariant() {
        assertEquals(".env", ProjectTextFilePolicy.environmentTemplateTargetName(".env.example"))
        assertEquals(".env", ProjectTextFilePolicy.environmentTemplateTargetName(".env.sample"))
        assertEquals(".env", ProjectTextFilePolicy.environmentTemplateTargetName(".env.template"))
        assertEquals(
            ".env.production",
            ProjectTextFilePolicy.environmentTemplateTargetName(".env.production.example"),
        )
        assertNull(ProjectTextFilePolicy.environmentTemplateTargetName(".env.production"))
        assertNull(ProjectTextFilePolicy.environmentTemplateTargetName("config.example"))
    }

    @Test
    fun unrelatedBinaryNamesAreNotPromotedToText() {
        val mime = "application/octet-stream"
        assertFalse(ProjectTextFilePolicy.isEditableTextFileName("photo.png", mime))
        assertFalse(ProjectTextFilePolicy.isEditableTextFileName("archive.bin", mime))
        assertTrue(ProjectTextFilePolicy.isEditableTextFileName("README.md", mime))
    }

    @Test
    fun nulByteStillMarksPayloadUnsafeForTextEditing() {
        assertTrue(ProjectTextFilePolicy.isSafeUtf8TextPayload("A=1\n".toByteArray()))
        assertFalse(ProjectTextFilePolicy.isSafeUtf8TextPayload(byteArrayOf(65, 0, 66)))
    }
}
