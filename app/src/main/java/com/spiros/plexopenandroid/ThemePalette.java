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
    final int line;
    final int ink;
    final int muted;
    final int accent;
    final int highlight;
    final int danger;
    final int onAccent;
    final int poster;
    final int posterText;
    final int progressTrack;

    private ThemePalette(boolean dark) {
        this.dark = dark;
        if (dark) {
            paper = Color.rgb(14, 18, 20);
            surface = Color.rgb(28, 34, 38);
            surfaceMuted = Color.rgb(37, 45, 50);
            line = Color.rgb(55, 65, 70);
            ink = Color.rgb(244, 247, 246);
            muted = Color.rgb(160, 171, 175);
            accent = Color.rgb(245, 182, 66);
            highlight = Color.rgb(52, 190, 169);
            danger = Color.rgb(255, 128, 111);
            onAccent = Color.rgb(20, 20, 20);
            poster = Color.rgb(35, 43, 47);
            posterText = Color.rgb(180, 190, 193);
            progressTrack = Color.argb(210, 12, 13, 14);
        } else {
            paper = Color.rgb(243, 247, 246);
            surface = Color.rgb(255, 255, 255);
            surfaceMuted = Color.rgb(228, 234, 231);
            line = Color.rgb(200, 211, 206);
            ink = Color.rgb(22, 28, 26);
            muted = Color.rgb(84, 96, 91);
            accent = Color.rgb(218, 144, 0);
            highlight = Color.rgb(0, 130, 115);
            danger = Color.rgb(170, 36, 36);
            onAccent = Color.rgb(20, 20, 20);
            poster = Color.rgb(226, 233, 230);
            posterText = Color.rgb(73, 86, 81);
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
