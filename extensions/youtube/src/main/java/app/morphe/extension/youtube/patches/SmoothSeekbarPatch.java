/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5532
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * The player updates the seekbar progress about once a second, so on short videos
 * the played part and the thumb move in steps of many pixels.
 * Each step that follows the playback is drawn as a movement over the time until the next update.
 * The seekbar is drawn again only when the drawn position moves by a pixel,
 * so steps of one pixel (long videos) draw the seekbar exactly as often as before.
 * <p>
 * All methods are called on the main thread.
 */
@SuppressWarnings("unused")
public final class SmoothSeekbarPatch {

    /**
     * Smallest step that is animated. A step of one pixel looks the same with or without the movement.
     */
    private static final int MIN_ANIMATED_STEP_PX = 2;

    /**
     * Longest time between two steps of a playing video. A longer time means playback was paused,
     * and the step is animated over the time between the steps before the pause.
     */
    private static final long MAX_UPDATE_INTERVAL_MS = 2000;

    /**
     * Steps of a playing video have about the same size, up to one pixel of rounding.
     * A larger difference (fraction of the previous step) is a seek or a playback speed change.
     */
    private static final int STEP_TOLERANCE_DIVISOR = 4;

    /**
     * Progress of each seekbar. The player keeps several seekbars, which are all updated.
     */
    private static final Map<View, SeekbarProgress> seekbars = new WeakHashMap<>();

    private static final class SeekbarProgress {
        /**
         * Draws the seekbar again. Keeps only a weak reference, because the seekbar is the map key.
         */
        final Runnable invalidate;

        /**
         * Copy of the played part that is drawn, made again on every progress update.
         */
        Rect playedRect;

        /**
         * Right edge of the played part set by the last progress update.
         */
        int playedRight;

        /**
         * Right edge of the played part in the current draw.
         */
        int drawnRight;

        int animationStartRight;
        long animationStartTime;
        long animationDuration;

        int lastStep;
        long lastStepTime;
        long updateInterval;

        SeekbarProgress(View seekbar, Rect played) {
            WeakReference<View> seekbarRef = new WeakReference<>(seekbar);
            invalidate = () -> {
                View view = seekbarRef.get();
                if (view != null) {
                    view.invalidate();
                }
            };
            playedRect = played;
            playedRight = played.right;
            drawnRight = playedRight;
        }

        int getDrawnRight(long now) {
            final long elapsed = now - animationStartTime;
            if (elapsed >= animationDuration || animationStartRight >= playedRight) {
                return playedRight;
            }
            return animationStartRight + (int) ((playedRight - animationStartRight) * elapsed / animationDuration);
        }
    }

    private SmoothSeekbarPatch() {}

    /**
     * Injection point. Called after a progress update copied the played part for drawing.
     */
    public static void setPlayedRect(View seekbar, Rect played) {
        try {
            if (!Settings.SMOOTH_SEEKBAR.get()) {
                return;
            }

            SeekbarProgress progress = seekbars.get(seekbar);
            if (progress == null) {
                seekbars.put(seekbar, new SeekbarProgress(seekbar, played));
                return;
            }

            final int right = played.right;
            if (right != progress.playedRight) {
                final long now = SystemClock.uptimeMillis();
                final int step = right - progress.playedRight;
                long interval = now - progress.lastStepTime;
                if (interval > MAX_UPDATE_INTERVAL_MS) {
                    interval = progress.updateInterval;
                } else {
                    progress.updateInterval = interval;
                }

                final int lastStep = progress.lastStep;
                final boolean isPlaybackStep = interval > 0
                        && step >= MIN_ANIMATED_STEP_PX
                        && Math.abs(step - lastStep) <= Math.max(1, lastStep / STEP_TOLERANCE_DIVISOR);

                progress.animationStartRight = progress.getDrawnRight(now);
                progress.animationStartTime = now;
                progress.animationDuration = isPlaybackStep ? interval : 0;
                progress.lastStep = step;
                progress.lastStepTime = now;
                progress.playedRight = right;
            }

            progress.playedRect = played;
        } catch (Exception ex) {
            Logger.printException(() -> "setPlayedRect failure", ex);
        }
    }

    /**
     * Injection point. Called when the seekbar starts drawing.
     */
    public static void onSeekbarDraw(View seekbar) {
        try {
            if (!Settings.SMOOTH_SEEKBAR.get()) {
                return;
            }

            SeekbarProgress progress = seekbars.get(seekbar);
            if (progress == null) {
                return;
            }

            final long now = SystemClock.uptimeMillis();
            final int drawnRight = progress.getDrawnRight(now);
            progress.drawnRight = drawnRight;
            progress.playedRect.right = drawnRight;

            if (drawnRight < progress.playedRight) {
                // Draw again when the drawn position reaches the next pixel.
                final long pixels = progress.playedRight - progress.animationStartRight;
                final long nextPixel = drawnRight - progress.animationStartRight + 1;
                final long nextPixelTime = progress.animationStartTime
                        + (nextPixel * progress.animationDuration + pixels - 1) / pixels;
                seekbar.removeCallbacks(progress.invalidate);
                seekbar.postDelayed(progress.invalidate, Math.max(0, nextPixelTime - now));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSeekbarDraw failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @return The x of the thumb to draw. The thumb follows the drawn played part,
     *         unless it is somewhere else (such as while it is dragged).
     */
    public static int getThumbX(View seekbar, int thumbX) {
        try {
            if (Settings.SMOOTH_SEEKBAR.get()) {
                SeekbarProgress progress = seekbars.get(seekbar);
                if (progress != null && thumbX == progress.playedRight) {
                    return progress.drawnRight;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getThumbX failure", ex);
        }
        return thumbX;
    }
}
