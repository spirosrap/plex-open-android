package com.spiros.plexopenandroid;

final class DownloadsLibrary {
    static final String KEY = "__offline__";

    private DownloadsLibrary() {
    }

    static Models.Library create(String title) {
        Models.Library library = new Models.Library();
        library.key = KEY;
        library.title = title;
        library.type = "movie";
        return library;
    }

    static boolean matches(Models.Library library) {
        return library != null && isKey(library.key);
    }

    static boolean isKey(String key) {
        return KEY.equals(key);
    }

    static String serverLibraryKey(String key) {
        return isKey(key) ? "" : Models.nonEmpty(key, "");
    }
}
