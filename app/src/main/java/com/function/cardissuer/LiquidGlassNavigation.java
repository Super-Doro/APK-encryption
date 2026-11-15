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
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int GRAY = Color.rgb(94, 99, 112);
    private final ImageView backdrop;
    private final GlassSurface glass;
    private final String[] titles;
    private final boolean showIcons;
    private final TabItem[] tabs;
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
    private int gestureStartIndex;
    private long lastSnapshot;
    private boolean tracking;
    private boolean dragging;
    private boolean multiTouch;
    private boolean backdropDirty = true;
    private boolean snapshotEnabled = true;

    private int innerInset() { return dp(showIcons ? 6 : 4); }

    LiquidGlassNavigation(Context context) {
        this(context, new String[]{"加密", "转换", "日志"}, true);
    }

    LiquidGlassNavigation(Context context, String[] titles, boolean showIcons) {
        super(context);
        if (titles.length == 0) throw new IllegalArgumentException("至少需要一个菜单项");
        this.titles = titles.clone();
        this.showIcons = showIcons;
        tabs = new TabItem[titles.length];
        setClickable(true);
        setClipChildren(false);
        setClipToOutline(true);
        setElevation(dp(showIcons ? 10 : 3));
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
        glass = new GlassSurface(context);
        glass.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(glass, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout items = new LinearLayout(context);
        int inset = innerInset();
        items.setPadding(inset, inset, inset, inset);
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
        return showIcons ? Math.max(dp(68), dp(46) + Math.round(12 * getResources().getDisplayMetrics().scaledDensity))
                : Math.max(dp(52), dp(28) + Math.round(14 * getResources().getDisplayMetrics().scaledDensity));
    }

    static int contentBottomInset(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.max(Math.round(68 * metrics.density), Math.round(46 * metrics.density + 12 * metrics.scaledDensity))
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
        float width = Math.max(1, (getWidth() - innerInset() * 2) / (float) tabs.length);
        float position = Math.max(0, Math.min(tabs.length - 1, (x - innerInset()) / width - 0.5f));
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL ? tabs.length - 1 - position : position;
    }

    private void beginDrag() {
        if (!tracking || multiTouch || !isShown() || !hasWindowFocus()) return;
        removeCallbacks(longPress);
        if (selectionAnimation != null) selectionAnimation.cancel();
        dragging = true;
        AppLog.event(getContext(), showIcons ? "导航" : "日志筛选", "开始拖动菜单", "起始选项  " + titles[gestureStartIndex]);
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
        if (dragging && hovered != selectedIndex && listener != null) listener.accept(hovered);
    }

    private void endDrag(boolean commit) {
        int target = commit ? previewIndex : gestureStartIndex;
        AppLog.event(getContext(), showIcons ? "导航" : "日志筛选", commit ? "完成菜单拖动" : "取消菜单拖动",
                "目标选项  " + titles[target]);
        tracking = false;
        dragging = false;
        pointerId = -1;
        removeCallbacks(longPress);
        animate().cancel();
        if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(1).scaleY(1).setDuration(180).start();
        else { setScaleX(1); setScaleY(1); }
        previewIndex = target;
        if (listener != null && (commit || target != selectedIndex)) listener.accept(target);
        previewIndex = selectedIndex;
        updateTabStyles();
        if (selectionAnimation == null || !selectionAnimation.isRunning()) animateLens(selectedIndex);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }

    @Override public boolean performClick() {
        super.performClick();
        tabs[previewIndex].performClick();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            removeCallbacks(longPress);
            tracking = true;
            multiTouch = false;
            gestureStartIndex = selectedIndex;
            pointerId = event.getPointerId(0);
            downX = touchX = event.getX();
            downY = touchY = event.getY();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            removeCallbacks(longPress);
            if (dragging) endDrag(false);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (multiTouch) return true;
            int pointer = event.findPointerIndex(pointerId);
            if (pointer < 0 || !tracking) {
                if (dragging) endDrag(false);
                removeCallbacks(longPress);
                tracking = false;
                return true;
            }
            touchX = event.getX(pointer);
            touchY = event.getY(pointer);
            float horizontal = Math.abs(touchX - downX);
            float vertical = Math.abs(touchY - downY);
            if (!dragging && horizontal > touchSlop && horizontal > vertical) beginDrag();
            if (dragging) { moveLens(touchX); return true; }
            if (vertical > touchSlop * 2) {
                removeCallbacks(longPress);
                tracking = false;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
            boolean wasTracking = tracking;
            tracking = false;
            if (dragging) {
                boolean inside = event.getX() >= -dp(28) && event.getX() <= getWidth() + dp(28)
                        && event.getY() >= -dp(28) && event.getY() <= getHeight() + dp(28);
                if (action == MotionEvent.ACTION_UP && inside) moveLens(event.getX());
                endDrag(action == MotionEvent.ACTION_UP && inside && !multiTouch);
                return true;
            }
            if (multiTouch) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            if (action == MotionEvent.ACTION_UP && wasTracking && event.getX() >= 0 && event.getX() <= getWidth()
                    && event.getY() >= 0 && event.getY() <= getHeight()) {
                previewIndex = Math.round(positionAt(event.getX()));
                performClick();
            }
            pointerId = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    private void updateTabStyles() {
        for (int i = 0; i < tabs.length; i++) {
            TabItem item = tabs[i];
            if (item == null) continue;
            int color = i == previewIndex ? BLUE : GRAY;
            if (showIcons) {
                item.icon.color = color;
                item.icon.invalidate();
            }
            item.title.setTextColor(color);
            item.title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        if (backdropSource.getVisibility() != VISIBLE) {
            backdrop.setImageDrawable(null);
            return true;
        }
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
        if (width > 0 && height > 0 && Build.VERSION.SDK_INT >= 31) {
            RenderEffect blur = RenderEffect.createBlurEffect(dp(7), dp(7), Shader.TileMode.CLAMP);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    Refraction.apply(backdrop, width, height, dp(16), dp(14), dp(7), blur);
                } else backdrop.setRenderEffect(blur);
            } catch (RuntimeException error) {
                backdrop.setRenderEffect(blur);
                android.util.Log.w("DoroNavigation", "折射效果不可用，使用系统模糊", error);
            }
        }
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
                    new int[]{Color.argb(235, 247, 248, 250), Color.argb(185, 241, 243, 247),
                            Color.argb(220, 247, 248, 250)}, null, Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, 0, width, height,
                    new int[]{Color.WHITE, Color.argb(150, 255, 255, 255), Color.argb(58, 75, 95, 125),
                            Color.argb(210, 255, 255, 255)}, new float[]{0, 0.4f, 0.8f, 1}, Shader.TileMode.CLAMP);
            lensTint = new LinearGradient(0, innerInset(), 0, height - innerInset(),
                    new int[]{Color.argb(254, 255, 255, 255), Color.argb(235, 255, 255, 255),
                            Color.argb(225, 250, 252, 255)}, null, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            float inset = dp(0.7f);
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(tint);
            canvas.drawRoundRect(bounds, getHeight() / 2f, getHeight() / 2f, paint);
            float slot = (getWidth() - innerInset() * 2) / (float) tabs.length;
            float position = Math.max(-0.05f, Math.min(tabs.length - 0.95f, lensPosition));
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) position = tabs.length - 1 - position;
            float center = innerInset() + slot * (position + 0.5f);
            float halfWidth = (slot - dp(4)) * (1 + lensStretch) / 2;
            float halfHeight = (getHeight() - innerInset() * 2) * (1 - lensStretch * 0.4f) / 2;
            lens.set(center - halfWidth, getHeight() / 2f - halfHeight,
                    center + halfWidth, getHeight() / 2f + halfHeight);
            paint.setShader(null);
            paint.setColor(Color.argb(30, 80, 90, 110));
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
            setContentDescription(titles[index]);
            icon = showIcons ? new Icon(context, index) : null;
            if (icon != null) {
                icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(icon, new LinearLayout.LayoutParams(dp(24), dp(25)));
            }
            title = new TextView(context);
            title.setText(titles[index]);
            title.setTextSize(showIcons ? 11 : 14);
            title.setIncludeFontPadding(false);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-2, -2);
            label.topMargin = showIcons ? dp(3) : 0;
            addView(title, label);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setSelected(isSelected());
        }
    }

    /** Kept behind the API 33 check so older Android versions can load the control. */
    private static final class Refraction {
        private static final String SHADER = """
                uniform shader content;
                uniform float2 viewSize;
                uniform float2 halfSize;
                uniform float radius;
                uniform float rimWidth;
                uniform float strength;
                half4 main(float2 p) {
                    float2 local = p - viewSize * 0.5;
                    float2 inner = halfSize - float2(radius);
                    float2 delta = local - clamp(local, -inner, inner);
                    float distance = length(delta) - radius;
                    float edge = 1.0 - smoothstep(0.0, rimWidth, -distance);
                    float2 normal = delta / max(length(delta), 0.0001);
                    float2 shift = normal * strength * edge * edge;
                    half4 color = content.eval(p - shift);
                    half red = content.eval(p - shift * 1.06).r;
                    half blue = content.eval(p - shift * 0.94).b;
                    return half4(red, color.g, blue, color.a);
                }
                """;

        static void apply(ImageView view, int width, int height, int bleed, int rim, int strength, RenderEffect blur) {
            android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(SHADER);
            shader.setFloatUniform("viewSize", width + bleed * 2, height + bleed * 2);
            shader.setFloatUniform("halfSize", width / 2f, height / 2f);
            shader.setFloatUniform("radius", height / 2f);
            shader.setFloatUniform("rimWidth", rim);
            shader.setFloatUniform("strength", strength);
            view.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blur));
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
