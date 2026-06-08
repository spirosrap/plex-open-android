package com.spiros.plexopenandroid;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class PlexApiClient {
    interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    static final String PREFS = "plex-open-android";
    private static final String KEY_BASE_URL = "base_url";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final PersistentCookieJar cookieJar;
    private final OkHttpClient client;
    private final OkHttpClient downloadClient;
    private String baseUrl;

    PlexApiClient(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cookieJar = new PersistentCookieJar(prefs, gson);
        client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        downloadClient = client.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        baseUrl = prefs.getString(KEY_BASE_URL, "");
    }

    Gson gson() {
        return gson;
    }

    OkHttpClient httpClient() {
        return client;
    }

    String baseUrl() {
        return baseUrl;
    }

    boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isEmpty();
    }

    void setBaseUrl(String value) {
        baseUrl = normalizeBaseUrl(value);
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply();
    }

    void clearSession() {
        cookieJar.clear();
    }

    String absoluteUrl(String pathOrUrl) throws IOException {
        if (pathOrUrl == null || pathOrUrl.trim().isEmpty()) {
            throw new IOException("Missing URL");
        }
        String value = pathOrUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IOException("Server URL is not configured");
        }
        HttpUrl base = HttpUrl.get(baseUrl);
        HttpUrl resolved = base.resolve(value);
        if (resolved == null) {
            throw new IOException("Bad URL: " + value);
        }
        return resolved.toString();
    }

    String cookieHeaderFor(String pathOrUrl) throws IOException {
        HttpUrl url = HttpUrl.get(absoluteUrl(pathOrUrl));
        List<Cookie> cookies = cookieJar.loadForRequest(url);
        StringBuilder builder = new StringBuilder();
        for (Cookie cookie : cookies) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie.name()).append('=').append(cookie.value());
        }
        return builder.toString();
    }

    Request request(String pathOrUrl) throws IOException {
        return new Request.Builder()
                .url(absoluteUrl(pathOrUrl))
                .header("Accept", "application/json")
                .build();
    }

    <T> T get(String path, Class<T> type) throws IOException {
        Request request = request(path);
        try (Response response = client.newCall(request).execute()) {
            return parseResponse(response, type);
        }
    }

    <T> T post(String path, Object payload, Class<T> type) throws IOException {
        String json = gson.toJson(payload == null ? new JsonObject() : payload);
        Request request = new Request.Builder()
                .url(absoluteUrl(path))
                .header("Accept", "application/json")
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            return parseResponse(response, type);
        }
    }

    void downloadToFile(String pathOrUrl, File target, ProgressListener listener) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Request request = new Request.Builder()
                .url(absoluteUrl(pathOrUrl))
                .header("Accept", "*/*")
                .build();
        try (Response response = downloadClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw apiError(response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response");
            }
            long total = body.contentLength();
            long read = 0;
            byte[] buffer = new byte[128 * 1024];
            try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(target)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    read += count;
                    if (listener != null) {
                        listener.onProgress(read, total);
                    }
                }
            }
        }
    }

    private <T> T parseResponse(Response response, Class<T> type) throws IOException {
        if (!response.isSuccessful()) {
            throw apiError(response);
        }
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("Empty response");
        }
        String text = body.string();
        if (type == String.class) {
            return type.cast(text);
        }
        return gson.fromJson(text, type);
    }

    private IOException apiError(Response response) throws IOException {
        String message = response.code() + " " + response.message();
        ResponseBody body = response.body();
        if (body != null) {
            String text = body.string();
            if (text != null && !text.trim().isEmpty()) {
                try {
                    JsonObject object = gson.fromJson(text, JsonObject.class);
                    if (object != null) {
                        if (object.has("message") && !object.get("message").isJsonNull()) {
                            message = object.get("message").getAsString();
                        } else if (object.has("error") && !object.get("error").isJsonNull()) {
                            message = object.get("error").getAsString();
                        }
                    }
                } catch (RuntimeException ignored) {
                    message = text.length() > 240 ? text.substring(0, 240) : text;
                }
            }
        }
        return new IOException(message);
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        if (!value.endsWith("/")) {
            value += "/";
        }
        return value;
    }

    private static final class PersistentCookieJar implements CookieJar {
        private static final String KEY_COOKIES = "cookies";
        private final SharedPreferences prefs;
        private final Gson gson;
        private final List<StoredCookie> cookies = new ArrayList<>();

        PersistentCookieJar(SharedPreferences prefs, Gson gson) {
            this.prefs = prefs;
            this.gson = gson;
            load();
        }

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> responseCookies) {
            long now = System.currentTimeMillis();
            for (Cookie cookie : responseCookies) {
                remove(cookie.name(), cookie.domain(), cookie.path());
                if (cookie.expiresAt() > now) {
                    cookies.add(StoredCookie.from(cookie));
                }
            }
            pruneExpired(now);
            persist();
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            List<Cookie> result = new ArrayList<>();
            for (StoredCookie stored : cookies) {
                Cookie cookie = stored.toCookie();
                if (cookie != null && cookie.matches(url)) {
                    result.add(cookie);
                }
            }
            return result;
        }

        synchronized void clear() {
            cookies.clear();
            persist();
        }

        private void remove(String name, String domain, String path) {
            Iterator<StoredCookie> iterator = cookies.iterator();
            while (iterator.hasNext()) {
                StoredCookie existing = iterator.next();
                if (name.equals(existing.name) && domain.equals(existing.domain) && path.equals(existing.path)) {
                    iterator.remove();
                }
            }
        }

        private void pruneExpired(long now) {
            Iterator<StoredCookie> iterator = cookies.iterator();
            while (iterator.hasNext()) {
                StoredCookie stored = iterator.next();
                if (stored.expiresAt <= now) {
                    iterator.remove();
                }
            }
        }

        private void load() {
            String json = prefs.getString(KEY_COOKIES, "[]");
            try {
                StoredCookie[] stored = gson.fromJson(json, StoredCookie[].class);
                if (stored != null) {
                    for (StoredCookie cookie : stored) {
                        if (cookie != null) {
                            cookies.add(cookie);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                cookies.clear();
            }
        }

        private void persist() {
            prefs.edit().putString(KEY_COOKIES, gson.toJson(cookies)).apply();
        }
    }

    private static final class StoredCookie {
        String name;
        String value;
        long expiresAt;
        String domain;
        String path;
        boolean secure;
        boolean httpOnly;
        boolean hostOnly;

        static StoredCookie from(Cookie cookie) {
            StoredCookie stored = new StoredCookie();
            stored.name = cookie.name();
            stored.value = cookie.value();
            stored.expiresAt = cookie.expiresAt();
            stored.domain = cookie.domain().toLowerCase(Locale.US);
            stored.path = cookie.path();
            stored.secure = cookie.secure();
            stored.httpOnly = cookie.httpOnly();
            stored.hostOnly = cookie.hostOnly();
            return stored;
        }

        Cookie toCookie() {
            if (name == null || value == null || domain == null || path == null) {
                return null;
            }
            Cookie.Builder builder = new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .expiresAt(expiresAt)
                    .path(path);
            if (hostOnly) {
                builder.hostOnlyDomain(domain);
            } else {
                builder.domain(domain);
            }
            if (secure) {
                builder.secure();
            }
            if (httpOnly) {
                builder.httpOnly();
            }
            return builder.build();
        }
    }
}

