package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * The frosted glass of the Mac look: a small, blurred copy of the wallpaper cut to the screen's shape, which the
 * menu bar, the Dock and Launchpad paint through their own outlines. One tiny bitmap, made once per picture.
 */
final class MacBlur {

    private MacBlur() {
    }

    /**
     * Blurs {@code src} cropped to the screen's aspect ratio (the way a centre-cropped wallpaper shows) into a
     * bitmap about 192 px wide. Call it off the main thread.
     */
    static Bitmap make(Bitmap src, int screenW, int screenH) {
        if (src == null || screenW <= 0 || screenH <= 0) return null;
        float screenAspect = (float) screenW / screenH;
        int sw = src.getWidth(), sh = src.getHeight();
        int cw = sw, ch = sh;
        if ((float) sw / sh > screenAspect) cw = Math.round(sh * screenAspect);
        else ch = Math.round(sw / screenAspect);
        int cx = (sw - cw) / 2, cy = (sh - ch) / 2;
        int w = 192, h = Math.max(1, Math.round(w / screenAspect));
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h), p);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(px, w, h, 5, true);
            boxPass(px, w, h, 5, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    /** One box blur pass along rows or columns, edges clamped. */
    private static void boxPass(int[] px, int w, int h, int radius, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[len];
        int window = radius * 2 + 1;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = horizontal ? px[l * w + i] : px[i * w + l];
            long r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int v = line[Math.max(0, Math.min(len - 1, k))];
                r += (v >> 16) & 0xFF;
                g += (v >> 8) & 0xFF;
                b += v & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                int out = 0xFF000000 | ((int) (r / window) << 16) | ((int) (g / window) << 8) | (int) (b / window);
                if (horizontal) px[l * w + i] = out;
                else px[i * w + l] = out;
                int add = line[Math.min(len - 1, i + radius + 1)], drop = line[Math.max(0, i - radius)];
                r += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                b += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }

    private static final Rect SRC = new Rect();

    /**
     * Paints the blurred wallpaper that lies behind {@code area} (in {@code view}'s coordinates) through a
     * rounded rectangle. {@code view} must fill the screen's width and sit at its own {@code getTop()}.
     */
    static void draw(Canvas c, Bitmap blur, View view, RectF area, float radius, Paint paint) {
        if (blur == null || view.getWidth() == 0) return;
        View root = (View) view.getParent();
        int rw = root != null ? root.getWidth() : view.getWidth();
        int rh = root != null ? root.getHeight() : view.getHeight();
        if (rw == 0 || rh == 0) return;
        float sx = (float) blur.getWidth() / rw, sy = (float) blur.getHeight() / rh;
        float top = view.getTop() + view.getTranslationY();
        SRC.set(Math.round(area.left * sx), Math.round((area.top + top) * sy),
                Math.round(area.right * sx), Math.round((area.bottom + top) * sy));
        SRC.intersect(0, 0, blur.getWidth(), blur.getHeight());
        if (SRC.isEmpty()) return;
        Path clip = new Path();
        clip.addRoundRect(area, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawBitmap(blur, SRC, area, paint);
        c.restore();
    }
}
