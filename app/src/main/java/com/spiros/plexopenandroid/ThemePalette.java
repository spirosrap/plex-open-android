package com.spiros.plexopenandroid;

import android.content.res.Configuration;
import android.graphics.Color;

final class ThemePalette {
    static final String PREF_KEY = "color_theme";
    static final String SYSTEM = "system";
    static final String LIGHT = "light";
    static final String DARK = "dark";

    final boolean dark;
    final int paper;
    final int surface;
    final int surfaceMuted;
    final int ink;
    final int muted;
    final int accent;
    final int danger;
    final int onAccent;
    final int poster;
    final int posterText;
    final int progressTrack;

    private ThemePalette(boolean dark) {
        this.dark = dark;
        if (dark) {
            paper = Color.rgb(16, 18, 20);
            surface = Color.rgb(34, 37, 40);
            surfaceMuted = Color.rgb(44, 48, 51);
            ink = Color.rgb(244, 241, 232);
            muted = Color.rgb(173, 169, 158);
            accent = Color.rgb(245, 182, 66);
            danger = Color.rgb(255, 138, 122);
            onAccent = Color.rgb(20, 20, 20);
            poster = Color.rgb(42, 47, 49);
            posterText = Color.rgb(190, 185, 174);
            progressTrack = Color.argb(210, 12, 13, 14);
        } else {
            paper = Color.rgb(250, 250, 247);
            surface = Color.rgb(232, 230, 222);
            surfaceMuted = Color.rgb(222, 220, 212);
            ink = Color.rgb(21, 21, 21);
            muted = Color.rgb(93, 92, 86);
            accent = Color.rgb(229, 160, 13);
            danger = Color.rgb(170, 36, 36);
            onAccent = Color.rgb(20, 20, 20);
            poster = Color.rgb(234, 232, 224);
            posterText = Color.rgb(80, 78, 72);
            progressTrack = Color.argb(190, 30, 30, 30);
        }
    }

    static ThemePalette from(String mode, Configuration configuration) {
        String normalized = normalize(mode);
        boolean systemDark = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new ThemePalette(DARK.equals(normalized) || (SYSTEM.equals(normalized) && systemDark));
    }

    static String normalize(String mode) {
        if (LIGHT.equals(mode) || DARK.equals(mode)) {
            return mode;
        }
        return SYSTEM;
    }

    static int index(String mode) {
        switch (normalize(mode)) {
            case LIGHT:
                return 1;
            case DARK:
                return 2;
            case SYSTEM:
            default:
                return 0;
        }
    }

    static String modeAt(int index) {
        switch (index) {
            case 1:
                return LIGHT;
            case 2:
                return DARK;
            case 0:
            default:
                return SYSTEM;
        }
    }
}
