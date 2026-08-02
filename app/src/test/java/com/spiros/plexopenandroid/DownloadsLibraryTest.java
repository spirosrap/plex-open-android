package com.spiros.plexopenandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DownloadsLibraryTest {
    @Test
    public void createsARecognizableLocalLibrary() {
        Models.Library library = DownloadsLibrary.create("Downloads");

        assertEquals(DownloadsLibrary.KEY, library.key);
        assertEquals("Downloads", library.label());
        assertTrue(DownloadsLibrary.matches(library));
    }

    @Test
    public void localLibraryKeyIsNeverSentToTheServer() {
        assertEquals("", DownloadsLibrary.serverLibraryKey(DownloadsLibrary.KEY));
        assertEquals("7", DownloadsLibrary.serverLibraryKey("7"));
    }

    @Test
    public void regularAndMissingLibrariesAreNotLocalDownloads() {
        Models.Library library = new Models.Library();
        library.key = "7";

        assertFalse(DownloadsLibrary.matches(library));
        assertFalse(DownloadsLibrary.matches(null));
    }
}
