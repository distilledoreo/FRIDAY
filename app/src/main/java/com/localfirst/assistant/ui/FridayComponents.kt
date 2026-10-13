package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle

/** One quiet surface treatment for accounts, images, memory and computer task content. */
@Composable
internal fun FridayCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    val colors=MaterialTheme.colorScheme
    if(onClick==null)Surface(modifier,shape=MaterialTheme.shapes.medium,color=colors.surface,tonalElevation=0.dp,shadowElevation=1.dp) { Column(content=content) }
    else Surface(onClick=onClick,modifier=modifier,shape=MaterialTheme.shapes.medium,color=colors.surface,tonalElevation=0.dp,shadowElevation=1.dp) { Column(content=content) }
}

/** Dialog windows have their own system bars; follow the same appearance and motion preference. */
/** UI polling only runs while its screen is visible; foreground voice infrastructure is separate. */
@Composable
internal fun FridayVisibleEffect(vararg keys:Any?,block:suspend kotlinx.coroutines.CoroutineScope.()->Unit) {
    val owner=androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(owner,*keys) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED,block)
    }
}
