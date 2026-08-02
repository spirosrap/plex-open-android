package com.spiros.plexopenandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class OfflineConnectionStatusTest {
    @Test
    public void airplaneModeTakesPriorityOverEveryOtherCause() {
        assertEquals(
                OfflineConnectionStatus.Cause.AIRPLANE_MODE,
                OfflineConnectionStatus.preflight(true, false, true, false)
        );
    }

    @Test
    public void missingInternetIsReportedBeforeTailscale() {
        assertEquals(
                OfflineConnectionStatus.Cause.NO_INTERNET,
                OfflineConnectionStatus.preflight(false, false, true, false)
        );
        assertEquals(
                "No working internet connection. Staying offline.",
                OfflineConnectionStatus.message(OfflineConnectionStatus.Cause.NO_INTERNET, true)
        );
    }

    @Test
    public void disconnectedTailscaleIsReportedWhenInternetWorks() {
        assertEquals(
                OfflineConnectionStatus.Cause.TAILSCALE_DISCONNECTED,
                OfflineConnectionStatus.preflight(false, true, true, false)
        );
        assertEquals(
                "Internet works, but Tailscale is disconnected. Staying offline.",
                OfflineConnectionStatus.message(
                        OfflineConnectionStatus.Cause.TAILSCALE_DISCONNECTED,
                        true
                )
        );
    }

    @Test
    public void connectedPathCanAttemptPlex() {
        assertNull(OfflineConnectionStatus.preflight(false, true, true, true));
        assertNull(OfflineConnectionStatus.preflight(false, true, false, false));
    }

    @Test
    public void plexFailureNamesTheRemainingLayer() {
        assertEquals(
                "Internet and Tailscale work, but Plex did not respond. Staying offline.",
                OfflineConnectionStatus.message(OfflineConnectionStatus.Cause.PLEX_UNAVAILABLE, true)
        );
    }

    @Test
    public void connectedOfflineScreenWaitsForManualReconnect() {
        assertEquals(
                "Internet and Tailscale are available. Select Reconnect now.",
                OfflineConnectionStatus.readyToReconnectMessage(true)
        );
    }
}
