package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;

import java.util.Date;
import java.util.Locale;

/**
 * The Mac menu bar across the top: the apple, the app name in bold, the menu titles, and on the right the
 * status icons and the date and time. The icons are focusable with the D-pad (a soft rounded highlight, as the
 * Mac shows under a pressed item); everything else is just drawn.
 */
final class MacMenuBar extends View {

    static final int ITEM_INPUTS = 0, ITEM_WIFI = 1, ITEM_SETTINGS = 2;

    interface Listener {
        void onItem(int item);

        void onDown();
    }

    private static final String[] MENUS = {"File", "Edit", "View", "Go", "Window", "Help"};

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Drawable[] icons = new Drawable[3];
    private final RectF[] itemRect = {new RectF(), new RectF(), new RectF()};
    private final RectF bar = new RectF(), tmp = new RectF();
    private final Path apple = new Path();
    private Bitmap blur;
    private int selected = ITEM_SETTINGS;
    private float highlight;      // 0 when the bar does not have focus, 1 when it does
    private String when = "";
    private Listener listener;

    MacMenuBar(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        icons[ITEM_INPUTS] = ctx.getDrawable(R.drawable.ic_input).mutate();
        icons[ITEM_WIFI] = ctx.getDrawable(R.drawable.ic_wifi).mutate();
        icons[ITEM_SETTINGS] = ctx.getDrawable(R.drawable.ic_settings).mutate();
        for (Drawable ic : icons) ic.setTint(0xFFFFFFFF);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(15 * d);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        bold.setColor(0xFFFFFFFF);
        bold.setTextSize(15 * d);
        bold.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        // The window behind the bar fades with the wallpaper, so the bar only ever shows a tint over it.
        refreshClock();
    }

    static int heightPx(Context ctx) {
        return Math.round(34 * ctx.getResources().getDisplayMetrics().density);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setBlur(Bitmap b) {
        blur = b;
        invalidate();
    }

    void refreshClock() {
        Locale loc = Locale.getDefault();
        String pattern = DateFormat.getBestDateTimePattern(loc,
                DateFormat.is24HourFormat(getContext()) ? "EEEdMMMHm" : "EEEdMMMjm");
        when = new java.text.SimpleDateFormat(pattern, loc).format(new Date());
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        animate().cancel();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(highlight, gainFocus ? 1f : 0f);
        a.setDuration(140);
        a.addUpdateListener(v -> {
            highlight = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (selected > 0) selected--;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (selected < icons.length - 1) selected++;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) listener.onDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (listener != null) listener.onItem(selected);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        bar.set(0, 0, w, h);
        if (blur != null) MacBlur.draw(c, blur, this, bar, 0, blurPaint);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(blur != null ? 0x55000000 : 0x99202024);
        c.drawRect(bar, fill);
        // The hairline the Mac draws under its menu bar.
        fill.setColor(0x22FFFFFF);
        c.drawRect(0, h - 1, w, h, fill);

        float pad = 28 * d;
        float baseline = h / 2f - (text.ascent() + text.descent()) / 2f;
        float x = pad;
        drawApple(c, x, h / 2f, 15 * d);
        x += 15 * d + 20 * d;
        c.drawText("Finder", x, baseline, bold);
        x += bold.measureText("Finder") + 20 * d;
        for (String m : MENUS) {
            c.drawText(m, x, baseline, text);
            x += text.measureText(m) + 20 * d;
        }

        // Right side, from the edge inwards: date and time, then the icons.
        float right = w - pad;
        float tw = text.measureText(when);
        c.drawText(when, right - tw, baseline, text);
        right -= tw + 22 * d;
        float iconSize = 20 * d, itemW = 34 * d, itemH = 24 * d;
        for (int i = icons.length - 1; i >= 0; i--) {
            float cx = right - itemW / 2f;
            itemRect[i].set(cx - itemW / 2f, h / 2f - itemH / 2f, cx + itemW / 2f, h / 2f + itemH / 2f);
            if (i == selected && highlight > 0f) {
                fill.setColor(((int) (0x55 * highlight) << 24) | 0xFFFFFF);
                c.drawRoundRect(itemRect[i], 6 * d, 6 * d, fill);
            }
            icons[i].setBounds((int) (cx - iconSize / 2f), (int) (h / 2f - iconSize / 2f),
                    (int) (cx + iconSize / 2f), (int) (h / 2f + iconSize / 2f));
            icons[i].draw(c);
            right -= itemW + 4 * d;
        }
    }

    /** A small apple: a body of two lobes with a bite out of the right side, and a leaf. */
    private void drawApple(Canvas c, float x, float cy, float size) {
        apple.reset();
        float r = size * 0.36f;
        float cx = x + size / 2f;
        Path body = new Path();
        body.addCircle(cx - r * 0.45f, cy + size * 0.08f, r, Path.Direction.CW);
        Path lobe = new Path();
        lobe.addCircle(cx + r * 0.45f, cy + size * 0.08f, r, Path.Direction.CW);
        body.op(lobe, Path.Op.UNION);
        Path bite = new Path();
        bite.addCircle(cx + size * 0.5f, cy - size * 0.02f, size * 0.17f, Path.Direction.CW);
        body.op(bite, Path.Op.DIFFERENCE);
        apple.addPath(body);
        // Leaf.
        tmp.set(cx - size * 0.02f, cy - size * 0.52f, cx + size * 0.3f, cy - size * 0.2f);
        Path leaf = new Path();
        leaf.moveTo(cx, cy - size * 0.26f);
        leaf.quadTo(cx + size * 0.02f, cy - size * 0.55f, cx + size * 0.3f, cy - size * 0.52f);
        leaf.quadTo(cx + size * 0.28f, cy - size * 0.24f, cx, cy - size * 0.26f);
        apple.addPath(leaf);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        c.drawPath(apple, fill);
    }
}
