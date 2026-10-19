package com.deepak.videolibrary.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.*

/** Original navigation component: shared backdrop blur, with a spring-driven selection pill. */
@Composable
internal fun FloatingLibraryNavigation(selected:Int,labels:List<String>,icons:List<ImageVector>,haze:HazeState,modifier:Modifier=Modifier,onSelect:(Int)->Unit) {
    val shape=RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().padding(horizontal=16.dp),contentAlignment=Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max=440.dp).fillMaxWidth().height(68.dp).clip(shape)
            .hazeChild(haze,HazeStyle(backgroundColor=MaterialTheme.colorScheme.surface,tint=HazeTint(MaterialTheme.colorScheme.surface.copy(alpha=0.76f)),blurRadius=22.dp,noiseFactor=0.04f))
            .border(1.dp,Color.White.copy(alpha=0.28f),shape)) {
            val slot=maxWidth/labels.size
            val offset by animateDpAsState(slot*selected,animationSpec=spring(dampingRatio=0.85f,stiffness=500f),label="navigation_selection")
            Box(Modifier.offset(x=offset).padding(5.dp).width(slot-10.dp).fillMaxHeight().clip(shape).background(MaterialTheme.colorScheme.primary.copy(alpha=0.75f)))
            Row(Modifier.fillMaxSize()) {labels.forEachIndexed {index,label->
                Column(Modifier.weight(1f).fillMaxHeight().selectable(selected=index==selected,role=Role.Tab,onClick={onSelect(index)}).padding(horizontal=2.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(icons[index],contentDescription=label,tint=Color.White,modifier=Modifier.size(23.dp))
                    Text(label,color=Color.White,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }}
        }
    }
}
