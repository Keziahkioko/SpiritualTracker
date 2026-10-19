package com.keziah.spiritualtracker;

/**
 * How a photo sits in a frame, stored independently of screen size so the same framing fits
 * any phone: the point of the photo at the frame's centre ({@code cx}, {@code cy}, as fractions
 * of its width and height) and how much of its width shows ({@code zoom}: 1 = as much as fits).
 * The photo always fills the frame; it can't be moved or shrunk so that a gap shows.
 */
final class PhotoFrame {

    static final float MAX_ZOOM = 5f;

    private PhotoFrame() {}

    /**
     * For a photo of {@code iw}×{@code ih} px in a frame of {@code w}×{@code h} px, returns
     * {scale, photo x at the frame's centre (px), photo y at the frame's centre (px), zoom}
     * after keeping the photo filling the frame. The returned zoom and centre are what was
     * actually shown, so saving them saves exactly what the person saw.
     */
    static float[] fit(float iw, float ih, float w, float h, float cx, float cy, float zoom) {
        float cover = Math.max(w / iw, h / ih);
        float scale = zoom > 0 && zoom < 1 ? w / (zoom * iw) : cover;
        scale = Math.max(cover, Math.min(cover * MAX_ZOOM, scale));
        float halfW = w / (2 * scale), halfH = h / (2 * scale);
        float px = clamp(cx * iw, halfW, iw - halfW);
        float py = clamp(cy * ih, halfH, ih - halfH);
        return new float[]{scale, px, py, Math.min(1f, (w / scale) / iw)};
    }

    /**
     * After a drag of ({@code dx}, {@code dy}) px and a pinch by {@code factor} around
     * ({@code fx}, {@code fy}) in the frame, the new {cx, cy, zoom}. The photo point under the
     * fingers stays under them.
     */
    static float[] moved(float iw, float ih, float w, float h, float cx, float cy, float zoom,
                         float dx, float dy, float factor, float fx, float fy) {
        float[] now = fit(iw, ih, w, h, cx, cy, zoom);
        float scale = now[0];
        // The photo point under the fingers, then where the centre must be to keep it there.
        float underX = now[1] + (fx - w / 2) / scale;
        float underY = now[2] + (fy - h / 2) / scale;
        float cover = Math.max(w / iw, h / ih);
        float newScale = Math.max(cover, Math.min(cover * MAX_ZOOM, scale * factor));
        float px = underX - (fx - w / 2) / newScale - dx / newScale;
        float py = underY - (fy - h / 2) / newScale - dy / newScale;
        float[] after = fit(iw, ih, w, h, px / iw, py / ih, (w / newScale) / iw);
        return new float[]{after[1] / iw, after[2] / ih, after[3]};
    }

    private static float clamp(float v, float lo, float hi) {
        if (lo > hi) return (lo + hi) / 2;
        return Math.max(lo, Math.min(hi, v));
    }
}
