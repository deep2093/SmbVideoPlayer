package com.deepak.videolibrary.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BreadcrumbTest {
    @Test fun emptyPathHasNoCrumbs() {
        assertEquals(emptyList<Crumb>(), breadcrumbs(""))
    }

    @Test fun nestedPathBecomesCumulativeCrumbs() {
        assertEquals(
            listOf(Crumb("Movies", "Movies"), Crumb("2024", "Movies/2024"), Crumb("Trips", "Movies/2024/Trips")),
            breadcrumbs("Movies/2024/Trips"),
        )
    }

    @Test fun rootIsHiddenBehindHomeButton() {
        assertEquals(
            listOf(Crumb("Trips", "Videos/Home/Trips")),
            breadcrumbs("Videos/Home/Trips", root = "Videos/Home"),
        )
        assertEquals(emptyList<Crumb>(), breadcrumbs("Videos/Home", root = "Videos/Home"))
    }

    @Test fun pathOutsideRootKeepsFullPath() {
        assertEquals(listOf(Crumb("Other", "Other")), breadcrumbs("Other", root = "Videos"))
    }

    @Test fun slashesAreTrimmed() {
        assertEquals(listOf(Crumb("a", "a"), Crumb("b", "a/b")), breadcrumbs("/a//b/"))
    }
}
