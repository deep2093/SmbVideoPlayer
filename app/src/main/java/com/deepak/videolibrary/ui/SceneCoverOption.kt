package com.deepak.videolibrary.ui

import java.io.File

/** One manually selectable confirmed scene image for a video or folder. */
internal data class SceneCoverOption(
    val title: String,
    val videoName: String,
    val providerName: String,
    val imageUrl: String?,
    val localFile: File?,
)
