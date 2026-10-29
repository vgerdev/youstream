package com.youstream.media;

import java.util.ArrayList;
import java.util.List;

/** The HLS quality levels produced for a source; never upscales. */
public final class Ladder {

    public record Rung(String name, int width, int height, int videoKbps) {
    }

    private static final int[][] RUNGS = {
        // height, video kbps
        {1080, 5000},
        {720, 2800},
        {480, 1400},
        {360, 800},
    };

    private Ladder() {
    }

    public static List<Rung> forSource(int width, int height) {
        double aspect = (double) width / height;
        List<Rung> rungs = new ArrayList<>();
        for (int[] r : RUNGS) {
            if (r[0] <= height) {
                rungs.add(rung(r[0], aspect, r[1]));
            }
        }
        if (rungs.isEmpty()) {
            // tiny source: one rendition at its own (even) size
            int h = Math.max(2, height - height % 2);
            rungs.add(rung(h, aspect, Math.max(200, 800 * h / 360)));
        }
        return rungs;
    }

    private static Rung rung(int height, double aspect, int kbps) {
        int width = (int) Math.round(height * aspect / 2) * 2;
        return new Rung(height + "p", Math.max(2, width), height, kbps);
    }
}
