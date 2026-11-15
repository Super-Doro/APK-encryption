package com.function.cardissuer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** A floating tab bar with a blurred backdrop and a draggable selection lens. */
final class LiquidGlassNavigation extends FrameLayout {
    private static final String[] TITLES = {"加密", "转换", "日志"};
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int GRAY = Color.rgb(94, 99, 112);
    private final ImageView backdrop;
    private final GlassSurface glass;
    private final TabItem[] tabs = new TabItem[TITLES.length];
    private final int touchSlop;
    private final Runnable longPress = this::beginDrag;
    private final ViewTreeObserver.OnPreDrawListener preDraw = this::updateBackdrop;
    private View backdropSource;
    private ViewTreeObserver sourceObserver;
    private Bitmap snapshot;
    private IntConsumer listener;
    private ValueAnimator selectionAnimation;
    private int selectedIndex;
    private int previewIndex;
    private int pointerId = -1;
    private float lensPosition;
    private float lensStretch;
    private float downX;
    private float downY;
    private float touchX;
    private float touchY;
    private long downTime;
    private long lastSnapshot;
    private boolean tracking;
    private boolean dragging;
    private boolean multiTouch;
    private boolean backdropDirty = true;
    private boolean snapshotEnabled = true;

    LiquidGlassNavigation(Context context) {
        super(context);
        setClipChildren(false);
        setClipToOutline(true);
        setElevation(dp(12));
        setOutlineAmbientShadowColor(Color.argb(100, 46, 59, 79));
        setOutlineSpotShadowColor(Color.argb(95, 46, 59, 79));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                outline.setAlpha(0.28f);
            }
        });
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        backdrop = new ImageView(context);
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        backdrop.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int bleed = dp(16);
        FrameLayout.LayoutParams background = new FrameLayout.LayoutParams(-1, -1);
        background.setMargins(-bleed, -bleed, -bleed, -bleed);
        addView(backdrop, background);
        if (Build.VERSION.SDK_INT >= 31) {
            try { backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(14), dp(14), Shader.TileMode.CLAMP)); }
            catch (RuntimeException error) { android.util.Log.w("DoroNavigation", "系统模糊效果不可用", error); }
        }
        glass = new GlassSurface(context);
        glass.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(glass, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout items = new LinearLayout(context);
        items.setPadding(dp(6), dp(6), dp(6), dp(6));
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            tabs[i] = new TabItem(context, i);
            tabs[i].setOnClickListener(view -> {
                if (listener != null) listener.accept(index);
            });
            items.addView(tabs[i], new LinearLayout.LayoutParams(0, -1, 1));
        }
        addView(items, new FrameLayout.LayoutParams(-1, -1));
        updateTabStyles();
    }

    int preferredHeight() {
        return Math.max(dp(72), dp(50) + Math.round(12 * getResources().getDisplayMetrics().scaledDensity));
    }

    static int contentBottomInset(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.max(Math.round(72 * metrics.density), Math.round(50 * metrics.density + 12 * metrics.scaledDensity))
                + Math.round(28 * metrics.density);
    }

    void setOnTabSelectedListener(IntConsumer listener) { this.listener = listener; }

    void setBackdropSource(View source) {
        detachObserver();
        backdropSource = source;
        attachObserver();
        invalidateBackdrop();
    }

    void invalidateBackdrop() {
        backdropDirty = true;
        invalidate();
    }

    void select(int index) {
        int next = Math.max(0, Math.min(tabs.length - 1, index));
        boolean changed = selectedIndex != next;
        selectedIndex = next;
        if (!dragging) {
            previewIndex = next;
            if (changed) animateLens(next);
        }
        updateTabStyles();
        invalidateBackdrop();
    }

    private void animateLens(int index) {
        if (selectionAnimation != null) selectionAnimation.cancel();
        float start = lensPosition;
        float stretch = lensStretch;
        if (!ValueAnimator.areAnimatorsEnabled() || getWidth() == 0) {
            lensPosition = index;
            lensStretch = 0;
            glass.invalidate();
            return;
        }
        selectionAnimation = ValueAnimator.ofFloat(0, 1);
        selectionAnimation.setDuration(290);
        selectionAnimation.setInterpolator(new OvershootInterpolator(0.65f));
        selectionAnimation.addUpdateListener(animation -> {
            float amount = (float) animation.getAnimatedValue();
            lensPosition = start + (index - start) * amount;
            lensStretch = stretch * Math.max(0, 1 - amount);
            glass.invalidate();
        });
        selectionAnimation.start();
    }

    private float positionAt(float x) {
        float width = Math.max(1, (getWidth() - dp(12)) / (float) tabs.length);
        return Math.max(0, Math.min(tabs.length - 1, (x - dp(6)) / width - 0.5f));
    }

    private void beginDrag() {
        if (!tracking || multiTouch || !isShown() || !hasWindowFocus()) return;
        MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, touchX, touchY, 0);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        if (selectionAnimation != null) selectionAnimation.cancel();
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(1.025f).scaleY(1.025f).setDuration(150).start();
        moveLens(touchX);
    }

    private void moveLens(float x) {
        float next = positionAt(x);
        lensStretch = Math.min(0.09f, Math.abs(next - lensPosition) * 0.25f);
        lensPosition = next;
        int hovered = Math.round(next);
        if (previewIndex != hovered) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        previewIndex = hovered;
        updateTabStyles();
        glass.invalidate();
    }

    private void endDrag(boolean commit) {
        int target = commit ? previewIndex : selectedIndex;
        tracking = false;
        dragging = false;
        pointerId = -1;
        removeCallbacks(longPress);
        animate().cancel();
        if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(1).scaleY(1).setDuration(180).start();
        else { setScaleX(1); setScaleY(1); }
        previewIndex = target;
        if (commit && listener != null) listener.accept(target);
        previewIndex = selectedIndex;
        updateTabStyles();
        if (selectionAnimation == null || !selectionAnimation.isRunning()) animateLens(selectedIndex);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            removeCallbacks(longPress);
            tracking = true;
            multiTouch = false;
            pointerId = event.getPointerId(0);
            downX = touchX = event.getX();
            downY = touchY = event.getY();
            downTime = event.getDownTime();
            postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            removeCallbacks(longPress);
            if (dragging) endDrag(false);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (multiTouch) return true;
            int pointer = event.findPointerIndex(pointerId);
            if (pointer < 0) {
                if (dragging) endDrag(false);
                removeCallbacks(longPress);
                tracking = false;
                return true;
            }
            touchX = event.getX(pointer);
            touchY = event.getY(pointer);
            if (dragging) { moveLens(touchX); return true; }
            if (Math.abs(touchX - downX) > touchSlop || Math.abs(touchY - downY) > touchSlop) {
                removeCallbacks(longPress);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
            tracking = false;
            if (dragging) {
                boolean inside = event.getX() >= -dp(28) && event.getX() <= getWidth() + dp(28)
                        && event.getY() >= -dp(28) && event.getY() <= getHeight() + dp(28);
                if (action == MotionEvent.ACTION_UP && inside) moveLens(event.getX());
                endDrag(action == MotionEvent.ACTION_UP && inside && !multiTouch);
                return true;
            }
            if (multiTouch) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
            pointerId = -1;
        }
        return super.dispatchTouchEvent(event);
    }

    private void updateTabStyles() {
        for (int i = 0; i < tabs.length; i++) {
            TabItem item = tabs[i];
            if (item == null) continue;
            int color = i == previewIndex ? BLUE : GRAY;
            item.icon.color = color;
            item.icon.invalidate();
            item.title.setTextColor(color);
            item.setSelected(i == selectedIndex);
        }
    }

    private void attachObserver() {
        if (!isAttachedToWindow() || backdropSource == null || sourceObserver != null) return;
        sourceObserver = backdropSource.getViewTreeObserver();
        sourceObserver.addOnPreDrawListener(preDraw);
    }

    private void detachObserver() {
        if (sourceObserver != null && sourceObserver.isAlive()) sourceObserver.removeOnPreDrawListener(preDraw);
        sourceObserver = null;
    }

    private boolean dirty(View view) {
        if (view.getVisibility() != VISIBLE) return false;
        if (view.isDirty()) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) if (dirty(group.getChildAt(i))) return true;
        }
        return false;
    }

    private boolean updateBackdrop() {
        if (!snapshotEnabled || !isShown() || backdropSource == null || getWidth() == 0
                || backdropSource.getWidth() == 0 || backdropSource.getHeight() == 0) return true;
        backdropDirty |= dirty(backdropSource);
        if (!backdropDirty) return true;
        long now = SystemClock.uptimeMillis();
        if (now - lastSnapshot < 80) { postInvalidateDelayed(80 - (now - lastSnapshot)); return true; }
        try {
            int bleed = dp(16);
            int scale = Build.VERSION.SDK_INT >= 31 ? 6 : 10;
            int width = Math.max(1, (getWidth() + bleed * 2 + scale - 1) / scale);
            int height = Math.max(1, (getHeight() + bleed * 2 + scale - 1) / scale);
            if (snapshot == null || snapshot.getWidth() != width || snapshot.getHeight() != height) {
                snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            snapshot.eraseColor(Color.WHITE);
            int[] source = new int[2];
            int[] destination = new int[2];
            backdropSource.getLocationInWindow(source);
            getLocationInWindow(destination);
            Canvas canvas = new Canvas(snapshot);
            canvas.scale(width / (float) (getWidth() + bleed * 2), height / (float) (getHeight() + bleed * 2));
            canvas.translate(source[0] - destination[0] + bleed, source[1] - destination[1] + bleed);
            backdropSource.draw(canvas);
            backdrop.setImageBitmap(snapshot);
            backdrop.invalidate();
            backdropDirty = false;
            lastSnapshot = now;
        } catch (RuntimeException | OutOfMemoryError error) {
            snapshotEnabled = false;
            backdrop.setImageDrawable(null);
            android.util.Log.e("DoroNavigation", "玻璃背景采样失败", error);
        }
        return true;
    }

    @Override protected void onSizeChanged(int width, int height, int previousWidth, int previousHeight) {
        super.onSizeChanged(width, height, previousWidth, previousHeight);
        invalidateOutline();
        invalidateBackdrop();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attachObserver(); }

    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        if (glass == null) return;
        backdropDirty = true;
        if (!isShown()) {
            removeCallbacks(longPress);
            tracking = false;
            if (dragging) endDrag(false);
        }
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) {
            removeCallbacks(longPress);
            tracking = false;
            if (dragging) endDrag(false);
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        detachObserver();
        if (selectionAnimation != null) selectionAnimation.cancel();
        animate().cancel();
        tracking = false;
        dragging = false;
        super.onDetachedFromWindow();
    }

    private final class GlassSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final RectF lens = new RectF();
        private Shader tint;
        private Shader rim;
        private Shader lensTint;

        GlassSurface(Context context) { super(context); }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            tint = new LinearGradient(0, 0, 0, height,
                    new int[]{Color.argb(215, 255, 255, 255), Color.argb(185, 255, 255, 255),
                            Color.argb(205, 255, 255, 255)}, null, Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, 0, width, height,
                    new int[]{Color.WHITE, Color.argb(135, 255, 255, 255), Color.argb(38, 75, 95, 125),
                            Color.argb(210, 255, 255, 255)}, new float[]{0, 0.4f, 0.8f, 1}, Shader.TileMode.CLAMP);
            lensTint = new LinearGradient(0, dp(6), 0, height - dp(6),
                    new int[]{Color.argb(248, 255, 255, 255), Color.argb(225, 250, 250, 250),
                            Color.argb(185, 245, 245, 245)}, null, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            float inset = dp(0.7f);
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(tint);
            canvas.drawRoundRect(bounds, getHeight() / 2f, getHeight() / 2f, paint);
            float slot = (getWidth() - dp(12)) / (float) tabs.length;
            float position = Math.max(-0.05f, Math.min(tabs.length - 0.95f, lensPosition));
            float center = dp(6) + slot * (position + 0.5f);
            float halfWidth = (slot - dp(4)) * (1 + lensStretch) / 2;
            float halfHeight = (getHeight() - dp(12)) * (1 - lensStretch * 0.4f) / 2;
            lens.set(center - halfWidth, getHeight() / 2f - halfHeight,
                    center + halfWidth, getHeight() / 2f + halfHeight);
            paint.setShader(null);
            paint.setColor(Color.argb(18, 90, 90, 95));
            canvas.drawRoundRect(lens.left, lens.top + dp(2), lens.right, lens.bottom + dp(2), halfHeight, halfHeight, paint);
            paint.setShader(lensTint);
            canvas.drawRoundRect(lens, halfHeight, halfHeight, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.8f));
            paint.setShader(null);
            paint.setColor(Color.argb(230, 255, 255, 255));
            canvas.drawRoundRect(lens, halfHeight, halfHeight, paint);
            paint.setShader(rim);
            paint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(bounds, getHeight() / 2f, getHeight() / 2f, paint);
            paint.setShader(null);
        }
    }

    private final class TabItem extends LinearLayout {
        final Icon icon;
        final TextView title;

        TabItem(Context context, int index) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(true);
            setContentDescription(TITLES[index]);
            icon = new Icon(context, index);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon, new LinearLayout.LayoutParams(dp(25), dp(26)));
            title = new TextView(context);
            title.setText(TITLES[index]);
            title.setTextSize(11);
            title.setIncludeFontPadding(false);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-2, -2);
            label.topMargin = dp(4);
            addView(title, label);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setSelected(isSelected());
        }
    }

    private static final class Icon extends View {
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = GRAY;

        Icon(Context context, int type) { super(context); this.type = type; }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 25f);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            if (type == 2) {
                canvas.drawRoundRect(5, 2, 20, 23, 3, 3, paint);
                canvas.drawLine(9, 8, 16, 8, paint);
                canvas.drawLine(9, 13, 16, 13, paint);
                canvas.drawLine(9, 18, 14, 18, paint);
            } else if (type == 1) {
                canvas.drawLine(4, 8, 20, 8, paint);
                canvas.drawLine(16, 4, 20, 8, paint);
                canvas.drawLine(16, 12, 20, 8, paint);
                canvas.drawLine(20, 18, 4, 18, paint);
                canvas.drawLine(8, 14, 4, 18, paint);
                canvas.drawLine(8, 22, 4, 18, paint);
            } else {
                canvas.drawRoundRect(5, 10, 19, 22, 3, 3, paint);
                canvas.drawArc(8, 2.5f, 16, 15, 180, 180, false, paint);
                canvas.drawLine(12, 15, 12, 17.5f, paint);
            }
            canvas.restore();
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
