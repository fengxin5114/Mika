package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOptionsTest {
    @Test
    fun defaultsExcludeWorkspaceButIncludeSecrets() {
        val sections = BackupOptions().sections()
        assertTrue(BackupFormat.SECRETS in sections)
        assertFalse(BackupFormat.WORKSPACE in sections)
        assertTrue(BackupFormat.CONVERSATIONS in sections)
        assertTrue(BackupFormat.MEMORY in sections)
        assertTrue(BackupFormat.SKILLS in sections)
        assertTrue(BackupFormat.AUTOMATION in sections)
        assertTrue(BackupFormat.WEB in sections)
    }

    @Test
    fun everyToggleIsReflected() {
        val none = BackupOptions(
            includeSettings = false, includeSecrets = false, includeConversations = false, includeMemory = false,
            includeSkills = false, includeKnowledge = false, includeAutomation = false, includeWeb = false,
            includeWorkspace = false,
        )
        assertTrue(none.sections().isEmpty())

        val all = none.copy(
            includeSettings = true, includeSecrets = true, includeConversations = true, includeMemory = true,
            includeSkills = true, includeKnowledge = true, includeAutomation = true, includeWeb = true,
            includeWorkspace = true,
        )
        assertEquals(BackupFormat.ALL_SECTIONS.toSet(), all.sections().toSet())
    }
}
