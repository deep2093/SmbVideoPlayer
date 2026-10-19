package com.deepak.videolibrary.ui

import com.deepak.videolibrary.data.DirectorySummary

/** Tag asynchronous directory results with their location so old rows never clamp a restored grid. */
data class OriginalListing(val path: String="",val query: String="",val rows: List<DirectorySummary> = emptyList(),val videoCount: Int=0,val totalCount: Int=0,val ready: Boolean=false)
