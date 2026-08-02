package com.spiros.plexopenandroid;

final class OfflineConnectionStatus {
    enum Cause {
        AIRPLANE_MODE,
        NO_INTERNET,
        TAILSCALE_DISCONNECTED,
        PLEX_UNAVAILABLE
    }

    private OfflineConnectionStatus() {}

    static Cause preflight(
            boolean airplaneMode,
            boolean internetAvailable,
            boolean tailnetServer,
            boolean vpnActive
    ) {
        if (airplaneMode) {
            return Cause.AIRPLANE_MODE;
        }
        if (!internetAvailable) {
            return Cause.NO_INTERNET;
        }
        if (tailnetServer && !vpnActive) {
            return Cause.TAILSCALE_DISCONNECTED;
        }
        return null;
    }

    static String message(Cause cause, boolean tailnetServer) {
        switch (cause) {
            case AIRPLANE_MODE:
                return "Airplane mode is on. Staying offline.";
            case NO_INTERNET:
                return "No working internet connection. Staying offline.";
            case TAILSCALE_DISCONNECTED:
                return "Internet works, but Tailscale is disconnected. Staying offline.";
            case PLEX_UNAVAILABLE:
                return tailnetServer
                        ? "Internet and Tailscale work, but Plex did not respond. Staying offline."
                        : "Internet works, but Plex did not respond. Staying offline.";
            default:
                throw new IllegalArgumentException("Unknown offline cause: " + cause);
        }
    }

    static String readyToReconnectMessage(boolean tailnetServer) {
        return tailnetServer
                ? "Internet and Tailscale are available. Select Reconnect now."
                : "Internet is available. Select Reconnect now.";
    }
}
