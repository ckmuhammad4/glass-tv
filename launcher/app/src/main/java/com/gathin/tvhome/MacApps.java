package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The apps the Mac-style home shows: the launchable apps, each with its icon already drawn into a rounded
 * "squircle" bitmap, so the Dock and Launchpad only blit it. Built off the main thread.
 */
final class MacApps {

    /** Side of the pre-drawn icons in pixels; the Dock's biggest magnified icon stays below this on a 1080p TV. */
    static final int ICON_PX = 192;

    static final class App {
        final String pkg;
        final String label;
        final Intent launch;
        final Bitmap icon;

        App(String pkg, String label, Intent launch, Bitmap icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.icon = icon;
        }
    }

    private MacApps() {
    }

    /** Every launchable app except this one and the hidden ones, sorted by name. */
    static List<App> all(Context ctx, Set<String> hidden) {
        PackageManager pm = ctx.getPackageManager();
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                CharSequence label = ri.loadLabel(pm);
                Drawable icon = ri.loadIcon(pm);
                out.add(new App(pkg, label != null ? label.toString() : pkg, launch, squircle(icon)));
            }
        }
        Collections.sort(out, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** The system Settings app as an item, named like the Mac's. Null when the TV has none. */
    static App settings(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo ri = pm.resolveActivity(i, 0);
        if (ri == null || ri.activityInfo == null) return null;
        return new App(ri.activityInfo.packageName, ctx.getString(R.string.mac_system_settings), i,
                squircle(ri.loadIcon(pm)));
    }

    /** Launchpad's own icon: a dark squircle with a grid of coloured tiles. */
    static Bitmap launchpadIcon() {
        Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = ICON_PX * 0.225f;
        p.setShader(new LinearGradient(0, 0, 0, ICON_PX, 0xFFE9E9EE, 0xFFA7A9B3, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, ICON_PX, ICON_PX), r, r, p);
        p.setShader(null);
        int[] colours = {0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840, 0xFF2F9CF4, 0xFFAF52DE, 0xFFFF9F0A,
                0xFF5E5CE6, 0xFF34C759, 0xFFFF375F};
        float cell = ICON_PX * 0.2f, gap = ICON_PX * 0.07f, total = cell * 3 + gap * 2;
        float x0 = (ICON_PX - total) / 2f;
        for (int i = 0; i < 9; i++) {
            float x = x0 + (i % 3) * (cell + gap), y = x0 + (i / 3) * (cell + gap);
            p.setColor(colours[i]);
            c.drawRoundRect(new RectF(x, y, x + cell, y + cell), cell * 0.28f, cell * 0.28f, p);
        }
        return bmp;
    }

    /** Draws any icon into a rounded square on a dark backing, filling it. */
    static Bitmap squircle(Drawable d) {
        Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float r = ICON_PX * 0.225f;
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, ICON_PX, ICON_PX), r, r, Path.Direction.CW);
        c.clipPath(clip);
        Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        back.setColor(0xFF2C2C2E);
        c.drawRect(0, 0, ICON_PX, ICON_PX, back);
        if (d != null) {
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                d.setBounds(0, 0, ICON_PX, ICON_PX);
            } else {
                // Fill the square, cropping the long side (TV apps often hand over a wide banner).
                float scale = Math.max((float) ICON_PX / w, (float) ICON_PX / h);
                int dw = Math.round(w * scale), dh = Math.round(h * scale);
                int l = (ICON_PX - dw) / 2, t = (ICON_PX - dh) / 2;
                d.setBounds(l, t, l + dw, t + dh);
            }
            d.draw(c);
        }
        return bmp;
    }
}
