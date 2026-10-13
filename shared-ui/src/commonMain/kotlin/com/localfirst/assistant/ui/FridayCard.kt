package com.localfirst.assistant.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
/** One quiet surface treatment for accounts, images, memory and computer task content. */
@Composable
fun FridayCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    val colors=MaterialTheme.colorScheme
    if(onClick==null)Surface(modifier,shape=MaterialTheme.shapes.medium,color=colors.surface,tonalElevation=0.dp,shadowElevation=1.dp) { Column(content=content) }
    else Surface(onClick=onClick,modifier=modifier,shape=MaterialTheme.shapes.medium,color=colors.surface,tonalElevation=0.dp,shadowElevation=1.dp) { Column(content=content) }
}


@Composable
fun FridayVisibleEffect(vararg keys: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
 val visible = rememberAppVisible()
 LaunchedEffect(visible, *keys) { if (visible) block() }
}
