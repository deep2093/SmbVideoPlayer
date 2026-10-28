package com.deepak.videolibrary.scan

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRefreshTest {
    private val now = 1_800_000_000_000L

    @Test fun refreshesOnlyWhenTheLastScanIsTwelveHoursOldOrMore() {
        assertEquals(false, AutoRefresh.isDue(now - TimeUnit.HOURS.toMillis(11), now))
        assertEquals(true, AutoRefresh.isDue(now - TimeUnit.HOURS.toMillis(12), now))
        assertEquals(true, AutoRefresh.isDue(now - TimeUnit.DAYS.toMillis(3), now))
    }

    @Test fun aLibraryThatWasNeverScannedIsLeftToTheUser() {
        assertEquals(false, AutoRefresh.isDue(null, now))
    }
}
