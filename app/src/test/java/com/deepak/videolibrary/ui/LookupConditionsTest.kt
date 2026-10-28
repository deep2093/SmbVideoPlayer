package com.deepak.videolibrary.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookupConditionsTest {
    @Test fun saysNothingOnWifiWithAGoodBattery() = assertNull(LookupConditions.describe(false, 80, false))

    @Test fun namesMobileDataAndLowBattery() {
        assertEquals("mobile data", LookupConditions.describe(true, 80, false))
        assertEquals("a low battery (15%)", LookupConditions.describe(false, 15, false))
        assertEquals("mobile data and a low battery (10%)", LookupConditions.describe(true, 10, false))
    }

    @Test fun aChargingPhoneIsNotLowOnBattery() = assertNull(LookupConditions.describe(false, 5, true))
}
