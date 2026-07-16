package com.spiros.plexopenandroid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class ImageLoader {
    private final PlexApiClient api;
    private final LruCache<String, Bitmap> cache;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>> pending = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newFixedThreadPool(6);
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
        clear(target);
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
            target.setAlpha(1f);
            return;
        }
        target.setAlpha(0f);
        CopyOnWriteArrayList<WeakReference<ImageView>> waiters = new CopyOnWriteArrayList<>();
        waiters.add(new WeakReference<>(target));
        List<WeakReference<ImageView>> existing = pending.putIfAbsent(key, waiters);
        if (existing != null) {
            existing.add(new WeakReference<>(target));
            return;
        }
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                Request request = new Request.Builder().url(key).header("Accept", "image/*").build();
                try (Response response = api.httpClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) return;
                    ResponseBody body = response.body();
                    if (body == null || body.contentLength() > 12L * 1024L * 1024L) return;
                    bitmap = BitmapFactory.decodeStream(body.byteStream());
                }
            } catch (IOException ignored) {
                // Poster failures should not block browsing.
            } finally {
                if (bitmap != null) {
                    cache.put(key, bitmap);
                }
                Bitmap result = bitmap;
                List<WeakReference<ImageView>> targets = pending.remove(key);
                if (targets != null) {
                    main.post(() -> {
                        for (WeakReference<ImageView> reference : targets) {
                            ImageView view = reference.get();
                            if (view == null || !key.equals(view.getTag())) continue;
                            if (result != null) {
                                view.setImageBitmap(result);
                                view.animate().alpha(1f).setDuration(120L).start();
                            }
                        }
                    });
                }
            }
        });
    }

    void clear(ImageView target) {
        target.animate().cancel();
        target.setTag(null);
        target.setImageDrawable(null);
        target.setAlpha(1f);
    }

    void shutdown() {
        pending.clear();
        io.shutdownNow();
    }
}
