package com.localfirst.assistant.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.flow.collect

/** Following a growing answer is separate from the user's deliberate reading position. */
@Stable
class TranscriptScrollState(val list: LazyListState) {
    var following by mutableStateOf(true)
        private set
    val showJump: Boolean get() = !following && list.canScrollForward
    val input = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0 && list.canScrollBackward) following = false
            return Offset.Zero
        }
    }
    fun reachedEnd() { following = true }
    suspend fun followContent(count: Int) { if (following) scrollEnd(count) }
    suspend fun jump(count: Int) { following = true; scrollEnd(count) }
    private suspend fun scrollEnd(count: Int) {
        if (count == 0) return
        val last = count - 1
        list.scrollToItem(last)
        val height = list.layoutInfo.visibleItemsInfo.lastOrNull { it.index == last }?.size ?: 0
        list.scrollToItem(last, height)
    }
}

@Composable
fun rememberTranscriptScroll(key: Any?): TranscriptScrollState {
    val position = remember(key) { TranscriptScrollState(LazyListState()) }
    LaunchedEffect(position) {
        snapshotFlow { !position.list.canScrollForward }.collect { atEnd -> if (atEnd) position.reachedEnd() }
    }
    return position
}
