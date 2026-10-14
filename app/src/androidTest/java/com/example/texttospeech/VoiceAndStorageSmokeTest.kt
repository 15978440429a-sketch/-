package com.example.texttospeech

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceAndStorageSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchesOnlineVoicesAndPlaybackModesWithoutCrashing() {
        selectVoice("online.ayaka")
        selectVoice("online.eula")
        selectVoice("online.klee")

        composeRule.onNodeWithTag("mode_repeat_one")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("mode_shuffle").performClick().assertIsSelected()
        composeRule.onNodeWithTag("mode_sequential").performClick().assertIsSelected()
    }

    @Test
    fun draftSurvivesActivityRecreation() {
        val draft = "这段文字用于验证退出界面后仍能恢复。"
        composeRule.onNodeWithTag("text_input")
            .performScrollTo()
            .performTextReplacement(draft)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("text_input").assertTextContains(draft)
            }.isSuccess
        }
        composeRule.onNodeWithTag("text_input").assertTextContains(draft)
    }

    @Test
    fun documentNameInputStartsBlank() {
        composeRule.onNodeWithTag("text_input")
            .performScrollTo()
            .performTextReplacement("这是正文，不应该自动成为文稿名称。")

        composeRule.onNodeWithTag("save_document")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("document_name_input").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("")
            )
        )
    }

    private fun selectVoice(id: String) {
        composeRule.onNodeWithTag("voice_selector").performScrollTo().performClick()
        composeRule.onNodeWithTag("voice_$id").performClick()
        composeRule.waitForIdle()
    }
}
