package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Mac-style home screen: wallpaper, menu bar, Dock and Launchpad. MainActivity stays the registered Home and
 * passes over to this when "mac_mode" is on (see Config); Home pressed again lands back here via onNewIntent.
 *
 * Kept light like the classic home: one wallpaper bitmap and one tiny blurred copy, icons drawn once, nothing
 * running while another app is in front.
 */
public class MacActivity extends Activity {

    private static final long MINUTE_MS = 60_000L;
    /** The app launched last from here, so the Dock can keep a dot under it. Lost with the process. */
    private static String lastLaunched;

    private Config config;
    private FrameLayout root;
    private ImageView wall;
    private MacMenuBar bar;
    private MacDock dock;
    private MacLaunchpad pad;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<MacApps.App> apps = Collections.emptyList();
    private final List<MacApps.App> dockApps = new ArrayList<>();
    private MacApps.App settingsApp;
    private boolean appsDirty = true;
    private boolean destroyed;
    private int generation;

    private List<File> wallpapers = Collections.emptyList();
    private long wallpaperIntervalMs;
    private Bitmap wallBitmap;

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            bar.refreshClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, MINUTE_MS - now % MINUTE_MS);
        }
    };
    private final Runnable wallTick = new Runnable() {
        @Override
        public void run() {
            if (wallpapers.size() >= 2) showNextWallpaper(true);
            if (wallpaperIntervalMs > 0) handler.postDelayed(this, wallpaperIntervalMs);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (hasWindowFocus()) loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = Config.load(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(config.backgroundColor);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setImageDrawable(fallbackWallpaper());
        root.addView(wall, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new MacMenuBar(this);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                MacMenuBar.heightPx(this), Gravity.TOP));
        bar.setListener(new MacMenuBar.Listener() {
            @Override
            public void onItem(int item) {
                openMenuItem(item);
            }

            @Override
            public void onDown() {
                dock.requestFocus();
            }
        });

        dock = new MacDock(this, config.reducedMotion);
        root.addView(dock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                MacDock.heightPx(this), Gravity.BOTTOM));
        dock.setListener(new MacDock.Listener() {
            @Override
            public void onOpen(int index) {
                openDockItem(index);
            }

            @Override
            public void onUp() {
                bar.requestFocus();
            }
        });

        pad = new MacLaunchpad(this, config.reducedMotion);
        root.addView(pad, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pad.setListener(new MacLaunchpad.Listener() {
            @Override
            public void onOpen(MacApps.App app) {
                launch(app, -1);
                pad.close(true);
            }

            @Override
            public void onClose() {
                closeLaunchpad();
            }
        });

        setContentView(root);

        wallpapers = config.places ? PlaceWallpapers.list(this) : config.wallpapers;
        if (wallpapers.isEmpty() && config.wallpaper != null) wallpapers = Collections.singletonList(config.wallpaper);
        wallpaperIntervalMs = config.wallpaperIntervalMs;
        if (!wallpapers.isEmpty()) showNextWallpaper(false);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bar.refreshClock();
        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, MINUTE_MS - System.currentTimeMillis() % MINUTE_MS);
        if (wallpapers.size() >= 2 && wallpaperIntervalMs > 0) {
            handler.removeCallbacks(wallTick);
            handler.postDelayed(wallTick, wallpaperIntervalMs);
        }
        if (appsDirty) loadApps();
        else dock.setRunning(runningIndex());
        if (!pad.isOpen()) dock.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(wallTick);
        dock.stopBounce();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed on the Home screen: Launchpad folds away and the Dock gets the focus back.
        if (pad.isOpen()) pad.close(true);
        dock.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (pad.isOpen()) closeLaunchpad();
        // Home screen: Back goes nowhere.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        worker.shutdownNow();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
    }

    // ---- apps ------------------------------------------------------------------------------------

    private void loadApps() {
        appsDirty = false;
        final int gen = ++generation;
        final Context ctx = getApplicationContext();
        final java.util.Set<String> hidden = new HashSet<>(config.hiddenApps);
        try {
            worker.execute(() -> {
                List<MacApps.App> all = MacApps.all(ctx, hidden);
                MacApps.App settings = MacApps.settings(ctx);
                handler.post(() -> {
                    if (destroyed || gen != generation) return;
                    apps = all;
                    settingsApp = settings;
                    applyApps();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /** Dock: Launchpad, the pinned apps in their order, then a divider and Settings. Launchpad: all the apps. */
    private void applyApps() {
        dockApps.clear();
        for (String pkg : config.dockApps) {
            for (MacApps.App a : apps) {
                if (a.pkg.equals(pkg)) {
                    dockApps.add(a);
                    break;
                }
            }
        }
        List<MacDock.Item> items = new ArrayList<>();
        items.add(new MacDock.Item(getString(R.string.mac_launchpad), MacApps.launchpadIcon(), false));
        for (MacApps.App a : dockApps) items.add(new MacDock.Item(a.label, a.icon, false));
        if (settingsApp != null) items.add(new MacDock.Item(settingsApp.label, settingsApp.icon, true));
        // Land on the first app, not on Launchpad, as a fresh Dock would.
        dock.setItems(items, dock.selectedIndex() > 0 ? dock.selectedIndex() : Math.min(1, items.size() - 1));
        dock.setRunning(runningIndex());
        pad.setApps(apps);
    }

    private int runningIndex() {
        if (lastLaunched == null) return -1;
        for (int i = 0; i < dockApps.size(); i++) if (dockApps.get(i).pkg.equals(lastLaunched)) return i + 1;
        return -1;
    }

    // ---- actions ---------------------------------------------------------------------------------

    private void openDockItem(int index) {
        if (index == 0) {
            openLaunchpad();
            return;
        }
        if (index - 1 < dockApps.size()) {
            launch(dockApps.get(index - 1), index);
        } else if (settingsApp != null) {
            launch(settingsApp, index);
        }
    }

    private void launch(MacApps.App app, int dockIndex) {
        if (dockIndex >= 0) dock.bounce(dockIndex);
        try {
            startActivity(app.launch);
            lastLaunched = app.pkg;
        } catch (ActivityNotFoundException | SecurityException e) {
            dock.stopBounce();
        }
    }

    private void openLaunchpad() {
        pad.open();
    }

    private void closeLaunchpad() {
        pad.close(true);
        dock.requestFocus();
    }

    private void openMenuItem(int item) {
        Intent i;
        switch (item) {
            case MacMenuBar.ITEM_INPUTS:
                i = new Intent("com.android.tv.action.VIEW_INPUTS");
                break;
            case MacMenuBar.ITEM_WIFI:
                i = new Intent(Settings.ACTION_WIFI_SETTINGS);
                break;
            default:
                i = new Intent(Settings.ACTION_SETTINGS);
                break;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    // ---- wallpaper -------------------------------------------------------------------------------

    /** A soft blue to violet to rose sunrise, for when there is no picture. */
    private Drawable fallbackWallpaper() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E2A78, 0xFF6B3FA0, 0xFFE0787A});
        return g;
    }

    /** Shows the next picture from the list; the position is shared with the classic home. */
    private void showNextWallpaper(boolean fade) {
        if (wallpapers.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("wallpaper", MODE_PRIVATE);
        int next = (prefs.getInt("index", -1) + 1) % wallpapers.size();
        if (fade) prefs.edit().putInt("index", next).apply();
        final File file = wallpapers.get(next);
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        try {
            worker.execute(() -> {
                Bitmap bmp = decode(file, dm);
                if (bmp == null) return;
                Bitmap blur = MacBlur.make(bmp, dm.widthPixels, dm.heightPixels);
                handler.post(() -> {
                    if (destroyed) return;
                    setWallpaper(bmp, blur, fade);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void setWallpaper(Bitmap bmp, Bitmap blur, boolean fade) {
        Bitmap old = wallBitmap;
        wallBitmap = bmp;
        if (fade && old != null && !config.reducedMotion) {
            TransitionDrawable t = new TransitionDrawable(new Drawable[]{
                    new BitmapDrawable(getResources(), old), new BitmapDrawable(getResources(), bmp)});
            t.setCrossFadeEnabled(true);
            wall.setImageDrawable(t);
            t.startTransition(900);
            handler.postDelayed(() -> {
                if (wallBitmap == bmp && !destroyed) wall.setImageBitmap(bmp);
            }, 1000);
        } else {
            wall.setImageBitmap(bmp);
        }
        bar.setBlur(blur);
        dock.setBlur(blur);
        pad.setBlur(blur);
    }

    private static Bitmap decode(File file, DisplayMetrics dm) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= dm.widthPixels && o.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
