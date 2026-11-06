package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Takes over the remote's volume buttons and shows the Mac's volume indicator over whatever is on screen: a dark
 * rounded square with a speaker and sixteen pips, near the bottom edge, fading in and out.
 *
 * The service changes the volume itself (about a quarter of a pip per press) and consumes the key, so the TV's
 * own volume bar does not show as well. Where the TV has fixed volume (the sound goes out over HDMI to a
 * receiver that does its own), or Android refuses the change, the key is passed on untouched and nothing is drawn.
 * Opt-in: VOLUME_HUD=1 build-and-install.sh (or -VolumeHud on Windows) enables it.
 */
public class VolumeHudService extends AccessibilityService {

    private static final long HOLD_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private WindowManager windows;
    private Hud hud;
    private boolean attached;
    private ValueAnimator fade;
    private final Runnable hide = () -> fadeTo(0f, 320);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN
                && key != KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (audio == null || windows == null || audio.isVolumeFixed()) return false;
        // Both halves of a press are consumed once the first was; the work happens on the way down.
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        try {
            int stream = AudioManager.STREAM_MUSIC;
            int max = audio.getStreamMaxVolume(stream);
            if (key == KeyEvent.KEYCODE_VOLUME_MUTE) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            } else {
                int step = Math.max(1, Math.round(max / 64f));
                int cur = audio.getStreamVolume(stream);
                if (audio.isStreamMute(stream)) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                int next = Math.max(0, Math.min(max, cur + (key == KeyEvent.KEYCODE_VOLUME_UP ? step : -step)));
                audio.setStreamVolume(stream, next, 0);
            }
            show(audio.getStreamVolume(stream), max, audio.isStreamMute(stream));
        } catch (SecurityException | RuntimeException e) {
            // Do-not-disturb rules or a vendor audio policy said no: let the TV handle the key itself.
            return false;
        }
        return true;
    }

    private void show(int volume, int max, boolean muted) {
        if (hud == null) hud = new Hud(this);
        hud.set(max <= 0 ? 0f : volume / (float) max, muted || volume == 0, muted);
        if (!attached) {
            float d = getResources().getDisplayMetrics().density;
            int size = Math.round(Hud.SIZE_DP * d);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(70 * d);
            hud.setAlpha(0f);
            try {
                windows.addView(hud, lp);
                attached = true;
            } catch (RuntimeException e) {
                return;
            }
        }
        handler.removeCallbacks(hide);
        fadeTo(1f, 90);
        handler.postDelayed(hide, HOLD_MS);
    }

    private void fadeTo(float alpha, long ms) {
        if (hud == null) return;
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(hud.getAlpha(), alpha);
        fade.setDuration(ms);
        fade.addUpdateListener(v -> hud.setAlpha((float) v.getAnimatedValue()));
        if (alpha == 0f) {
            fade.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (!cancelled) detach();
                }
            });
        }
        fade.start();
    }

    private void detach() {
        if (!attached) return;
        try {
            windows.removeView(hud);
        } catch (RuntimeException ignored) {
        }
        attached = false;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        handler.removeCallbacks(hide);
        if (fade != null) fade.cancel();
        detach();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** The indicator: rounded dark square, speaker with waves (or a slash when muted), sixteen pips. */
    private static final class Hud extends View {
        static final float SIZE_DP = 176f;
        private static final int PIPS = 16;

        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path speaker = new Path();
        private float level;
        private boolean silent, muted;

        Hud(Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
        }

        void set(float level, boolean silent, boolean muted) {
            this.level = Math.max(0f, Math.min(1f, level));
            this.silent = silent;
            this.muted = muted;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xDD262628);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, w * 0.2f, w * 0.2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f * d);
            p.setColor(0x33FFFFFF);
            r.inset(0.5f * d, 0.5f * d);
            c.drawRoundRect(r, w * 0.2f, w * 0.2f, p);

            drawSpeaker(c, w / 2f, h * 0.40f, w * 0.32f);

            // Sixteen pips; the level is shown in quarter pips.
            float margin = w * 0.1f, gap = 2f * d;
            float pipW = (w - 2 * margin - gap * (PIPS - 1)) / PIPS, pipH = 7 * d;
            float y = h - margin - pipH - 4 * d;
            float lit = muted ? 0f : level * PIPS;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < PIPS; i++) {
                float x = margin + i * (pipW + gap);
                r.set(x, y, x + pipW, y + pipH);
                p.setColor(0x40FFFFFF);
                c.drawRoundRect(r, 1.5f * d, 1.5f * d, p);
                float part = Math.max(0f, Math.min(1f, lit - i));
                if (part > 0f) {
                    part = Math.round(part * 4f) / 4f;
                    if (part > 0f) {
                        r.set(x, y, x + pipW * part, y + pipH);
                        p.setColor(0xFFFFFFFF);
                        c.drawRoundRect(r, 1.5f * d, 1.5f * d, p);
                    }
                }
            }
        }

        private void drawSpeaker(Canvas c, float cx, float cy, float size) {
            float u = size / 10f;
            float x0 = cx - 5.5f * u;
            speaker.reset();
            // Box, then the cone opening to the right.
            speaker.moveTo(x0, cy - 1.8f * u);
            speaker.lineTo(x0 + 2.4f * u, cy - 1.8f * u);
            speaker.lineTo(x0 + 5.4f * u, cy - 4.2f * u);
            speaker.lineTo(x0 + 5.4f * u, cy + 4.2f * u);
            speaker.lineTo(x0 + 2.4f * u, cy + 1.8f * u);
            speaker.lineTo(x0, cy + 1.8f * u);
            speaker.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawPath(speaker, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(1.1f * u);
            if (silent) {
                // A slash through nothing: the waves are gone, a cross sits where they were.
                float sx = x0 + 7.4f * u;
                c.drawLine(sx, cy - 1.6f * u, sx + 3.2f * u, cy + 1.6f * u, p);
                c.drawLine(sx, cy + 1.6f * u, sx + 3.2f * u, cy - 1.6f * u, p);
            } else {
                int waves = level < 0.34f ? 1 : level < 0.67f ? 2 : 3;
                for (int i = 1; i <= waves; i++) {
                    float rad = (1.5f + i * 1.7f) * u;
                    r.set(x0 + 5.4f * u - rad * 0.55f, cy - rad, x0 + 5.4f * u + rad * 0.95f, cy + rad);
                    c.drawArc(r, -42f, 84f, false, p);
                }
            }
            p.setStrokeCap(Paint.Cap.BUTT);
        }
    }
}
