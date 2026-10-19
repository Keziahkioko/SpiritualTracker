package com.keziah.spiritualtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Framing the home photo: it always fills the frame, and a framing carries over to other screen sizes. */
public class PhotoFrameTest {

    private static final float EPS = 0.01f;

    /** Visible part of the photo, in photo pixels: {left, top, right, bottom}. */
    private static float[] visible(float iw, float ih, float w, float h, float[] f) {
        float s = f[0];
        return new float[]{f[1] - w / (2 * s), f[2] - h / (2 * s), f[1] + w / (2 * s), f[2] + h / (2 * s)};
    }

    private static void assertFills(float iw, float ih, float w, float h, float[] f) {
        float[] v = visible(iw, ih, w, h, f);
        assertTrue("no gap on the left/top", v[0] >= -EPS && v[1] >= -EPS);
        assertTrue("no gap on the right/bottom", v[2] <= iw + EPS && v[3] <= ih + EPS);
    }

    @Test
    public void defaultShowsAsMuchAsFitsCentred() {
        // A tall photo in a wide frame: full width, centred vertically.
        float[] f = PhotoFrame.fit(1000, 2000, 720, 600, 0.5f, 0.5f, 1f);
        assertEquals(0.72f, f[0], EPS);
        assertEquals(500, f[1], EPS);
        assertEquals(1000, f[2], EPS);
        assertEquals(1f, f[3], EPS);
        assertFills(1000, 2000, 720, 600, f);
    }

    @Test
    public void cannotMovePastTheEdge() {
        // Asking for the very top of the photo keeps the frame inside it.
        float[] f = PhotoFrame.fit(1000, 2000, 720, 600, 0.5f, 0f, 1f);
        assertFills(1000, 2000, 720, 600, f);
        assertEquals(0, visible(1000, 2000, 720, 600, f)[1], EPS);
    }

    @Test
    public void zoomIsLimited() {
        float[] in = PhotoFrame.fit(1000, 2000, 720, 600, 0.5f, 0.5f, 0.01f);
        assertEquals(1f / PhotoFrame.MAX_ZOOM, in[3], EPS);
        float[] out = PhotoFrame.fit(1000, 2000, 720, 600, 0.5f, 0.5f, 3f);
        assertEquals(1f, out[3], EPS);
    }

    @Test
    public void dragMovesThePhotoWithTheFinger() {
        // Zoomed in 2x, drag right by 100 px: the photo moves right, so a point further left is centred.
        float[] next = PhotoFrame.moved(1000, 2000, 720, 600, 0.5f, 0.5f, 0.5f, 100, 0, 1f, 360, 300);
        assertTrue(next[0] < 0.5f);
        assertEquals(0.5f, next[1], EPS);
    }

    @Test
    public void pinchKeepsThePointUnderTheFingers() {
        float iw = 1000, ih = 2000, w = 720, h = 600, fx = 600, fy = 150;
        float[] before = PhotoFrame.fit(iw, ih, w, h, 0.5f, 0.5f, 1f);
        float underX = before[1] + (fx - w / 2) / before[0];
        float underY = before[2] + (fy - h / 2) / before[0];
        float[] next = PhotoFrame.moved(iw, ih, w, h, 0.5f, 0.5f, 1f, 0, 0, 2f, fx, fy);
        float[] after = PhotoFrame.fit(iw, ih, w, h, next[0], next[1], next[2]);
        assertEquals(underX, after[1] + (fx - w / 2) / after[0], 0.5f);
        assertEquals(underY, after[2] + (fy - h / 2) / after[0], 0.5f);
        assertEquals(0.5f, next[2], EPS);
    }

    @Test
    public void framingCarriesOverToAnotherPhone() {
        // Chosen on one phone, shown on a slightly taller header: same centre, still no gaps.
        float[] chosen = PhotoFrame.fit(2000, 1500, 720, 640, 0.3f, 0.6f, 0.6f);
        float[] other = PhotoFrame.fit(2000, 1500, 1080, 1010, chosen[1] / 2000, chosen[2] / 1500, chosen[3]);
        assertFills(2000, 1500, 1080, 1010, other);
        assertEquals(chosen[1], other[1], 1f);
        assertEquals(chosen[2], other[2], 1f);
    }
}
