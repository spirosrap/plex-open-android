package com.spiros.plexopenandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OfflineDownloadStateTest {
    @Test
    public void progressIsBoundedUntilTheDownloadIsCommitted() {
        assertEquals(-1, snapshot(OfflineDownloadState.PREPARING, 0L, -1L).percent());
        assertEquals(1, snapshot(OfflineDownloadState.DOWNLOADING, 1L, 1_000L).percent());
        assertEquals(50, snapshot(OfflineDownloadState.DOWNLOADING, 500L, 1_000L).percent());
        assertEquals(99, snapshot(OfflineDownloadState.DOWNLOADING, 1_000L, 1_000L).percent());
        assertEquals(100, snapshot(OfflineDownloadState.COMPLETE, 1_000L, 1_000L).percent());
    }

    @Test
    public void progressLabelsRemainUsefulWhenContentLengthIsUnknown() {
        OfflineDownloadState.Snapshot preparing = snapshot(OfflineDownloadState.PREPARING, 0L, -1L);
        OfflineDownloadState.Snapshot downloading = snapshot(OfflineDownloadState.DOWNLOADING, 200L, -1L);

        assertEquals("Preparing...", preparing.buttonLabel());
        assertEquals("Saving...", downloading.buttonLabel());
        assertEquals("Saving Test title offline...", downloading.statusText());
    }

    @Test
    public void terminalAndMatchingStateAreExplicit() {
        OfflineDownloadState.Snapshot complete = snapshot(OfflineDownloadState.COMPLETE, 1_000L, 1_000L);

        assertFalse(complete.isInProgress());
        assertTrue(complete.isTerminal());
        assertTrue(complete.matches("42"));
        assertFalse(complete.matches("43"));
        assertEquals("Test title is ready offline.", complete.statusText());
    }

    private static OfflineDownloadState.Snapshot snapshot(String stage, long bytes, long totalBytes) {
        return new OfflineDownloadState.Snapshot(
                "42",
                "Test title",
                stage,
                bytes,
                totalBytes,
                "",
                1L
        );
    }
}
