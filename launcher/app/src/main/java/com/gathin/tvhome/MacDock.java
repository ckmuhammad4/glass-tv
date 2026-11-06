package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mac Dock: a frosted tray along the bottom edge with the apps in a row. The icon under the selection
 * swells and its neighbours swell a little less (the Mac's magnification wave), the wave follows the D-pad on a
 * spring, a name tag floats above the selected icon, and a launched icon hops up and down. One view draws it
 * all, so the wave costs a few bitmap draws per frame.
 */
final class MacDock extends View {

    interface Listener {
        void onOpen(int index);

        void onUp();
    }

    static final class Item {
        final String label;
        final Bitmap icon;
        final boolean separatorBefore;

        Item(String label, Bitmap icon, boolean separatorBefore) {
            this.label = label;
            this.icon = icon;
            this.separatorBefore = separatorBefore;
        }
    }

    private static final float BASE_DP = 60f, MAX_SCALE = 1.75f, GAP_DP = 8f, SPREAD = 2.4f;

    private final float d;
    private final boolean calm;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF tray = new RectF(), dst = new RectF(), tagRect = new RectF();
    private final Rect src = new Rect();

    private final List<Item> items = new ArrayList<>();
    private float[] bounce = new float[0];
    private ValueAnimator[] bouncing = new ValueAnimator[0];
    private int selected;
    private int running = -1;
    private Bitmap blur;
    private Listener listener;

    /** Where the wave is centred, in item numbers, and how much of it shows (0 off the Dock, 1 on it). */
    private float center, magnify;
    private final SpringAnimation centerSpring, magnifySpring;
    private boolean ready;

    MacDock(Context ctx, boolean reducedMotion) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        calm = reducedMotion;
        setFocusable(true);
        setClickable(true);
        tag.setTextSize(16 * d);
        tag.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tag.setTextAlign(Paint.Align.CENTER);

        FloatPropertyCompat<MacDock> centerProp = new FloatPropertyCompat<MacDock>("center") {
            @Override
            public float getValue(MacDock v) {
                return v.center;
            }

            @Override
            public void setValue(MacDock v, float value) {
                v.center = value;
                v.invalidate();
            }
        };
        FloatPropertyCompat<MacDock> magnifyProp = new FloatPropertyCompat<MacDock>("magnify") {
            @Override
            public float getValue(MacDock v) {
                return v.magnify;
            }

            @Override
            public void setValue(MacDock v, float value) {
                v.magnify = value;
                v.invalidate();
            }
        };
        centerSpring = new SpringAnimation(this, centerProp);
        centerSpring.setSpring(new SpringForce().setStiffness(calm ? 1500f : 420f)
                .setDampingRatio(calm ? 1f : 0.72f));
        centerSpring.setMinimumVisibleChange(0.002f);
        magnifySpring = new SpringAnimation(this, magnifyProp);
        magnifySpring.setSpring(new SpringForce().setStiffness(calm ? 1500f : 380f)
                .setDampingRatio(calm ? 1f : 0.8f));
        magnifySpring.setMinimumVisibleChange(0.002f);
    }

    /** The view's height: room for the biggest icon, its name tag and a hop. */
    static int heightPx(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round((BASE_DP * MAX_SCALE + 20 + 12 + 40 + BASE_DP * 0.6f) * d);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setBlur(Bitmap b) {
        blur = b;
        invalidate();
    }

    void setItems(List<Item> list, int keepSelected) {
        items.clear();
        items.addAll(list);
        bounce = new float[items.size()];
        for (ValueAnimator a : bouncing) if (a != null) a.cancel();
        bouncing = new ValueAnimator[items.size()];
        selected = Math.max(0, Math.min(keepSelected, items.size() - 1));
        center = selected;
        centerSpring.cancel();
        invalidate();
    }

    int selectedIndex() {
        return selected;
    }

    /** The app shown with a dot under it, as the Mac marks an open app. -1 for none. */
    void setRunning(int index) {
        running = index;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!ready) {
            // The first focus comes with the first frame: the wave grows in from nothing.
            ready = true;
        }
        magnifySpring.animateToFinalPosition(gainFocus ? 1f : 0f);
    }

    /** Hops the icon at {@code index}: a few decaying jumps, until {@link #stopBounce()} or they run out. */
    void bounce(int index) {
        if (index < 0 || index >= bounce.length) return;
        if (bouncing[index] != null) bouncing[index].cancel();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(calm ? 700 : 1500);
        a.setRepeatCount(calm ? 1 : 3);
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            // Two hops per round, each lower than the last.
            bounce[index] = (float) Math.abs(Math.sin(t * 2 * Math.PI)) * (1f - 0.45f * t);
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                bounce[index] = 0f;
                invalidate();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                bounce[index] = 0f;
            }
        });
        bouncing[index] = a;
        a.start();
    }

    void stopBounce() {
        for (ValueAnimator a : bouncing) if (a != null) a.cancel();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (listener != null) listener.onUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0 && listener != null && !items.isEmpty()) listener.onOpen(selected);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void move(int by) {
        int next = Math.max(0, Math.min(items.size() - 1, selected + by));
        if (next == selected) return;
        selected = next;
        centerSpring.animateToFinalPosition(selected);
        invalidate();
    }

    /** How much item {@code i} swells: a cosine hump around the wave's centre, 1 at the peak. */
    private float swell(int i) {
        float dist = Math.abs(i - center);
        if (dist >= SPREAD) return 0f;
        return (float) (0.5 * (1 + Math.cos(Math.PI * dist / SPREAD)));
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = items.size();
        if (n == 0) return;
        float w = getWidth(), h = getHeight();
        float base = BASE_DP * d, gap = GAP_DP * d, padV = 10 * d, padH = 14 * d, sep = 16 * d;
        float[] size = new float[n];
        float total = 0;
        for (int i = 0; i < n; i++) {
            size[i] = base * (1f + (MAX_SCALE - 1f) * swell(i) * magnify);
            total += size[i] + (i < n - 1 ? gap : 0) + (items.get(i).separatorBefore ? sep : 0);
        }
        float trayBottom = h - 14 * d;
        float trayTop = trayBottom - base - 2 * padV;
        float left = (w - total) / 2f;
        tray.set(left - padH, trayTop, left + total + padH, trayBottom);

        float radius = 24 * d;
        if (blur != null) MacBlur.draw(c, blur, this, tray, radius, blurPaint);
        shape.setStyle(Paint.Style.FILL);
        shape.setColor(blur != null ? 0x38FFFFFF : 0x40FFFFFF);
        c.drawRoundRect(tray, radius, radius, shape);
        shape.setStyle(Paint.Style.STROKE);
        shape.setStrokeWidth(1.2f * d);
        shape.setColor(0x66FFFFFF);
        RectF edge = new RectF(tray);
        edge.inset(0.6f * d, 0.6f * d);
        c.drawRoundRect(edge, radius, radius, shape);

        float baseline = trayBottom - padV;
        float x = left;
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            if (it.separatorBefore) {
                shape.setStyle(Paint.Style.FILL);
                shape.setColor(0x66FFFFFF);
                float sx = x - gap / 2f - sep / 2f;
                c.drawRect(sx - 0.6f * d, trayTop + padV, sx + 0.6f * d, trayBottom - padV, shape);
                x += sep;
            }
            float s = size[i];
            float lift = bounce[i] * base * 0.55f;
            dst.set(x, baseline - s - lift, x + s, baseline - lift);
            // A soft shadow under each icon, as the Mac gives them.
            if (it.icon != null) c.drawBitmap(it.icon, null, dst, paint);
            if (i == running) {
                shape.setStyle(Paint.Style.FILL);
                shape.setColor(0xCCFFFFFF);
                c.drawCircle(x + s / 2f, trayBottom - 5 * d, 2.2f * d, shape);
            }
            if (i == selected && magnify > 0.02f) drawTag(c, it.label, dst, w);
            x += s + gap;
        }
    }

    /** The dark name tag that rises above the selected icon. */
    private void drawTag(Canvas c, String label, RectF icon, float viewW) {
        float alpha = Math.max(0f, Math.min(1f, (magnify - 0.3f) / 0.7f));
        float tw = tag.measureText(label);
        float ph = 14 * d, pv = 7 * d;
        float cx = Math.max(tw / 2f + ph + 8 * d, Math.min(viewW - tw / 2f - ph - 8 * d, icon.centerX()));
        float bottom = icon.top - 10 * d;
        tagRect.set(cx - tw / 2f - ph, bottom - tag.getTextSize() - 2 * pv, cx + tw / 2f + ph, bottom);
        shape.setStyle(Paint.Style.FILL);
        shape.setColor(((int) (0xDD * alpha) << 24) | 0x2A2A2E);
        c.drawRoundRect(tagRect, 9 * d, 9 * d, shape);
        shape.setStyle(Paint.Style.STROKE);
        shape.setStrokeWidth(1f * d);
        shape.setColor(((int) (0x44 * alpha) << 24) | 0xFFFFFF);
        c.drawRoundRect(tagRect, 9 * d, 9 * d, shape);
        tag.setColor(((int) (0xFF * alpha) << 24) | 0xFFFFFF);
        c.drawText(label, cx, tagRect.centerY() - (tag.ascent() + tag.descent()) / 2f, tag);
    }
}
