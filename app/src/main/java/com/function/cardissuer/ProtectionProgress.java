package com.function.cardissuer;

@FunctionalInterface
interface ProtectionProgress {
    void update(String stage, int percent);

    static int percent(long done, long total, int start, int end) {
        if (total <= 0) return start;
        double fraction = Math.min(1.0, Math.max(0.0, (double) done / total));
        return start + (int) Math.round(fraction * (end - start));
    }
}
