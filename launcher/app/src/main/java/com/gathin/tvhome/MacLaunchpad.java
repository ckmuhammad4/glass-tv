package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * Launchpad: every app on a frosted full-screen sheet, in pages of icons with dots below. It zooms in from
 * slightly large as it fades up, the D-pad moves a soft highlight over the icons, and moving past the last
 * column slides to the next page on a spring.
 */
final class MacLaunchpad extends View {

    interface Listener {
        void onOpen(MacApps.App app);

        void onClose();
    }

    private static final int COLS = 7, ROWS = 4;

    private final float d;
    private final boolean calm;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF(), full = new RectF();

    private final List<MacApps.App> apps = new ArrayList<>();
    private Bitmap blur;
    private Listener listener;
    private int selected;
    /** Which page is at the screen centre, in pages: it chases the selected page on a spring. */
    private float pageOffset;
    /** 0 hidden, 1 fully shown. */
    private float appear;
    private ValueAnimator appearAnim;
    private final SpringAnimation pageSpring;

    MacLaunchpad(Context ctx, boolean reducedMotion) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        calm = reducedMotion;
        setFocusable(true);
        setClickable(true);
        setVisibility(GONE);
        label.setColor(0xFFFFFFFF);
        label.setTextSize(15 * d);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        label.setTextAlign(Paint.Align.LEFT);
        label.setShadowLayer(3 * d, 0, 1 * d, 0x88000000);
        FloatPropertyCompat<MacLaunchpad> prop = new FloatPropertyCompat<MacLaunchpad>("page") {
            @Override
            public float getValue(MacLaunchpad v) {
                return v.pageOffset;
            }

            @Override
            public void setValue(MacLaunchpad v, float value) {
                v.pageOffset = value;
                v.invalidate();
            }
        };
        pageSpring = new SpringAnimation(this, prop);
        pageSpring.setSpring(new SpringForce().setStiffness(calm ? 1500f : 260f).setDampingRatio(calm ? 1f : 0.85f));
        pageSpring.setMinimumVisibleChange(0.002f);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setBlur(Bitmap b) {
        blur = b;
        invalidate();
    }

    void setApps(List<MacApps.App> list) {
        apps.clear();
        apps.addAll(list);
        selected = Math.max(0, Math.min(selected, apps.size() - 1));
        invalidate();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE && appear > 0f && (appearAnim == null || target == 1f);
    }

    private float target;

    void open() {
        if (apps.isEmpty()) return;
        selected = 0;
        pageSpring.cancel();
        pageOffset = 0f;
        setVisibility(VISIBLE);
        requestFocus();
        animateTo(1f, calm ? 120 : 320);
    }

    void close(boolean animated) {
        if (getVisibility() != VISIBLE) return;
        if (!animated) {
            if (appearAnim != null) appearAnim.cancel();
            target = 0f;
            appear = 0f;
            setVisibility(GONE);
            return;
        }
        animateTo(0f, calm ? 100 : 220);
    }

    private void animateTo(float to, long ms) {
        if (appearAnim != null) appearAnim.cancel();
        target = to;
        appearAnim = ValueAnimator.ofFloat(appear, to);
        appearAnim.setDuration(ms);
        appearAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        appearAnim.addUpdateListener(v -> {
            appear = (float) v.getAnimatedValue();
            invalidate();
        });
        appearAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && to == 0f) setVisibility(GONE);
            }
        });
        appearAnim.start();
    }

    private int pages() {
        return Math.max(1, (apps.size() + COLS * ROWS - 1) / (COLS * ROWS));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int n = apps.size();
        int per = COLS * ROWS;
        int page = selected / per, idx = selected % per, row = idx / COLS, col = idx % COLS;
        int next = selected;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < COLS - 1 && selected + 1 < n) next = selected + 1;
                else if ((page + 1) * per < n) next = Math.min(n - 1, (page + 1) * per + row * COLS);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) next = selected - 1;
                else if (page > 0) next = (page - 1) * per + row * COLS + COLS - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < ROWS - 1 && selected + COLS < n) next = selected + COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row > 0) next = selected - COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0 && n > 0 && listener != null) listener.onOpen(apps.get(selected));
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (listener != null) listener.onClose();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
        if (next != selected) {
            selected = next;
            pageSpring.animateToFinalPosition(selected / per);
            invalidate();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (appear <= 0f) return;
        float w = getWidth(), h = getHeight();
        full.set(0, 0, w, h);
        c.save();
        // The sheet fades up while the icons zoom down to their place.
        float zoom = 1f + 0.08f * (1f - appear);
        c.scale(zoom, zoom, w / 2f, h / 2f);
        paint.setAlpha((int) (255 * appear));
        if (blur != null) {
            c.drawBitmap(blur, null, full, paint);
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(((int) (0x66 * appear) << 24) | 0x101018);
        } else {
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(((int) (0xDD * appear) << 24) | 0x101018);
        }
        c.drawRect(-w, -h, 2 * w, 2 * h, shape);
        paint.setAlpha(255);

        int per = COLS * ROWS;
        float marginX = 110 * d, top = 56 * d, bottom = 70 * d;
        float cellW = (w - 2 * marginX) / COLS, cellH = (h - top - bottom) / ROWS;
        float icon = Math.min(cellW * 0.56f, cellH * 0.56f);
        int first = (int) Math.floor(pageOffset), last = first + 1;
        for (int page = Math.max(0, first); page <= Math.min(pages() - 1, last); page++) {
            float dx = (page - pageOffset) * w;
            float pageAlpha = Math.max(0f, 1f - Math.abs(page - pageOffset));
            for (int k = 0; k < per; k++) {
                int i = page * per + k;
                if (i >= apps.size()) break;
                float cx = marginX + (k % COLS + 0.5f) * cellW + dx;
                float cy = top + (k / COLS + 0.5f) * cellH - 12 * d;
                boolean sel = i == selected;
                drawCell(c, apps.get(i), cx, cy, icon, cellW, sel, appear * pageAlpha);
            }
        }
        drawDots(c, w, h);
        c.restore();
    }

    private void drawCell(Canvas c, MacApps.App app, float cx, float cy, float icon, float cellW,
                          boolean sel, float alpha) {
        float s = sel ? 1.14f : 1f;
        float half = icon * s / 2f;
        if (sel) {
            // The soft plate behind the selected icon.
            float plate = icon * 0.78f * s;
            dst.set(cx - plate, cy - plate * 0.92f, cx + plate, cy + plate * 1.18f);
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(((int) (0x3A * alpha) << 24) | 0xFFFFFF);
            c.drawRoundRect(dst, 22 * d, 22 * d, shape);
        }
        dst.set(cx - half, cy - half, cx + half, cy + half);
        paint.setAlpha((int) (255 * alpha));
        c.drawBitmap(app.icon, null, dst, paint);
        paint.setAlpha(255);
        label.setAlpha((int) (255 * alpha));
        float maxW = cellW - 12 * d;
        CharSequence text = TextUtils.ellipsize(app.label, label, maxW, TextUtils.TruncateAt.END);
        float tw = label.measureText(text, 0, text.length());
        c.drawText(text, 0, text.length(), cx - tw / 2f, cy + half + 24 * d * s, label);
    }

    private void drawDots(Canvas c, float w, float h) {
        int n = pages();
        if (n < 2) return;
        float r = 4.5f * d, gap = 16 * d;
        float x = (w - (n - 1) * gap) / 2f, y = h - 34 * d;
        shape.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float near = Math.max(0f, 1f - Math.abs(i - pageOffset));
            int a = (int) ((0x55 + 0xAA * near) * appear);
            shape.setColor((a << 24) | 0xFFFFFF);
            c.drawCircle(x + i * gap, y, r, shape);
        }
    }
}
