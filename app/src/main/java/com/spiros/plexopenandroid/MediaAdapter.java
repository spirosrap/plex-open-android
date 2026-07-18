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
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {
    interface Listener {
        void onItemSelected(Models.MediaItem item);
        void onCollectionActions(View anchor, Models.MediaItem item);
    }

    private final ImageLoader imageLoader;
    private final Listener listener;
    private final ThemePalette palette;
    private final AsyncListDiffer<Row> differ;

    MediaAdapter(ImageLoader imageLoader, Listener listener, ThemePalette palette) {
        this.imageLoader = imageLoader;
        this.listener = listener;
        this.palette = palette;
        differ = new AsyncListDiffer<>(this, new DiffUtil.ItemCallback<Row>() {
            @Override
            public boolean areItemsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
                return Objects.equals(oldItem.identity, newItem.identity);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
                return Objects.equals(oldItem.signature, newItem.signature);
            }
        });
        setHasStableIds(true);
    }

    void submit(List<Models.MediaItem> next) {
        List<Row> rows = new ArrayList<>();
        if (next != null) {
            for (Models.MediaItem item : next) {
                rows.add(new Row(item));
            }
        }
        differ.submitList(rows);
    }

    Models.MediaItem itemAt(int position) {
        return differ.getCurrentList().get(position).item;
    }

    @Override
    public long getItemId(int position) {
        String key = differ.getCurrentList().get(position).identity;
        if (key == null) return position;
        long hash = 1125899906842597L;
        for (int index = 0; index < key.length(); index++) {
            hash = 31L * hash + key.charAt(index);
        }
        return hash;
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

        FrameLayout posterFrame = new PosterFrame(parent.getContext());
        posterFrame.setBackgroundColor(palette.poster);
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        posterParams.bottomMargin = dp(parent, 8);
        posterFrame.setLayoutParams(posterParams);

        TextView fallback = new TextView(parent.getContext());
        fallback.setGravity(Gravity.CENTER);
        fallback.setTextColor(palette.posterText);
        fallback.setTextSize(32);
        fallback.setTypeface(Typeface.DEFAULT_BOLD);
        posterFrame.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView poster = new ImageView(parent.getContext());
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterFrame.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView collectionBadge = new TextView(parent.getContext());
        collectionBadge.setText("Collection");
        collectionBadge.setTextColor(palette.onAccent);
        collectionBadge.setTextSize(10);
        collectionBadge.setTypeface(Typeface.DEFAULT_BOLD);
        collectionBadge.setBackgroundColor(palette.accent);
        collectionBadge.setPadding(dp(parent, 6), dp(parent, 3), dp(parent, 6), dp(parent, 3));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        badgeParams.setMargins(dp(parent, 8), dp(parent, 8), 0, 0);
        posterFrame.addView(collectionBadge, badgeParams);

        TextView myListBadge = new TextView(parent.getContext());
        myListBadge.setText("My List");
        myListBadge.setTextColor(palette.onAccent);
        myListBadge.setTextSize(10);
        myListBadge.setTypeface(Typeface.DEFAULT_BOLD);
        myListBadge.setBackgroundColor(palette.accent);
        myListBadge.setPadding(dp(parent, 6), dp(parent, 3), dp(parent, 6), dp(parent, 3));
        FrameLayout.LayoutParams myListBadgeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        myListBadgeParams.setMargins(0, dp(parent, 8), dp(parent, 8), 0);
        posterFrame.addView(myListBadge, myListBadgeParams);

        TextView collectionMenu = new TextView(parent.getContext());
        collectionMenu.setText("...");
        collectionMenu.setGravity(Gravity.CENTER);
        collectionMenu.setTextColor(palette.ink);
        collectionMenu.setTextSize(18);
        collectionMenu.setTypeface(Typeface.DEFAULT_BOLD);
        collectionMenu.setBackgroundColor(palette.surface);
        collectionMenu.setClickable(true);
        collectionMenu.setFocusable(true);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(parent, 42), dp(parent, 38), Gravity.TOP | Gravity.END);
        menuParams.setMargins(0, dp(parent, 8), dp(parent, 8), 0);
        posterFrame.addView(collectionMenu, menuParams);

        ProgressBar progress = new ProgressBar(parent.getContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(palette.accent));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(palette.progressTrack));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 5), Gravity.BOTTOM);
        progressParams.setMargins(dp(parent, 8), 0, dp(parent, 8), dp(parent, 8));
        posterFrame.addView(progress, progressParams);

        TextView title = new TextView(parent.getContext());
        title.setTextColor(palette.ink);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);

        TextView meta = new TextView(parent.getContext());
        meta.setTextColor(palette.muted);
        meta.setTextSize(12);
        meta.setMaxLines(1);

        root.addView(posterFrame);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(root, posterFrame, poster, fallback, collectionBadge, myListBadge, collectionMenu, progress, title, meta);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Models.MediaItem item = differ.getCurrentList().get(position).item;
        holder.title.setText(item.cardTitle());
        holder.meta.setText(item.metaLine());
        int progress = item.progressPercent();
        holder.progress.setProgress(progress);
        holder.progress.setVisibility(progress > 0 ? View.VISIBLE : View.GONE);
        boolean collection = "collection".equals(item.type);
        holder.collectionBadge.setVisibility(collection ? View.VISIBLE : View.GONE);
        if (collection) {
            holder.collectionBadge.bringToFront();
        }
        holder.myListBadge.setVisibility(item.inMyList ? View.VISIBLE : View.GONE);
        if (item.inMyList) {
            holder.myListBadge.bringToFront();
        }
        boolean collectionActions = collection && !item.smart;
        holder.collectionMenu.setVisibility(collectionActions ? View.VISIBLE : View.GONE);
        holder.collectionMenu.setContentDescription("Actions for " + item.displayTitle());
        holder.collectionMenu.setOnClickListener(collectionActions ? view -> {
            int selected = holder.getBindingAdapterPosition();
            if (selected != RecyclerView.NO_POSITION) {
                listener.onCollectionActions(view, differ.getCurrentList().get(selected).item);
            }
        } : null);
        if (collectionActions) {
            holder.collectionMenu.bringToFront();
        }
        String fallback = item.title == null || item.title.trim().isEmpty() ? "?" : item.title.trim().substring(0, 1).toUpperCase();
        holder.fallback.setText(fallback);
        imageLoader.clear(holder.poster);
        holder.fallback.setVisibility(View.VISIBLE);
        if (item.posterUrl != null && !item.posterUrl.isEmpty()) {
            imageLoader.load(item.posterUrl, holder.poster);
        }
        holder.root.setOnClickListener(view -> {
            int selected = holder.getBindingAdapterPosition();
            if (selected != RecyclerView.NO_POSITION) {
                listener.onItemSelected(differ.getCurrentList().get(selected).item);
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        imageLoader.clear(holder.poster);
        holder.root.setOnClickListener(null);
        holder.collectionMenu.setOnClickListener(null);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    private static final class PosterFrame extends FrameLayout {
        PosterFrame(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = Math.max(1, width * 3 / 2);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    private static final class Row {
        final Models.MediaItem item;
        final String identity;
        final String signature;

        Row(Models.MediaItem item) {
            this.item = item;
            identity = item.ratingKey != null
                    ? "rating:" + item.ratingKey
                    : "fallback:" + Objects.toString(item.key, "") + ":" + Objects.toString(item.type, "")
                    + ":" + Objects.toString(item.title, "");
            signature = item.cardTitle() + "\n"
                    + item.metaLine() + "\n"
                    + Objects.toString(item.posterUrl, "") + "\n"
                    + Objects.toString(item.type, "") + "\n"
                    + item.smart + "\n"
                    + item.progressPercent() + "\n"
                    + item.inMyList;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final FrameLayout posterFrame;
        final ImageView poster;
        final TextView fallback;
        final TextView collectionBadge;
        final TextView myListBadge;
        final TextView collectionMenu;
        final ProgressBar progress;
        final TextView title;
        final TextView meta;

        Holder(LinearLayout root, FrameLayout posterFrame, ImageView poster, TextView fallback, TextView collectionBadge, TextView myListBadge, TextView collectionMenu, ProgressBar progress, TextView title, TextView meta) {
            super(root);
            this.root = root;
            this.posterFrame = posterFrame;
            this.poster = poster;
            this.fallback = fallback;
            this.collectionBadge = collectionBadge;
            this.myListBadge = myListBadge;
            this.collectionMenu = collectionMenu;
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
