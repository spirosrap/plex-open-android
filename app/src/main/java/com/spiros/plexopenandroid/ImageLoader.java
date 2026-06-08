package com.spiros.plexopenandroid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class ImageLoader {
    private final PlexApiClient api;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());

    ImageLoader(PlexApiClient api) {
        this.api = api;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L);
        cache = new LruCache<String, Bitmap>(Math.max(8 * 1024, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void load(String pathOrUrl, ImageView target) {
        target.setImageDrawable(null);
        if (pathOrUrl == null || pathOrUrl.isEmpty()) {
            return;
        }
        final String key;
        try {
            key = api.absoluteUrl(pathOrUrl);
        } catch (IOException ignored) {
            return;
        }
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            try {
                Request request = new Request.Builder().url(key).header("Accept", "image/*").build();
                try (Response response = api.httpClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        return;
                    }
                    Bitmap bitmap = BitmapFactory.decodeStream(body.byteStream());
                    if (bitmap == null) {
                        return;
                    }
                    cache.put(key, bitmap);
                    main.post(() -> {
                        Object tag = target.getTag();
                        if (key.equals(tag)) {
                            target.setImageBitmap(bitmap);
                        }
                    });
                }
            } catch (IOException ignored) {
                // Poster failures should not block browsing.
            }
        });
    }

    void shutdown() {
        io.shutdownNow();
    }
}

