package com.spiros.plexopenandroid;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {
    interface Listener {
        void onItemSelected(Models.MediaItem item);
    }

    private final ImageLoader imageLoader;
    private final Listener listener;
    private final List<Models.MediaItem> items = new ArrayList<>();

    MediaAdapter(ImageLoader imageLoader, Listener listener) {
        this.imageLoader = imageLoader;
        this.listener = listener;
        setHasStableIds(true);
    }

    void submit(List<Models.MediaItem> next) {
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        notifyDataSetChanged();
    }

    Models.MediaItem itemAt(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        String key = items.get(position).ratingKey;
        return key == null ? position : key.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int pad = dp(parent, 8);
        LinearLayout root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(true);
        root.setFocusable(true);

        FrameLayout posterFrame = new FrameLayout(parent.getContext());
        posterFrame.setBackgroundColor(Color.rgb(234, 232, 224));
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        posterParams.bottomMargin = dp(parent, 8);
        posterFrame.setLayoutParams(posterParams);

        ImageView poster = new ImageView(parent.getContext());
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterFrame.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView fallback = new TextView(parent.getContext());
        fallback.setGravity(Gravity.CENTER);
        fallback.setTextColor(Color.rgb(80, 78, 72));
        fallback.setTextSize(32);
        fallback.setTypeface(Typeface.DEFAULT_BOLD);
        posterFrame.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar progress = new ProgressBar(parent.getContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(229, 160, 13)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(190, 30, 30, 30)));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 5), Gravity.BOTTOM);
        progressParams.setMargins(dp(parent, 8), 0, dp(parent, 8), dp(parent, 8));
        posterFrame.addView(progress, progressParams);

        TextView title = new TextView(parent.getContext());
        title.setTextColor(Color.rgb(20, 20, 20));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);

        TextView meta = new TextView(parent.getContext());
        meta.setTextColor(Color.rgb(94, 93, 88));
        meta.setTextSize(12);
        meta.setMaxLines(1);

        root.addView(posterFrame);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(root, posterFrame, poster, fallback, progress, title, meta);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Models.MediaItem item = items.get(position);
        holder.title.setText(item.cardTitle());
        holder.meta.setText(item.metaLine());
        int progress = item.progressPercent();
        holder.progress.setProgress(progress);
        holder.progress.setVisibility(progress > 0 ? View.VISIBLE : View.GONE);
        String fallback = item.title == null || item.title.trim().isEmpty() ? "?" : item.title.trim().substring(0, 1).toUpperCase();
        holder.fallback.setText(fallback);
        holder.poster.setImageDrawable(null);
        if (item.posterUrl != null && !item.posterUrl.isEmpty()) {
            holder.fallback.setVisibility(View.GONE);
            imageLoader.load(item.posterUrl, holder.poster);
        } else {
            holder.fallback.setVisibility(View.VISIBLE);
        }
        holder.root.setOnClickListener(view -> listener.onItemSelected(item));
        ViewGroup.LayoutParams params = holder.posterFrame.getLayoutParams();
        params.height = Math.max(dp(holder.root, 190), holder.root.getMeasuredWidth() * 3 / 2);
        holder.posterFrame.setLayoutParams(params);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final FrameLayout posterFrame;
        final ImageView poster;
        final TextView fallback;
        final ProgressBar progress;
        final TextView title;
        final TextView meta;

        Holder(LinearLayout root, FrameLayout posterFrame, ImageView poster, TextView fallback, ProgressBar progress, TextView title, TextView meta) {
            super(root);
            this.root = root;
            this.posterFrame = posterFrame;
            this.poster = poster;
            this.fallback = fallback;
            this.progress = progress;
            this.title = title;
            this.meta = meta;
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static int dp(ViewGroup view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
