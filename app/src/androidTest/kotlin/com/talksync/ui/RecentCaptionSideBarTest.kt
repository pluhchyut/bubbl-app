package com.talksync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.talksync.emotion.EmotionState
import com.talksync.model.RecentCaptionRef
import com.talksync.model.RecentCaptionState
import com.talksync.ui.components.RecentCaptionSideBar
import com.talksync.viewmodel.VisibleCaptionUi
import java.io.File
import org.junit.Rule
import org.junit.Test

class RecentCaptionSideBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captionsAppearOnHandoffThenFadeIndependently() {
        val transcript = listOf(caption("a", "Speaker 1", "Let's meet at the cafe."),
            caption("b", "Speaker 2", "Sounds good. See you there!"))
        val refs = mutableStateOf(emptyList<RecentCaptionRef>())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF263745))) {
                    RecentCaptionSideBar(
                        entries = refs.value.map { ref -> transcript.first { it.id == ref.captionId }.copy(isFading = ref.isFading) },
                        enabled = true,
                        panelWidth = 180.dp,
                        maxPanelHeight = 400.dp,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        compose.onNodeWithText("Just said").assertDoesNotExist()
        compose.runOnIdle { refs.value = RecentCaptionState.add(refs.value, "a", 0L) }
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithText(transcript[0].text).assertIsDisplayed()
        compose.runOnIdle { refs.value = RecentCaptionState.add(refs.value, "b", 5_000L) }
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithText(transcript[1].text).assertIsDisplayed()
        saveScreenshot("recent-captions-visible.png")
        compose.runOnIdle { refs.value = RecentCaptionState.advance(refs.value, 10_000L) }
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithText(transcript[0].text).assertDoesNotExist()
        compose.onNodeWithText(transcript[1].text).assertIsDisplayed()
        compose.runOnIdle { refs.value = RecentCaptionState.advance(refs.value, 15_000L) }
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithText(transcript[1].text).assertDoesNotExist()
        compose.runOnIdle { refs.value = RecentCaptionState.advance(refs.value, 15_600L) }
        compose.mainClock.advanceTimeBy(300L)
        compose.onNodeWithText("Just said").assertDoesNotExist()
    }

    private fun caption(id: String, speaker: String, text: String) = VisibleCaptionUi(
        id = id, speakerLabel = speaker, text = text, timestamp = 0L,
        wordVolumes = emptyList(), emotionState = EmotionState(), expandedTokens = emptyList(),
    )

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
