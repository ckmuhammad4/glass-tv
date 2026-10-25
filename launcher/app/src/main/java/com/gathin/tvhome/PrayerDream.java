package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: one of this week's place pictures dimmed behind a large digital clock, today's five
 * prayer times with the next one counting down, and a banner for ten minutes from each prayer time.
 * Times come from {@link PrayerTimes}; no network. The text drifts a little each minute so nothing
 * burns in. Pick it with `settings put secure screensaver_components com.gathin.tvhome/.PrayerDream`.
 */
public class PrayerDream extends DreamService {

    private static final long TICK_MS = 15_000L;
    private static final long DRIFT_MS = 60_000L;
    private static final long BANNER_MS = 10 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout root;
    private ImageView picture;
    private LinearLayout column;
    private TextView banner, next;
    private final TextView[] names = new TextView[5];
    private final TextView[] times = new TextView[5];
    private Bitmap bitmap;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            float dx = root.getWidth() * 0.02f * (2f * random.nextFloat() - 1f);
            float dy = root.getHeight() * 0.02f * (2f * random.nextFloat() - 1f);
            column.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                column.setTranslationX(dx);
                column.setTranslationY(dy);
                column.animate().alpha(1f).setDuration(1200).start();
            }).start();
            handler.postDelayed(this, DRIFT_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);

        float u = getResources().getDisplayMetrics().heightPixels / 380f;   // one design unit
        Typeface font = getResources().getFont(R.font.outfit);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF111113);
        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor(0xA0000000);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        style(time, font, 74 * u, 0xFFFFFFFF);
        column.addView(time);

        TextClock date = new TextClock(this);
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMdyyyy");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        style(date, font, 15 * u, 0xFFD0D0D4);
        date.setAllCaps(true);
        date.setLetterSpacing(0.12f);
        column.addView(date);

        next = new TextView(this);
        style(next, font, 17 * u, 0xFFFFFFFF);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = Math.round(22 * u);
        column.addView(next, nl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = Math.round(14 * u);
        column.addView(row, rl);
        for (int i = 0; i < 5; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = Math.round(14 * u);
            cell.setPadding(pad, Math.round(4 * u), pad, Math.round(4 * u));
            names[i] = new TextView(this);
            style(names[i], font, 11 * u, 0xFFB8B8BE);
            names[i].setAllCaps(true);
            names[i].setLetterSpacing(0.12f);
            times[i] = new TextView(this);
            style(times[i], font, 20 * u, 0xFFFFFFFF);
            cell.addView(names[i]);
            cell.addView(times[i]);
            row.addView(cell);
        }

        banner = new TextView(this);
        style(banner, font, 22 * u, 0xFFFFFFFF);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(0x66000000);
        int bp = Math.round(10 * u);
        banner.setPadding(bp * 3, bp, bp * 3, bp);
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bl.topMargin = Math.round(28 * u);
        root.addView(banner, bl);

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
    }

    private static void style(TextView v, Typeface font, float px, int color) {
        v.setTypeface(font);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setShadowLayer(px * 0.12f, 0, px * 0.04f, 0x99000000);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        loadPicture();
        column.setAlpha(0f);
        column.animate().alpha(1f).setDuration(1200).start();
        refresh();
        handler.postDelayed(tick, TICK_MS);
        handler.postDelayed(drift, DRIFT_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(drift);
        picture.setImageDrawable(null);
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
        super.onDreamingStopped();
    }

    private void loadPicture() {
        List<File> files = PlaceWallpapers.list(this);
        if (files.isEmpty()) return;
        File f = files.get(random.nextInt(files.size()));
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = Math.max(1, o.outWidth / 1280);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeFile(f.getPath(), o);
            if (bitmap != null) picture.setImageBitmap(bitmap);
        } catch (Throwable ignored) {
            // A missing or broken picture just leaves the dark background.
        }
    }

    private void refresh() {
        Calendar now = Calendar.getInstance(PrayerTimes.ZONE);
        long t = now.getTimeInMillis();
        long[] today = PrayerTimes.forDay(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH));
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", Locale.getDefault());
        fmt.setTimeZone(PrayerTimes.ZONE);

        int nextIndex = -1;
        long nextAt = 0;
        for (int i = 0; i < 5; i++) {
            if (today[i] > t) {
                nextIndex = i;
                nextAt = today[i];
                break;
            }
        }
        if (nextIndex < 0) {   // after Isha: tomorrow's Fajr
            Calendar tomorrow = (Calendar) now.clone();
            tomorrow.add(Calendar.DAY_OF_MONTH, 1);
            nextIndex = 0;
            nextAt = PrayerTimes.forDay(tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1,
                    tomorrow.get(Calendar.DAY_OF_MONTH))[0];
        }
        for (int i = 0; i < 5; i++) {
            boolean isNext = i == nextIndex;
            names[i].setText(PrayerTimes.NAMES[i]);
            times[i].setText(fmt.format(today[i]));
            times[i].setTextColor(isNext ? 0xFFFFFFFF : 0xB3FFFFFF);
            names[i].setTextColor(isNext ? 0xFFFFFFFF : 0xFFB8B8BE);
            ((View) times[i].getParent()).setBackgroundColor(isNext ? 0x40FFFFFF : Color.TRANSPARENT);
        }
        long mins = Math.max(0, (nextAt - t + 59_999) / 60_000);
        next.setText(String.format(Locale.getDefault(), "%s in %dh %02dm", PrayerTimes.NAMES[nextIndex],
                mins / 60, mins % 60));

        String due = null;
        for (int i = 0; i < 5; i++) {
            if (t >= today[i] && t < today[i] + BANNER_MS) due = PrayerTimes.NAMES[i];
        }
        if (due != null) {
            banner.setText("It's time for " + due);
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
    }
}
