package com.function.cardissuer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** A bottom separator for flat, edge-aligned rows. */
final class FlatDividerDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    FlatDividerDrawable(Context context) { this(context, Color.rgb(232, 232, 237), 0.5f); }

    FlatDividerDrawable(Context context, int color, float thicknessDp) {
        paint.setColor(color);
        paint.setStrokeWidth(Math.max(1, thicknessDp * context.getResources().getDisplayMetrics().density));
    }

    @Override public void draw(Canvas canvas) {
        float y = getBounds().bottom - paint.getStrokeWidth() / 2;
        canvas.drawLine(getBounds().left, y, getBounds().right, y, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
