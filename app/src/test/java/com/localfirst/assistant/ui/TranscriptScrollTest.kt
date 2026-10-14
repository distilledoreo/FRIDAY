package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TranscriptScrollTest {
    @get:Rule val compose = createComposeRule()
    @Test fun readingOlderMessagesSurvivesGrowthAndJumpResumesFollowing() {
        var count by mutableIntStateOf(40)
        var chat by mutableStateOf("a")
        lateinit var reading: TranscriptScrollState
        compose.setContent {
            MaterialTheme {
                reading = rememberTranscriptScroll(chat)
                val scope = rememberCoroutineScope()
                LaunchedEffect(reading, count) { reading.followContent(count) }
                Column(Modifier.width(300.dp)) {
                    LazyColumn(state = reading.list, modifier = Modifier.height(300.dp).testTag("transcript").nestedScroll(reading.input)) {
                        items(count) { index -> Text("Message $index", Modifier.height(64.dp)) }
                    }
                    if (reading.showJump) Button({ scope.launch { reading.jump(count) } }) { Text("Jump to latest") }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(reading.list.canScrollForward) }
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        compose.waitForIdle()
        var first = 0
        var offset = 0
        compose.runOnIdle {
            assertFalse(reading.following)
            first = reading.list.firstVisibleItemIndex
            offset = reading.list.firstVisibleItemScrollOffset
            count += 4
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(first, reading.list.firstVisibleItemIndex)
            assertEquals(offset, reading.list.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithText("Jump to latest").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(reading.following); assertFalse(reading.list.canScrollForward); count++ }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(reading.list.canScrollForward); chat = "b" }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(reading.following); assertFalse(reading.list.canScrollForward) }
    }
}
