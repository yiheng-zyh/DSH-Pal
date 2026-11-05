package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
class LocalAppearanceTest {
    @Test fun missingOrUnknownPreferenceAlwaysFollowsDsh() {
        for (id in listOf(null, "", "unknown", "';fetch('evil')")) assertEquals(LocalAppearance.DSH, LocalAppearance.fromId(id))
    }
    @Test fun savedLocalChoiceRoundTrips() {
        LocalAppearance.entries.forEach { assertEquals(it, LocalAppearance.fromId(it.id)) }
    }
    @Test fun customPaletteRoundTripsAndRejectsUntrustedInput() {
        val p=LocalAppearance.custom(218,38,94)
        assertEquals(p,LocalAppearance.fromId(p.id))
        for(id in listOf("custom:361:30:90","custom:10:101:90","custom:10:30:7",
            "custom:10:30:99","custom:NaN:30:90","custom:10:30:90;alert(1)","custom:-1:30:90")) {
            assertEquals(LocalAppearance.DSH,LocalAppearance.fromId(id))
        }
    }
    @Test fun customPaletteMaintainsReadableTextAndActionsAcrossSliderRanges() {
        for(h in 0..360 step 30) for(s in 0..100 step 20) for(l in 8..98 step 3) {
            val p=LocalAppearance.custom(h,s,l)
            assertTrue(p.id,LocalAppearance.contrast(p.foreground,p.background)>=4.5)
            assertTrue(p.id,LocalAppearance.contrast(LocalAppearance.readableOn(p.surface),p.surface)>=4.5)
            assertTrue(p.id,LocalAppearance.contrast(p.onAccent,p.accent)>=4.5)
            assertTrue(p.id,LocalAppearance.contrast(p.accent,p.background)>=4.5)
            assertTrue(p.id,LocalAppearance.contrast(p.accent,p.surface)>=4.5)
        }
    }
    @Test fun newPresetsAreLightAndExistingIdsRemainStable() {
        assertEquals(listOf("dsh","graphite","ocean","violet","forest"),LocalAppearance.entries.take(5).map { it.id })
        assertEquals(5,LocalAppearance.entries.count { it.isLight })
        LocalAppearance.entries.filter { it.isLight }.forEach {
            assertTrue(LocalAppearance.contrast(it.foreground,it.background)>=4.5)
            assertTrue(LocalAppearance.contrast(it.accent,it.surface)>=4.5)
        }
    }
}
