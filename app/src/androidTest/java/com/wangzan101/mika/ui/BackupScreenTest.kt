package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Rule
import org.junit.Test

class BackupScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test
    fun exportRequiresMatchingLongPassphrase() {
        compose.setContent { MikaTheme(dark = false, reduceMotion = true) { BackupScreen(onBack = {}) } }
        compose.onNodeWithText("导出备份").assertIsDisplayed().performClick()
        compose.onNodeWithText("导出到…").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("backup-passphrase").performScrollTo().performTextInput("short")
        compose.onNodeWithTag("backup-confirm").performScrollTo().performTextInput("short")
        compose.onNodeWithText("导出到…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("口令至少 8 位").assertIsDisplayed()

        compose.onNodeWithTag("backup-passphrase").performScrollTo().performTextInput("long enough")
        compose.onNodeWithTag("backup-confirm").performScrollTo().performTextInput("long enough")
        compose.onNodeWithText("导出到…").performScrollTo().assertIsEnabled()
    }

    @Test
    fun homeOffersBothDirections() {
        compose.setContent { MikaTheme(dark = false, reduceMotion = true) { BackupScreen(onBack = {}) } }
        compose.onNodeWithText("导出备份").assertIsDisplayed()
        compose.onNodeWithText("从备份恢复").assertIsDisplayed()
    }
}
