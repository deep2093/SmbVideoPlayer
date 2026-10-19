package com.deepak.videolibrary.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class LibraryTheme(val id:String,val label:String,val background:Color,val surface:Color,val card:Color,val accent:Color)
val libraryThemes=listOf(
    LibraryTheme("CHARCOAL","Dark grey",Color(0xFF121416),Color(0xFF191C1F),Color(0xFF24282D),Color(0xFFBB3333)),
    LibraryTheme("AMOLED","AMOLED black",Color.Black,Color.Black,Color(0xFF101010),Color(0xFFB00000)),
    LibraryTheme("SLATE","Midnight blue",Color(0xFF141C29),Color(0xFF1B2738),Color(0xFF26364D),Color(0xFF3569AA)),
    LibraryTheme("FOREST","Forest",Color(0xFF14221D),Color(0xFF1D3028),Color(0xFF2A4036),Color(0xFF26715B)),
    LibraryTheme("PLUM","Plum",Color(0xFF241A2B),Color(0xFF30233A),Color(0xFF40304D),Color(0xFF884EAA)),
)
@Composable
private fun rememberThemeChoice():State<String> {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("appearance",Context.MODE_PRIVATE)}
    fun read()=prefs.getString("theme","CHARCOAL")?.takeIf {name->libraryThemes.any {it.id==name}} ?: "CHARCOAL"
    val selected=remember {mutableStateOf(read())}
    DisposableEffect(prefs) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key=="theme")selected.value=read()}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return selected
}
@Composable
fun VideoLibraryTheme(content:@Composable ()->Unit) {
    val choice by rememberThemeChoice()
    val theme=libraryThemes.first {it.id==choice}
    val colors=darkColorScheme(
        primary=theme.accent,onPrimary=Color.White,primaryContainer=theme.card,onPrimaryContainer=Color.White,
        secondary=theme.accent,onSecondary=Color.White,secondaryContainer=theme.card,onSecondaryContainer=Color.White,
        tertiary=theme.accent,onTertiary=Color.White,tertiaryContainer=theme.card,onTertiaryContainer=Color.White,
        background=theme.background,onBackground=Color.White,surface=theme.surface,onSurface=Color.White,
        surfaceVariant=theme.card,onSurfaceVariant=Color.White.copy(alpha=0.82f),surfaceTint=theme.accent,
        outline=Color.White.copy(alpha=0.5f),outlineVariant=Color.White.copy(alpha=0.25f),
        error=Color(0xFFFF6B6B),onError=Color.Black,errorContainer=Color(0xFF452323),onErrorContainer=Color.White,
        inverseSurface=Color.White,inverseOnSurface=theme.background,inversePrimary=theme.accent,
        surfaceContainer=theme.surface,surfaceContainerLow=theme.background,surfaceContainerHigh=theme.card,
        surfaceContainerHighest=theme.card,surfaceContainerLowest=theme.background,
    )
    MaterialTheme(colorScheme=colors,content=content)
}
@Composable
internal fun ThemeSettings() {
    val context=LocalContext.current
    val choice by rememberThemeChoice()
    var expanded by remember {mutableStateOf(false)}
    Text("Appearance",style=MaterialTheme.typography.titleMedium)
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {Text("Theme: ${libraryThemes.first {it.id==choice}.label}")}
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            libraryThemes.forEach {theme->DropdownMenuItem(text={Text(theme.label+if(theme.id==choice)" ✓" else "")},onClick={
                context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("theme",theme.id).apply();expanded=false
            })}
        }
    }
}
