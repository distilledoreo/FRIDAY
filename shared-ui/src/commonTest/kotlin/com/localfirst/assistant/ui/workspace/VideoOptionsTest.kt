package com.localfirst.assistant.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class VideoOptionsTest {
    private val options = Json.parseToJsonElement("""{"available":true,"durations":[2,3,4],"aspects":["16:9","9:16"],"levels":2,"inputs":[],
        "plans":{"16:9":{"4":[{"width":640,"height":352,"frames":97,"pauses_chat":false,"estimate_seconds":410,"chat_paused_seconds":0},
                             {"width":1600,"height":896,"frames":97,"pauses_chat":true,"estimate_seconds":2436,"chat_paused_seconds":2676}],"3":[]}}}""").jsonObject

    @Test fun readsTheComputersTable() {
        assertEquals(listOf(2, 3, 4), options.videoDurations())
        assertEquals(listOf("16:9", "9:16"), options.videoAspects())
        val levels = options.videoLevels("16:9", 4)
        assertEquals(2, levels.size)
        assertEquals(VideoLevel(640, 352, 97, false, 410, 0), levels[0])
        assertTrue(levels[1].pausesChat)
        // A length that doesn't fit, or a shape the table lacks, has no levels.
        assertTrue(options.videoLevels("16:9", 3).isEmpty())
        assertTrue(options.videoLevels("1:1", 4).isEmpty())
    }

    @Test fun describesTimeAndChat() {
        val (fast, best) = options.videoLevels("16:9", 4)
        assertEquals("640 × 352 · about 7 min", fast.summary())
        assertEquals("Chat keeps working while it renders.", fast.chatNote())
        assertEquals("Uses both GPUs: chat and voice pause for about 45 min.", best.chatNote())
        assertEquals("about 1 min", minutesLabel(45))
    }

    @Test fun jobStatusShowsStepAndTimeLeft() {
        val job = Json.parseToJsonElement("""{"status":"running","phase":"sampling","step":{"step":3,"steps":8},"progress":0.5,
            "plan":{"estimate_seconds":1200}}""").jsonObject
        assertEquals("Rendering · step 3/8 · about 10 min left", job.videoStatus())
        val paused = Json.parseToJsonElement("""{"status":"running","phase":"unloading_chat"}""").jsonObject
        assertEquals("Pausing chat", paused.videoStatus())
    }

    @Test fun inputLimitsMatchTheComputersRules() {
        val none = emptyList<VideoInputDraft>()
        assertTrue(none.allows("start_image") && none.allows("keyframe"))
        val start = listOf(VideoInputDraft("start_image", "a", "photo.png"))
        assertTrue(!start.allows("start_image") && !start.allows("source_video") && start.allows("end_image"))
        val clip = listOf(VideoInputDraft("source_video", "b", "clip.mp4"))
        assertTrue(!clip.allows("start_image") && clip.allows("audio"))
        val four = List(4) { VideoInputDraft("keyframe", "k$it", "k.png", 1f) }
        assertTrue(!four.allows("keyframe"))
        assertTrue(!none.allows("reference_image"))
    }
}
