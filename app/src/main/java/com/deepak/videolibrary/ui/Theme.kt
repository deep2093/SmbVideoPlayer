package com.deepak.videolibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red=Color(0xFFB00000)
private val AppColors=darkColorScheme(
    primary=Red,onPrimary=Color.White,primaryContainer=Color.Black,onPrimaryContainer=Color.White,
    secondary=Red,onSecondary=Color.White,secondaryContainer=Color.Black,onSecondaryContainer=Color.White,
    tertiary=Red,onTertiary=Color.White,tertiaryContainer=Color.Black,onTertiaryContainer=Color.White,
    background=Color.Black,onBackground=Color.White,surface=Color.Black,onSurface=Color.White,
    surfaceVariant=Color.Black,onSurfaceVariant=Color.White,surfaceTint=Red,
    outline=Color.White.copy(alpha=0.5f),outlineVariant=Color.White.copy(alpha=0.25f),
    error=Red,onError=Color.White,errorContainer=Color.Black,onErrorContainer=Red,
    inverseSurface=Color.White,inverseOnSurface=Color.Black,inversePrimary=Red,
    surfaceContainer=Color.Black,surfaceContainerLow=Color.Black,surfaceContainerHigh=Color.Black,
    surfaceContainerHighest=Color.Black,surfaceContainerLowest=Color.Black,
)
@Composable
fun VideoLibraryTheme(content:@Composable ()->Unit){MaterialTheme(colorScheme=AppColors,content=content)}
