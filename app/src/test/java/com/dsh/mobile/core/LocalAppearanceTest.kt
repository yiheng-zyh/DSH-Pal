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
}
