package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test

class WebFilePolicyTest {
    @Test fun redirectsKeepCredentialsInsideTheSelectedOrigin() {
        assertEquals("https://example.org/download", WebFilePolicy.redirect("https://example.org", "https://example.org/api/export", "../download"))
        assertNull(WebFilePolicy.redirect("https://example.org", "https://example.org/api/export", "https://other.example/download"))
        assertNull(WebFilePolicy.redirect("https://example.org", "https://example.org/api/export", "//example.org.evil.test/download"))
        assertNull(WebFilePolicy.redirect("https://example.org", "https://example.org/api/export", "javascript:alert(1)"))
    }
    @Test fun filenameStaysWithinSelectedDestination() {
        assertEquals("export.zip", WebFilePolicy.safeName("../../export.zip"))
        assertEquals("dsh-export.bin", WebFilePolicy.safeName(".."))
        assertEquals("report_.zip", WebFilePolicy.safeName("report\n.zip"))
        assertEquals("test.zip", WebFilePolicy.safeName("C:\\private\\test.zip"))
    }
    @Test fun originComparisonIsNotAPrefixMatch() {
        assertTrue(WebFilePolicy.sameOrigin("https://example.org:443/file", "https://example.org"))
        assertFalse(WebFilePolicy.sameOrigin("https://example.org.attacker.test", "https://example.org"))
        assertFalse(WebFilePolicy.sameOrigin("http://example.org", "https://example.org"))
    }
    @Test fun pickerAcceptsOnlyExplicitContentUris() {
        assertTrue(WebFilePolicy.acceptedUris(listOf("content", "content")))
        assertFalse(WebFilePolicy.acceptedUris(listOf("file")))
        assertFalse(WebFilePolicy.acceptedUris(emptyList()))
    }
    @Test fun clipOnlyPickerResultsAreNotDiscarded() {
        assertEquals(listOf("content://test/a"),WebFilePolicy.chooserItems(true,null,listOf("content://test/a"),emptyList()))
    }
    @Test fun singleSelectionAndFallbackRemainSupported() {
        assertEquals(listOf("content://test/a"),WebFilePolicy.chooserItems(true,"content://test/a",emptyList(),emptyList()))
        assertEquals(listOf("content://test/b"),WebFilePolicy.chooserItems(true,null,emptyList(),listOf("content://test/b")))
    }
    @Test fun multiSelectionPreservesOrderAndRemovesRepeatedUris() {
        assertEquals(listOf("a","b"),WebFilePolicy.chooserItems(true,"a",listOf("a","b"),listOf("b")))
    }
    @Test fun cancelledPickerNeverDeliversStaleSelections() {
        assertTrue(WebFilePolicy.chooserItems(false,"a",listOf("b"),listOf("c")).isEmpty())
    }
    @Test fun blobOffersAreBoundedAndTokensCannotInjectScript() {
        assertTrue(WebFilePolicy.validBlobOffer("valid-token-1234567890", 0))
        assertFalse(WebFilePolicy.validBlobOffer("bad');alert(1);//", 10))
        assertFalse(WebFilePolicy.validBlobOffer("valid-token-1234567890", 536870913))
    }
}
