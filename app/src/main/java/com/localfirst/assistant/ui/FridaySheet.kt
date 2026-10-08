package com.localfirst.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import kotlinx.coroutines.launch

/** Native draggable sheet, with an instant two-position dialog when the user reduces motion. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FridaySheet(onDismiss:()->Unit,content:@Composable ColumnScope.(expanded:Boolean,toggle:(()->Unit)?,close:()->Unit)->Unit) {
    if(LocalFridayPalette.current.reducedMotion) {
        var expanded by remember { mutableStateOf(false) }
        Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        FridayDialogWindow()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(if(expanded).1f else .45f).align(Alignment.TopCenter).clickable(onClick=onDismiss))
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if(expanded).9f else .55f),shape=RoundedCornerShape(topStart=16.dp,topEnd=16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.navigationBarsPadding()) {
                        Box(Modifier.fillMaxWidth().height(48.dp).clickable { expanded=!expanded }.semantics { contentDescription=if(expanded)"Collapse panel"else"Expand panel" }
                            .pointerInput(Unit) { var total=0f;detectVerticalDragGestures(onDragStart={total=0f},onDragEnd={if(total < -24.dp.toPx())expanded=true else if(total>24.dp.toPx()) { if(expanded)expanded=false else onDismiss() }}) { change,amount -> total+=amount;change.consume() } },contentAlignment=Alignment.Center) {
                            Box(Modifier.width(32.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(2.dp)))
                        }
                        content(expanded,{expanded=!expanded},onDismiss)
                    }
                }
            }
        }
    } else {
        val sheet=rememberModalBottomSheetState()
        val scope=rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheet,containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
            val toggle:(()->Unit)?=if(sheet.hasPartiallyExpandedState) ({
                scope.launch { if(sheet.currentValue==SheetValue.Expanded)sheet.partialExpand()else sheet.expand() };Unit
            }) else null
            content(sheet.currentValue==SheetValue.Expanded,toggle,{scope.launch { sheet.hide();onDismiss() }})
        }
    }
}
