/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.fuelgauge.batteryusage;

import static com.android.settings.Utils.formatPercentage;
import static com.android.settings.fuelgauge.batteryusage.BatteryChartViewModel.AxisLabelPosition.BETWEEN_TRAPEZOIDS;
import static com.android.settings.fuelgauge.batteryusage.BatteryChartViewModel.SELECTED_INDEX_ALL;
import static com.android.settings.fuelgauge.batteryusage.BatteryChartViewModel.SELECTED_INDEX_INVALID;
import static com.android.settingslib.fuelgauge.BatteryStatus.BATTERY_LEVEL_UNKNOWN;

import static java.lang.Math.abs;
import static java.lang.Math.round;
import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.widget.AppCompatImageView;

import com.android.settings.R;
import com.android.settingslib.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A widget component to draw chart graph. */
public class BatteryChartView extends AppCompatImageView implements View.OnClickListener {
    private static final String TAG = "BatteryChartView";

    private static final int DIVIDER_COLOR = Color.parseColor("#CDCCC5");
    private static final int HORIZONTAL_DIVIDER_COUNT = 5;

    /** A callback listener for selected group index is updated. */
    public interface OnSelectListener {
        /** The callback function for selected group index is updated. */
        void onSelect(int trapezoidIndex);

        /** The callback function for selected range is updated. */
        default void onSelectRange(int startIndex, int endIndex) {
            onSelect(startIndex);
        }
    }

    private final String[] mPercentages = getPercentages();
    private final Rect mIndent = new Rect();
    private final Rect[] mPercentageBounds = new Rect[] {new Rect(), new Rect(), new Rect()};
    private final List<Rect> mAxisLabelsBounds = new ArrayList<>();
    private final Set<Integer> mLabelDrawnIndexes = new ArraySet<>();
    private final int mLayoutDirection =
            getContext().getResources().getConfiguration().getLayoutDirection();

    private BatteryChartViewModel mViewModel;
    private int mHoveredIndex = SELECTED_INDEX_INVALID;
    private int mDividerWidth;
    private int mDividerHeight;
    private float mTrapezoidVOffset;
    private float mTrapezoidHOffset;
    private int mTrapezoidColor;
    private int mTrapezoidSolidColor;
    private int mTrapezoidHoverColor;
    private int mDefaultTextColor;
    private int mTextPadding;
    private int mTransomIconSize;
    private int mTransomTop;
    private int mTransomViewHeight;
    private int mTransomLineDefaultColor;
    private int mTransomLineSelectedColor;
    private float mTransomPadding;
    private Drawable mTransomIcon;
    private Paint mTransomLinePaint;
    private Paint mTransomSelectedSlotPaint;
    private Paint mDividerPaint;
    private Paint mTrapezoidPaint;
    private Paint mTextPaint;
    private Paint mCurveLinePaint;
    private Paint mCurveFillPaint;
    private Paint mChargingLinePaint;
    private Paint mChargingFillPaint;
    private Paint mSelectionPillPaint;
    private Paint mSelectionPillBorderPaint;
    private Paint mIndicatorDotPaint;
    private Paint mIndicatorDotBorderPaint;
    private Paint mHandleBarPaint;
    private Paint mHandleGripPaint;
    private Paint mHandleGripBorderPaint;
    private AccessibilityNodeProvider mAccessibilityNodeProvider;
    private BatteryChartView.OnSelectListener mOnSelectListener;

    private static final int DRAG_NONE = 0;
    private static final int DRAG_LEFT_HANDLE = 1;
    private static final int DRAG_RIGHT_HANDLE = 2;
    private static final int DRAG_WINDOW = 3;
    private static final int DRAG_NEW_SELECTION = 4;

    private int mTouchSlop;
    private int mDragMode = DRAG_NONE;
    private float mDownX;
    private float mDownY;
    private boolean mIsDragging = false;
    private int mDragStartSlot = SELECTED_INDEX_INVALID;
    private int mDragEndSlot = SELECTED_INDEX_INVALID;
    private int mInitialStartSlot = SELECTED_INDEX_INVALID;
    private int mInitialEndSlot = SELECTED_INDEX_INVALID;
    private int mInitialTouchSlot = SELECTED_INDEX_INVALID;

    @VisibleForTesting TrapezoidSlot[] mTrapezoidSlots;
    // Records the location to calculate selected index.
    @VisibleForTesting float mTouchUpEventX = Float.MIN_VALUE;

    public BatteryChartView(Context context) {
        super(context, null);
    }

    public BatteryChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initializeColors(context);
        // Registers the click event listener.
        setOnClickListener(this);
        setClickable(false);
        requestLayout();
    }

    /** Sets the data model of this view. */
    public void setViewModel(BatteryChartViewModel viewModel) {
        if (viewModel == null) {
            mViewModel = null;
            invalidate();
            return;
        }

        Log.d(
                TAG,
                String.format(
                        "setViewModel(): size: %d, selectedIndex: %d, getHighlightSlotIndex: %d",
                        viewModel.size(),
                        viewModel.selectedIndex(),
                        viewModel.getHighlightSlotIndex()));
        mViewModel = viewModel;
        initializeAxisLabelsBounds();
        initializeTrapezoidSlots(viewModel.size() - 1);
        setClickable(hasAnyValidTrapezoid(viewModel));
        requestLayout();
    }

    /** Sets the callback to monitor the selected group index. */
    public void setOnSelectListener(BatteryChartView.OnSelectListener listener) {
        mOnSelectListener = listener;
    }

    /** Sets the companion {@link TextView} for percentage information. */
    public void setCompanionTextView(TextView textView) {
        if (textView != null) {
            // Pre-draws the view first to load style atttributions into paint.
            textView.draw(new Canvas());
            mTextPaint = textView.getPaint();
            mDefaultTextColor = mTextPaint.getColor();
        } else {
            mTextPaint = null;
        }
        requestLayout();
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // Measures text bounds and updates indent configuration.
        if (mTextPaint != null) {
            mTextPaint.setTextAlign(Paint.Align.LEFT);
            for (int index = 0; index < mPercentages.length; index++) {
                mTextPaint.getTextBounds(
                        mPercentages[index],
                        0,
                        mPercentages[index].length(),
                        mPercentageBounds[index]);
            }
            // Updates the indent configurations.
            mIndent.top = mPercentageBounds[0].height() + mTransomViewHeight;
            final int textWidth = mPercentageBounds[0].width() + mTextPadding;
            if (isRTL()) {
                mIndent.left = textWidth;
            } else {
                mIndent.right = textWidth;
            }

            if (mViewModel != null) {
                int maxTop = 0;
                for (int index = 0; index < mViewModel.size(); index++) {
                    final String text = mViewModel.getText(index);
                    mTextPaint.getTextBounds(text, 0, text.length(), mAxisLabelsBounds.get(index));
                    maxTop = Math.max(maxTop, -mAxisLabelsBounds.get(index).top);
                }
                mIndent.bottom = maxTop + round(mTextPadding * 2f);
            }
            Log.d(TAG, "setIndent:" + mPercentageBounds[0]);
        } else {
            mIndent.set(0, 0, 0, 0);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        // Before mLevels initialized, the count of trapezoids is unknown. Only draws the
        // horizontal percentages and dividers.
        drawHorizontalDividers(canvas);
        if (mViewModel == null) {
            return;
        }
        drawVerticalDividers(canvas);
        drawTrapezoids(canvas);
        drawTransomLine(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mViewModel == null || mTrapezoidSlots == null || mTrapezoidSlots.length == 0) {
            return super.onTouchEvent(event);
        }

        final int action = event.getActionMasked();
        final float x = event.getX();
        final float y = event.getY();
        final int slotCount = mTrapezoidSlots.length;

        final int selectedIndex = mViewModel.selectedIndex();
        final int currentStart = mViewModel.getRangeStartIndex();
        final int currentEnd = mViewModel.getRangeEndIndex();
        final boolean hasSelection = (selectedIndex != SELECTED_INDEX_ALL);
        final int selStart = hasSelection
                ? (currentStart != SELECTED_INDEX_ALL ? currentStart : selectedIndex)
                : SELECTED_INDEX_ALL;
        final int selEnd = hasSelection
                ? (currentEnd != SELECTED_INDEX_ALL ? currentEnd : selectedIndex)
                : SELECTED_INDEX_ALL;

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = x;
                mDownY = y;
                mTouchUpEventX = x;
                mIsDragging = false;
                mDragMode = DRAG_NONE;

                final float density = getContext().getResources().getDisplayMetrics().density;
                final float handleHitRadius = density * 28f;

                if (hasSelection && selStart >= 0 && selEnd < slotCount) {
                    final int drawStart = isRTL() ? (slotCount - 1 - selEnd) : selStart;
                    final int drawEnd = isRTL() ? (slotCount - 1 - selStart) : selEnd;
                    final int minIdx = Math.min(drawStart, drawEnd);
                    final int maxIdx = Math.max(drawStart, drawEnd);
                    final float bandLeft = mTrapezoidSlots[minIdx].mLeft;
                    final float bandRight = mTrapezoidSlots[maxIdx].mRight;
                    final float width = bandRight - bandLeft;
                    final float edgeZone = Math.min(handleHitRadius, width * 0.35f);

                    final float distLeft = Math.abs(x - bandLeft);
                    final float distRight = Math.abs(x - bandRight);

                    if (distLeft <= edgeZone && distLeft <= distRight) {
                        mDragMode = isRTL() ? DRAG_RIGHT_HANDLE : DRAG_LEFT_HANDLE;
                    } else if (distRight <= edgeZone) {
                        mDragMode = isRTL() ? DRAG_LEFT_HANDLE : DRAG_RIGHT_HANDLE;
                    } else if (x >= bandLeft && x <= bandRight) {
                        mDragMode = DRAG_WINDOW;
                        mInitialTouchSlot = getTrapezoidIndexClamped(x);
                        mInitialStartSlot = selStart;
                        mInitialEndSlot = selEnd;
                    } else {
                        mDragMode = DRAG_NEW_SELECTION;
                        mInitialTouchSlot = getTrapezoidIndexClamped(x);
                    }
                } else {
                    mDragMode = DRAG_NEW_SELECTION;
                    mInitialTouchSlot = getTrapezoidIndexClamped(x);
                }

                mDragStartSlot = selStart;
                mDragEndSlot = selEnd;
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                final float deltaX = x - mDownX;

                if (!mIsDragging) {
                    if (Math.abs(deltaX) >= (mTouchSlop / 2)) {
                        mIsDragging = true;
                        ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }

                if (mIsDragging) {
                    final int currentSlot = getTrapezoidIndexClamped(x);
                    if (currentSlot == SELECTED_INDEX_INVALID) {
                        return true;
                    }

                    int newStart = mDragStartSlot;
                    int newEnd = mDragEndSlot;

                    switch (mDragMode) {
                        case DRAG_LEFT_HANDLE: {
                            newStart = Math.min(currentSlot, mDragEndSlot);
                            newEnd = mDragEndSlot;
                            break;
                        }
                        case DRAG_RIGHT_HANDLE: {
                            newStart = mDragStartSlot;
                            newEnd = Math.max(currentSlot, mDragStartSlot);
                            break;
                        }
                        case DRAG_WINDOW: {
                            final int slotDelta = currentSlot - mInitialTouchSlot;
                            final int windowLen = mInitialEndSlot - mInitialStartSlot;
                            newStart = mInitialStartSlot + slotDelta;
                            newEnd = newStart + windowLen;
                            if (newStart < 0) {
                                newStart = 0;
                                newEnd = newStart + windowLen;
                            }
                            if (newEnd >= slotCount) {
                                newEnd = slotCount - 1;
                                newStart = Math.max(0, newEnd - windowLen);
                            }
                            break;
                        }
                        case DRAG_NEW_SELECTION: {
                            newStart = Math.min(mInitialTouchSlot, currentSlot);
                            newEnd = Math.max(mInitialTouchSlot, currentSlot);
                            break;
                        }
                    }

                    if (newStart != mDragStartSlot || newEnd != mDragEndSlot) {
                        mDragStartSlot = newStart;
                        mDragEndSlot = newEnd;
                        updateSelection(mDragStartSlot, mDragEndSlot);
                        performHapticTick();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }

                if (!mIsDragging) {
                    final int tapSlot = getTrapezoidIndex(mDownX);
                    handleTap(tapSlot);
                }
                mDragMode = DRAG_NONE;
                mIsDragging = false;
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                mDragMode = DRAG_NONE;
                mIsDragging = false;
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        final int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                final int trapezoidIndex = getTrapezoidIndex(event.getX());
                if (mHoveredIndex != trapezoidIndex) {
                    mHoveredIndex = trapezoidIndex;
                    invalidate();
                    sendAccessibilityEventForHover(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
                }
                // Ignore the super.onHoverEvent() because the hovered trapezoid has already been
                // sent here.
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (mHoveredIndex != SELECTED_INDEX_INVALID) {
                    sendAccessibilityEventForHover(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    mHoveredIndex = SELECTED_INDEX_INVALID; // reset
                    invalidate();
                }
                // Ignore the super.onHoverEvent() because the hovered trapezoid has already been
                // sent here.
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public void onHoverChanged(boolean hovered) {
        super.onHoverChanged(hovered);
        if (!hovered) {
            mHoveredIndex = SELECTED_INDEX_INVALID; // reset
            invalidate();
        }
    }

    @Override
    public void onClick(View view) {
        // Handled directly inside onTouchEvent for instant feedback and smooth gestures
    }

    private void handleTap(int tapSlot) {
        if (tapSlot == SELECTED_INDEX_INVALID || !isValidToDraw(mViewModel, tapSlot)) {
            // Tapped outside or on invalid area -> deselect all
            updateSelection(SELECTED_INDEX_ALL, SELECTED_INDEX_ALL);
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            return;
        }

        final int selectedIndex = mViewModel.selectedIndex();
        final int currentStart = mViewModel.getRangeStartIndex();
        final int currentEnd = mViewModel.getRangeEndIndex();
        final boolean isRange = (currentStart != SELECTED_INDEX_ALL
                && currentEnd != SELECTED_INDEX_ALL
                && currentStart != currentEnd);

        if (selectedIndex == SELECTED_INDEX_ALL) {
            // Nothing was selected -> select tapped single slot!
            updateSelection(tapSlot, tapSlot);
        } else if (isRange) {
            if (tapSlot >= currentStart && tapSlot <= currentEnd) {
                // Tapped inside an existing range -> narrow down to this single tapped slot!
                updateSelection(tapSlot, tapSlot);
            } else {
                // Tapped outside the range -> select the new slot
                updateSelection(tapSlot, tapSlot);
            }
        } else {
            // Single slot was selected
            if (tapSlot == selectedIndex) {
                // Tapped the same slot -> deselect all (toggle)
                updateSelection(SELECTED_INDEX_ALL, SELECTED_INDEX_ALL);
            } else {
                // Tapped different slot -> select that slot
                updateSelection(tapSlot, tapSlot);
            }
        }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    private void updateSelection(int start, int end) {
        if (mOnSelectListener != null) {
            if (start == SELECTED_INDEX_ALL || end == SELECTED_INDEX_ALL) {
                mOnSelectListener.onSelect(SELECTED_INDEX_ALL);
            } else if (start == end) {
                mOnSelectListener.onSelect(start);
            } else {
                mOnSelectListener.onSelectRange(Math.min(start, end), Math.max(start, end));
            }
        }
        invalidate();
    }

    private void performHapticTick() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private boolean sendAccessibilityEvent(int virtualDescendantId, int eventType) {
        ViewParent parent = getParent();
        if (parent == null || !AccessibilityManager.getInstance(mContext).isEnabled()) {
            return false;
        }
        AccessibilityEvent accessibilityEvent = new AccessibilityEvent(eventType);
        accessibilityEvent.setSource(this, virtualDescendantId);
        accessibilityEvent.setEnabled(true);
        accessibilityEvent.setClassName(getAccessibilityClassName());
        accessibilityEvent.setPackageName(getContext().getPackageName());
        return parent.requestSendAccessibilityEvent(this, accessibilityEvent);
    }

    private void sendAccessibilityEventForHover(int eventType) {
        if (isTrapezoidIndexValid(mViewModel, mHoveredIndex)) {
            sendAccessibilityEvent(mHoveredIndex, eventType);
        }
    }

    private void initializeTrapezoidSlots(int count) {
        mTrapezoidSlots = new TrapezoidSlot[count];
        for (int index = 0; index < mTrapezoidSlots.length; index++) {
            mTrapezoidSlots[index] = new TrapezoidSlot();
        }
    }

    private void initializeColors(Context context) {
        setBackgroundColor(Color.TRANSPARENT);
        mTrapezoidSolidColor = Utils.getColorAccentDefaultColor(context);
        mTrapezoidColor = Utils.getDisabled(context, mTrapezoidSolidColor);
        mTrapezoidHoverColor =
                context.getColor(com.android.internal.R.color.materialColorSecondaryContainer);
        // Initializes the divider line paint.
        final Resources resources = getContext().getResources();
        mDividerWidth = resources.getDimensionPixelSize(R.dimen.chartview_divider_width);
        mDividerHeight = resources.getDimensionPixelSize(R.dimen.chartview_divider_height);
        mDividerPaint = new Paint();
        mDividerPaint.setAntiAlias(true);
        mDividerPaint.setColor(DIVIDER_COLOR);
        mDividerPaint.setStyle(Paint.Style.STROKE);
        mDividerPaint.setStrokeWidth(mDividerWidth);
        Log.i(TAG, "mDividerWidth:" + mDividerWidth);
        Log.i(TAG, "mDividerHeight:" + mDividerHeight);
        // Initializes the trapezoid paint.
        mTrapezoidHOffset = resources.getDimension(R.dimen.chartview_trapezoid_margin_start);
        mTrapezoidVOffset = resources.getDimension(R.dimen.chartview_trapezoid_margin_bottom);
        mTrapezoidPaint = new Paint();
        mTrapezoidPaint.setAntiAlias(true);
        mTrapezoidPaint.setColor(mTrapezoidSolidColor);
        mTrapezoidPaint.setStyle(Paint.Style.FILL);
        mTrapezoidPaint.setPathEffect(
                new CornerPathEffect(
                        resources.getDimensionPixelSize(R.dimen.chartview_trapezoid_radius)));
        // Initializes for drawing text information.
        mTextPadding = resources.getDimensionPixelSize(R.dimen.chartview_text_padding);
        // Initializes the padding top for drawing text information.
        mTransomViewHeight =
                resources.getDimensionPixelSize(R.dimen.chartview_transom_layout_height);

        final float density = resources.getDisplayMetrics().density;

        mCurveLinePaint = new Paint();
        mCurveLinePaint.setAntiAlias(true);
        mCurveLinePaint.setStyle(Paint.Style.STROKE);
        mCurveLinePaint.setStrokeWidth(density * 3.5f);
        mCurveLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mCurveLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mCurveLinePaint.setColor(mTrapezoidSolidColor);

        mCurveFillPaint = new Paint();
        mCurveFillPaint.setAntiAlias(true);
        mCurveFillPaint.setStyle(Paint.Style.FILL);

        mChargingLinePaint = new Paint();
        mChargingLinePaint.setAntiAlias(true);
        mChargingLinePaint.setStyle(Paint.Style.STROKE);
        mChargingLinePaint.setStrokeWidth(density * 4f);
        mChargingLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mChargingLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mChargingLinePaint.setColor(Color.parseColor("#25D366"));

        mChargingFillPaint = new Paint();
        mChargingFillPaint.setAntiAlias(true);
        mChargingFillPaint.setStyle(Paint.Style.FILL);

        mSelectionPillPaint = new Paint();
        mSelectionPillPaint.setAntiAlias(true);
        mSelectionPillPaint.setStyle(Paint.Style.FILL);
        mSelectionPillPaint.setColor(Color.argb(38, 255, 255, 255));

        mSelectionPillBorderPaint = new Paint();
        mSelectionPillBorderPaint.setAntiAlias(true);
        mSelectionPillBorderPaint.setStyle(Paint.Style.STROKE);
        mSelectionPillBorderPaint.setStrokeWidth(density * 1.5f);
        mSelectionPillBorderPaint.setColor(Color.argb(75, 255, 255, 255));

        mIndicatorDotPaint = new Paint();
        mIndicatorDotPaint.setAntiAlias(true);
        mIndicatorDotPaint.setStyle(Paint.Style.FILL);
        mIndicatorDotPaint.setColor(Color.WHITE);

        mIndicatorDotBorderPaint = new Paint();
        mIndicatorDotBorderPaint.setAntiAlias(true);
        mIndicatorDotBorderPaint.setStyle(Paint.Style.STROKE);
        mIndicatorDotBorderPaint.setStrokeWidth(density * 2.5f);
        mIndicatorDotBorderPaint.setColor(mTrapezoidSolidColor);

        mHandleBarPaint = new Paint();
        mHandleBarPaint.setAntiAlias(true);
        mHandleBarPaint.setStyle(Paint.Style.STROKE);
        mHandleBarPaint.setStrokeCap(Paint.Cap.ROUND);
        mHandleBarPaint.setColor(Color.WHITE);

        mHandleGripPaint = new Paint();
        mHandleGripPaint.setAntiAlias(true);
        mHandleGripPaint.setStyle(Paint.Style.FILL);
        mHandleGripPaint.setColor(Color.WHITE);

        mHandleGripBorderPaint = new Paint();
        mHandleGripBorderPaint.setAntiAlias(true);
        mHandleGripBorderPaint.setStyle(Paint.Style.STROKE);
        mHandleGripBorderPaint.setColor(mTrapezoidSolidColor);

        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    private void initializeTransomPaint() {
        if (mTransomLinePaint != null
                && mTransomSelectedSlotPaint != null
                && mTransomIcon != null) {
            return;
        }
        // Initializes the transom line paint.
        final Resources resources = getContext().getResources();
        final int transomLineWidth =
                resources.getDimensionPixelSize(R.dimen.chartview_transom_width);
        final int transomRadius = resources.getDimensionPixelSize(R.dimen.chartview_transom_radius);
        mTransomPadding = transomRadius * .5f;
        mTransomTop = resources.getDimensionPixelSize(R.dimen.chartview_transom_padding_top);
        mTransomLineDefaultColor = Utils.getDisabled(mContext, DIVIDER_COLOR);
        mTransomLineSelectedColor =
                resources.getColor(
                        com.android.settingslib.widget.preference.banner.R.color
                                .settingslib_banner_button_background_medium);
        final int slotHighlightColor = Utils.getDisabled(mContext, mTransomLineSelectedColor);
        mTransomIconSize = resources.getDimensionPixelSize(R.dimen.chartview_transom_icon_size);
        mTransomLinePaint = new Paint();
        mTransomLinePaint.setAntiAlias(true);
        mTransomLinePaint.setStyle(Paint.Style.STROKE);
        mTransomLinePaint.setStrokeWidth(transomLineWidth);
        mTransomLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mTransomLinePaint.setPathEffect(new CornerPathEffect(transomRadius));
        mTransomSelectedSlotPaint = new Paint();
        mTransomSelectedSlotPaint.setAntiAlias(true);
        mTransomSelectedSlotPaint.setColor(slotHighlightColor);
        mTransomSelectedSlotPaint.setStyle(Paint.Style.FILL);
        // Get the companion icon beside transom line
        mTransomIcon = getResources().getDrawable(R.drawable.ic_battery_tips_warning_icon);
    }

    private void drawHorizontalDividers(Canvas canvas) {
        final int width = getWidth() - abs(mIndent.width());
        final int height = getHeight() - mIndent.top - mIndent.bottom;
        final float topOffsetY = mIndent.top + mDividerWidth * .5f;
        final float bottomOffsetY = mIndent.top + (height - mDividerHeight - mDividerWidth * .5f);
        final float availableSpace = bottomOffsetY - topOffsetY;

        mDividerPaint.setColor(DIVIDER_COLOR);
        final float dividerOffsetUnit = availableSpace / (float) (HORIZONTAL_DIVIDER_COUNT - 1);

        // Draws 5 divider lines.
        for (int index = 0; index < HORIZONTAL_DIVIDER_COUNT; index++) {
            float offsetY = topOffsetY + dividerOffsetUnit * index;
            canvas.drawLine(mIndent.left, offsetY, mIndent.left + width, offsetY, mDividerPaint);

            //  Draws percentage text only for 100% / 50% / 0%
            if (index % 2 == 0) {
                drawPercentage(canvas, /* index= */ (index + 1) / 2, offsetY);
            }
        }
    }

    private void drawPercentage(Canvas canvas, int index, float offsetY) {
        if (mTextPaint != null) {
            mTextPaint.setTextAlign(isRTL() ? Paint.Align.RIGHT : Paint.Align.LEFT);
            mTextPaint.setColor(mDefaultTextColor);
            canvas.drawText(
                    mPercentages[index],
                    isRTL()
                            ? mIndent.left - mTextPadding
                            : getWidth() - mIndent.width() + mTextPadding,
                    offsetY + mPercentageBounds[index].height() * .5f,
                    mTextPaint);
        }
    }

    private void drawVerticalDividers(Canvas canvas) {
        final int width = getWidth() - abs(mIndent.width());
        final int dividerCount = mTrapezoidSlots.length + 1;
        final float dividerSpace = dividerCount * mDividerWidth;
        final float unitWidth = (width - dividerSpace) / (float) mTrapezoidSlots.length;
        final float bottomY = getHeight() - mIndent.bottom;
        final float startY = bottomY - mDividerHeight;
        final float trapezoidSlotOffset = mTrapezoidHOffset + mDividerWidth * .5f;
        // Draws the axis label slot information.
        if (mViewModel != null) {
            final float baselineY = getHeight() - mTextPadding;
            Rect[] axisLabelDisplayAreas;
            switch (mViewModel.axisLabelPosition()) {
                case CENTER_OF_TRAPEZOIDS:
                    axisLabelDisplayAreas =
                            getAxisLabelDisplayAreas(
                                    /* size= */ mViewModel.size() - 1,
                                    /* baselineX= */ mIndent.left + mDividerWidth + unitWidth * .5f,
                                    /* offsetX= */ mDividerWidth + unitWidth,
                                    baselineY,
                                    /* shiftFirstAndLast= */ false);
                    break;
                case BETWEEN_TRAPEZOIDS:
                default:
                    axisLabelDisplayAreas =
                            getAxisLabelDisplayAreas(
                                    /* size= */ mViewModel.size(),
                                    /* baselineX= */ mIndent.left + mDividerWidth * .5f,
                                    /* offsetX= */ mDividerWidth + unitWidth,
                                    baselineY,
                                    /* shiftFirstAndLast= */ true);
                    break;
            }
            drawAxisLabels(canvas, axisLabelDisplayAreas, baselineY);
        }
        // Draws each vertical dividers.
        float startX = mDividerWidth * .5f + mIndent.left;
        for (int index = 0; index < dividerCount; index++) {
            float dividerY = bottomY;
            if (mViewModel.axisLabelPosition() == BETWEEN_TRAPEZOIDS
                    && mLabelDrawnIndexes.contains(index)) {
                mDividerPaint.setColor(mTrapezoidSolidColor);
                dividerY += mDividerHeight / 4f;
            } else {
                mDividerPaint.setColor(DIVIDER_COLOR);
            }
            canvas.drawLine(startX, startY, startX, dividerY, mDividerPaint);
            final float nextX = startX + mDividerWidth + unitWidth;
            // Updates the trapezoid slots for drawing.
            if (index < mTrapezoidSlots.length) {
                final int trapezoidIndex = isRTL() ? mTrapezoidSlots.length - index - 1 : index;
                mTrapezoidSlots[trapezoidIndex].mLeft = round(startX + trapezoidSlotOffset);
                mTrapezoidSlots[trapezoidIndex].mRight = round(nextX - trapezoidSlotOffset);
            }
            startX = nextX;
        }
    }

    /** Gets all the axis label texts displaying area positions if they are shown. */
    private Rect[] getAxisLabelDisplayAreas(
            final int size,
            final float baselineX,
            final float offsetX,
            final float baselineY,
            final boolean shiftFirstAndLast) {
        final Rect[] result = new Rect[size];
        for (int index = 0; index < result.length; index++) {
            final float width = mAxisLabelsBounds.get(index).width();
            float middle = baselineX + index * offsetX;
            if (shiftFirstAndLast) {
                if (index == 0) {
                    middle += width * .5f;
                }
                if (index == size - 1) {
                    middle -= width * .5f;
                }
            }
            final float left = middle - width * .5f;
            final float right = left + width;
            final float top = baselineY + mAxisLabelsBounds.get(index).top;
            final float bottom = top + mAxisLabelsBounds.get(index).height();
            result[index] = new Rect(round(left), round(top), round(right), round(bottom));
        }
        return result;
    }

    private void drawAxisLabels(Canvas canvas, final Rect[] displayAreas, final float baselineY) {
        final int lastIndex = displayAreas.length - 1;
        mLabelDrawnIndexes.clear();
        // Suppose first and last labels are always able to draw.
        drawAxisLabelText(canvas, 0, displayAreas[0], baselineY);
        mLabelDrawnIndexes.add(0);
        drawAxisLabelText(canvas, lastIndex, displayAreas[lastIndex], baselineY);
        mLabelDrawnIndexes.add(lastIndex);
        drawAxisLabelsBetweenStartIndexAndEndIndex(canvas, displayAreas, 0, lastIndex, baselineY);
    }

    /**
     * Recursively draws axis labels between the start index and the end index. If the inner number
     * can be exactly divided into 2 parts, check and draw the middle index label and then
     * recursively draw the 2 parts. Otherwise, divide into 3 parts. Check and draw the middle two
     * labels and then recursively draw the 3 parts. If there are any overlaps, skip drawing and go
     * back to the uplevel of the recursion.
     */
    private void drawAxisLabelsBetweenStartIndexAndEndIndex(
            Canvas canvas,
            final Rect[] displayAreas,
            final int startIndex,
            final int endIndex,
            final float baselineY) {
        if (endIndex - startIndex <= 1) {
            return;
        }
        if ((endIndex - startIndex) % 2 == 0) {
            int middleIndex = (startIndex + endIndex) / 2;
            if (hasOverlap(displayAreas, startIndex, middleIndex)
                    || hasOverlap(displayAreas, middleIndex, endIndex)) {
                return;
            }
            drawAxisLabelText(canvas, middleIndex, displayAreas[middleIndex], baselineY);
            mLabelDrawnIndexes.add(middleIndex);
            drawAxisLabelsBetweenStartIndexAndEndIndex(
                    canvas, displayAreas, startIndex, middleIndex, baselineY);
            drawAxisLabelsBetweenStartIndexAndEndIndex(
                    canvas, displayAreas, middleIndex, endIndex, baselineY);
        } else {
            int middleIndex1 = startIndex + round((endIndex - startIndex) / 3f);
            int middleIndex2 = startIndex + round((endIndex - startIndex) * 2 / 3f);
            if (hasOverlap(displayAreas, startIndex, middleIndex1)
                    || hasOverlap(displayAreas, middleIndex1, middleIndex2)
                    || hasOverlap(displayAreas, middleIndex2, endIndex)) {
                return;
            }
            drawAxisLabelText(canvas, middleIndex1, displayAreas[middleIndex1], baselineY);
            mLabelDrawnIndexes.add(middleIndex1);
            drawAxisLabelText(canvas, middleIndex2, displayAreas[middleIndex2], baselineY);
            mLabelDrawnIndexes.add(middleIndex2);
            drawAxisLabelsBetweenStartIndexAndEndIndex(
                    canvas, displayAreas, startIndex, middleIndex1, baselineY);
            drawAxisLabelsBetweenStartIndexAndEndIndex(
                    canvas, displayAreas, middleIndex1, middleIndex2, baselineY);
            drawAxisLabelsBetweenStartIndexAndEndIndex(
                    canvas, displayAreas, middleIndex2, endIndex, baselineY);
        }
    }

    private boolean hasOverlap(
            final Rect[] displayAreas, final int leftIndex, final int rightIndex) {
        return displayAreas[leftIndex].right + mTextPadding * 2.3f > displayAreas[rightIndex].left;
    }

    private boolean isRTL() {
        return mLayoutDirection == View.LAYOUT_DIRECTION_RTL;
    }

    private void drawAxisLabelText(
            Canvas canvas, int index, final Rect displayArea, final float baselineY) {
        mTextPaint.setColor(mTrapezoidSolidColor);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        // Reverse the sort of axis labels for RTL
        if (isRTL()) {
            index =
                    mViewModel.axisLabelPosition() == BETWEEN_TRAPEZOIDS
                            ? mViewModel.size() - index - 1 // for hourly
                            : mViewModel.size() - index - 2; // for daily
        }
        canvas.drawText(mViewModel.getText(index), displayArea.centerX(), baselineY, mTextPaint);
        mLabelDrawnIndexes.add(index);
    }

    private void drawChargingMarker(Canvas canvas, float x, float y, boolean isSelected) {
        final float density = getContext().getResources().getDisplayMetrics().density;
        final float radius = density * 3.5f;
        mIndicatorDotBorderPaint.setColor(Color.parseColor("#25D366"));
        mIndicatorDotBorderPaint.setStrokeWidth(density * 1.5f);
        mIndicatorDotBorderPaint.setAlpha(isSelected ? 255 : 90);
        mIndicatorDotPaint.setAlpha(isSelected ? 255 : 90);
        canvas.drawCircle(x, y, radius + 2f, mIndicatorDotBorderPaint);
        canvas.drawCircle(x, y, radius, mIndicatorDotPaint);
    }

    private void drawHandle(Canvas canvas, float x, float top, float bottom) {
        final float density = getContext().getResources().getDisplayMetrics().density;
        // 1. Vertical glowing handle bar
        mHandleBarPaint.setColor(Color.WHITE);
        mHandleBarPaint.setStrokeWidth(density * 3.5f);
        mHandleBarPaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x, top + density * 4f, x, bottom - density * 4f, mHandleBarPaint);

        // 2. Circular grip handle in center
        final float centerY = (top + bottom) * 0.5f;
        final float gripRadius = density * 6.5f;
        mHandleGripBorderPaint.setColor(mTrapezoidSolidColor);
        mHandleGripBorderPaint.setStrokeWidth(density * 2.5f);
        canvas.drawCircle(x, centerY, gripRadius + density * 1.5f, mHandleGripBorderPaint);
        canvas.drawCircle(x, centerY, gripRadius, mHandleGripPaint);

        // 3. Two subtle vertical grip ridges
        final Paint ridgePaint = mIndicatorDotBorderPaint;
        ridgePaint.setColor(Color.parseColor("#9E9E9E"));
        ridgePaint.setStrokeWidth(density * 1.2f);
        canvas.drawLine(x - density * 1.5f, centerY - density * 3f, x - density * 1.5f, centerY + density * 3f, ridgePaint);
        canvas.drawLine(x + density * 1.5f, centerY - density * 3f, x + density * 1.5f, centerY + density * 3f, ridgePaint);
    }

    private void drawTrapezoids(Canvas canvas) {
        if (mViewModel == null || mTrapezoidSlots == null || mTrapezoidSlots.length == 0) {
            return;
        }
        final float trapezoidBottom =
                getHeight() - mIndent.bottom - mDividerHeight - mDividerWidth - mTrapezoidVOffset;
        final float availableSpace =
                trapezoidBottom - mDividerWidth * .5f - mIndent.top - mTrapezoidVOffset;
        final float unitHeight = availableSpace / 100f;

        final int slotCount = mTrapezoidSlots.length;
        final int selectedIndex = mViewModel.selectedIndex();
        final int rangeStart = mViewModel.getRangeStartIndex();
        final int rangeEnd = mViewModel.getRangeEndIndex();
        final boolean hasSelection = (selectedIndex != SELECTED_INDEX_ALL);
        final int selStart = hasSelection
                ? (rangeStart != SELECTED_INDEX_ALL ? rangeStart : selectedIndex)
                : SELECTED_INDEX_ALL;
        final int selEnd = hasSelection
                ? (rangeEnd != SELECTED_INDEX_ALL ? rangeEnd : selectedIndex)
                : SELECTED_INDEX_ALL;

        // 1. Draw Selection Highlight Band & Handles if an interval is selected
        if (hasSelection && selStart >= 0 && selEnd < slotCount) {
            final int drawStart = isRTL() ? (slotCount - 1 - selEnd) : selStart;
            final int drawEnd = isRTL() ? (slotCount - 1 - selStart) : selEnd;
            final int minIdx = Math.min(drawStart, drawEnd);
            final int maxIdx = Math.max(drawStart, drawEnd);
            final float bandLeft = mTrapezoidSlots[minIdx].mLeft;
            final float bandRight = mTrapezoidSlots[maxIdx].mRight;
            final float cornerRadius = getContext().getResources().getDimension(R.dimen.chartview_trapezoid_radius);
            final RectF selRect = new RectF(bandLeft, mIndent.top, bandRight, trapezoidBottom);
            canvas.drawRoundRect(selRect, cornerRadius, cornerRadius, mSelectionPillPaint);
            canvas.drawRoundRect(selRect, cornerRadius, cornerRadius, mSelectionPillBorderPaint);

            // Draw Left and Right drag handles
            drawHandle(canvas, bandLeft, mIndent.top, trapezoidBottom);
            drawHandle(canvas, bandRight, mIndent.top, trapezoidBottom);
        }

        // 2. Compute coordinate points and interpolate any missing (-1) levels
        final float[] pointX = new float[slotCount + 1];
        final float[] pointY = new float[slotCount + 1];
        final boolean[] validPoint = new boolean[slotCount + 1];
        final float[] effectiveLevel = new float[slotCount + 1];
        final boolean[] hasLevel = new boolean[slotCount + 1];

        for (int i = 0; i <= slotCount; i++) {
            if (i == 0) {
                pointX[i] = mTrapezoidSlots[0].mLeft;
            } else if (i == slotCount) {
                pointX[i] = mTrapezoidSlots[slotCount - 1].mRight;
            } else {
                pointX[i] = (mTrapezoidSlots[i - 1].mRight + mTrapezoidSlots[i].mLeft) * 0.5f;
            }

            final int levelIndex = isRTL() ? (slotCount - i) : i;
            final Integer level = mViewModel.getLevel(levelIndex);
            if (level != null && level != BATTERY_LEVEL_UNKNOWN && level >= 0) {
                effectiveLevel[i] = level;
                hasLevel[i] = true;
            }
        }

        // Find range of known data points
        int firstKnown = -1;
        int lastKnown = -1;
        for (int i = 0; i <= slotCount; i++) {
            if (hasLevel[i]) {
                if (firstKnown == -1) firstKnown = i;
                lastKnown = i;
            }
        }

        if (firstKnown == -1) {
            return;
        }

        // Interpolate any gaps between firstKnown and lastKnown
        int prevKnown = firstKnown;
        for (int i = firstKnown + 1; i <= lastKnown; i++) {
            if (hasLevel[i]) {
                if (i > prevKnown + 1) {
                    final float startLvl = effectiveLevel[prevKnown];
                    final float endLvl = effectiveLevel[i];
                    final int steps = i - prevKnown;
                    for (int k = prevKnown + 1; k < i; k++) {
                        final float fraction = (float) (k - prevKnown) / (float) steps;
                        effectiveLevel[k] = startLvl + fraction * (endLvl - startLvl);
                        hasLevel[k] = true;
                    }
                }
                prevKnown = i;
            }
        }

        // Compute pointY for all points in range
        for (int i = firstKnown; i <= lastKnown; i++) {
            pointY[i] = Math.max(mIndent.top, Math.min(trapezoidBottom, trapezoidBottom - effectiveLevel[i] * unitHeight));
            validPoint[i] = true;
        }

        // 3. Prepare gradients
        final int accentColor = mTrapezoidSolidColor;
        final int chargingGreen = Color.parseColor("#25D366");
        final int topAccentFill = Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
        final int topChargingFill = Color.argb(135, Color.red(chargingGreen), Color.green(chargingGreen), Color.blue(chargingGreen));

        final LinearGradient accentShader = new LinearGradient(
                0, mIndent.top, 0, trapezoidBottom,
                topAccentFill, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        final LinearGradient chargingShader = new LinearGradient(
                0, mIndent.top, 0, trapezoidBottom,
                topChargingFill, Color.TRANSPARENT, Shader.TileMode.CLAMP);

        mCurveFillPaint.setShader(accentShader);
        mChargingFillPaint.setShader(chargingShader);

        // 4. For each segment, draw smooth cubic curve and gradient fill
        final Path curvePath = new Path();
        final Path fillPath = new Path();

        for (int i = firstKnown; i < lastKnown; i++) {
            final int p0 = i;
            final int p1 = i + 1;

            final float x0 = pointX[p0];
            final float y0 = pointY[p0];
            final float x1 = pointX[p1];
            final float y1 = pointY[p1];

            final int slotIdx = isRTL() ? (slotCount - 1 - i) : i;

            final float startLvl = isRTL() ? effectiveLevel[p1] : effectiveLevel[p0];
            final float endLvl = isRTL() ? effectiveLevel[p0] : effectiveLevel[p1];
            final boolean isCharging = (endLvl > startLvl) || mViewModel.isSlotCharging(slotIdx);

            final boolean isSegmentSelected = (!hasSelection) || (slotIdx >= selStart && slotIdx <= selEnd);
            final int alpha = isSegmentSelected ? 255 : 75;

            // Cubic Bezier curve control points
            final float cpx1 = x0 + (x1 - x0) * 0.5f;
            final float cpy1 = y0;
            final float cpx2 = x0 + (x1 - x0) * 0.5f;
            final float cpy2 = y1;

            // Draw Area Fill under this segment
            fillPath.reset();
            fillPath.moveTo(x0, trapezoidBottom);
            fillPath.lineTo(x0, y0);
            fillPath.cubicTo(cpx1, cpy1, cpx2, cpy2, x1, y1);
            fillPath.lineTo(x1, trapezoidBottom);
            fillPath.close();

            final Paint fillPaint = isCharging ? mChargingFillPaint : mCurveFillPaint;
            fillPaint.setAlpha(alpha);
            canvas.drawPath(fillPath, fillPaint);

            // Draw Curve Stroke
            curvePath.reset();
            curvePath.moveTo(x0, y0);
            curvePath.cubicTo(cpx1, cpy1, cpx2, cpy2, x1, y1);

            final Paint linePaint = isCharging ? mChargingLinePaint : mCurveLinePaint;
            linePaint.setAlpha(alpha);
            canvas.drawPath(curvePath, linePaint);

            // Draw charging marker at midpoint
            if (isCharging) {
                final float midX = (x0 + x1) * 0.5f;
                final float midY = (y0 + y1) * 0.5f;
                drawChargingMarker(canvas, midX, midY, isSegmentSelected);
            }
        }

        // 5. Draw glowing indicator dot on selected point or current live level
        final int dotPointIdx = hasSelection
                ? (isRTL() ? (slotCount - 1 - selEnd) : (selEnd + 1))
                : lastKnown;
        if (dotPointIdx >= 0 && dotPointIdx <= slotCount && validPoint[dotPointIdx]) {
            final float dotX = pointX[dotPointIdx];
            final float dotY = pointY[dotPointIdx];
            final float dotRadius = getContext().getResources().getDisplayMetrics().density * 4.5f;
            final boolean isDotCharging = hasSelection
                    ? mViewModel.isSlotCharging(selEnd)
                    : (lastKnown > 0 && effectiveLevel[lastKnown] >= effectiveLevel[lastKnown - 1]);
            mIndicatorDotBorderPaint.setColor(isDotCharging ? chargingGreen : accentColor);
            mIndicatorDotBorderPaint.setAlpha(255);
            mIndicatorDotPaint.setAlpha(255);
            canvas.drawCircle(dotX, dotY, dotRadius + 2f, mIndicatorDotBorderPaint);
            canvas.drawCircle(dotX, dotY, dotRadius, mIndicatorDotPaint);
        }
    }

    private boolean isHighlightSlotValid() {
        return mViewModel != null && mViewModel.getHighlightSlotIndex() != SELECTED_INDEX_INVALID;
    }

    private void drawTransomLine(Canvas canvas) {
        if (!isHighlightSlotValid()) {
            return;
        }
        initializeTransomPaint();
        // Draw the whole transom line and a warning icon
        mTransomLinePaint.setColor(mTransomLineDefaultColor);
        final int width = getWidth() - abs(mIndent.width());
        final float transomOffset = mTrapezoidHOffset + mDividerWidth * .5f + mTransomPadding;
        final float trapezoidBottom =
                getHeight() - mIndent.bottom - mDividerHeight - mDividerWidth - mTrapezoidVOffset;
        canvas.drawLine(
                mIndent.left + transomOffset,
                mTransomTop,
                mIndent.left + width - transomOffset,
                mTransomTop,
                mTransomLinePaint);
        drawTransomIcon(canvas);
        // Draw selected segment of transom line and a highlight slot
        mTransomLinePaint.setColor(mTransomLineSelectedColor);
        final int index = mViewModel.getHighlightSlotIndex();
        final float startX = mTrapezoidSlots[index].mLeft;
        final float endX = mTrapezoidSlots[index].mRight;
        canvas.drawLine(
                startX + mTransomPadding,
                mTransomTop,
                endX - mTransomPadding,
                mTransomTop,
                mTransomLinePaint);
        canvas.drawRect(startX, mTransomTop, endX, trapezoidBottom, mTransomSelectedSlotPaint);
    }

    private void drawTransomIcon(Canvas canvas) {
        if (mTransomIcon == null) {
            return;
        }
        final int left =
                isRTL()
                        ? mIndent.left - mTextPadding - mTransomIconSize
                        : getWidth() - abs(mIndent.width()) + mTextPadding;
        mTransomIcon.setBounds(
                left,
                mTransomTop - mTransomIconSize / 2,
                left + mTransomIconSize,
                mTransomTop + mTransomIconSize / 2);
        mTransomIcon.draw(canvas);
    }

    // Searches the corresponding trapezoid index from x location.
    private int getTrapezoidIndex(float x) {
        if (mTrapezoidSlots == null) {
            return SELECTED_INDEX_INVALID;
        }
        for (int index = 0; index < mTrapezoidSlots.length; index++) {
            final TrapezoidSlot slot = mTrapezoidSlots[index];
            if (x >= slot.mLeft - mTrapezoidHOffset && x <= slot.mRight + mTrapezoidHOffset) {
                return index;
            }
        }
        return SELECTED_INDEX_INVALID;
    }

    private int getTrapezoidIndexClamped(float x) {
        if (mTrapezoidSlots == null || mTrapezoidSlots.length == 0) {
            return SELECTED_INDEX_INVALID;
        }
        final int count = mTrapezoidSlots.length;
        if (isRTL()) {
            if (x >= mTrapezoidSlots[0].mRight) {
                return 0;
            }
            if (x <= mTrapezoidSlots[count - 1].mLeft) {
                return count - 1;
            }
        } else {
            if (x <= mTrapezoidSlots[0].mLeft) {
                return 0;
            }
            if (x >= mTrapezoidSlots[count - 1].mRight) {
                return count - 1;
            }
        }
        for (int index = 0; index < count; index++) {
            final TrapezoidSlot slot = mTrapezoidSlots[index];
            if (x >= slot.mLeft - mTrapezoidHOffset && x <= slot.mRight + mTrapezoidHOffset) {
                return index;
            }
        }
        int closestIndex = 0;
        float minDistance = Float.MAX_VALUE;
        for (int index = 0; index < count; index++) {
            final TrapezoidSlot slot = mTrapezoidSlots[index];
            final float midX = (slot.mLeft + slot.mRight) * 0.5f;
            final float dist = Math.abs(x - midX);
            if (dist < minDistance) {
                minDistance = dist;
                closestIndex = index;
            }
        }
        return closestIndex;
    }

    private void initializeAxisLabelsBounds() {
        mAxisLabelsBounds.clear();
        for (int i = 0; i < mViewModel.size(); i++) {
            mAxisLabelsBounds.add(new Rect());
        }
    }

    private static boolean isTrapezoidValid(
            @NonNull BatteryChartViewModel viewModel, int trapezoidIndex) {
        if (!isTrapezoidIndexValid(viewModel, trapezoidIndex)) {
            return false;
        }
        int lastKnown = -1;
        for (int i = 0; i < viewModel.size(); i++) {
            final Integer lvl = viewModel.getLevel(i);
            if (lvl != null && lvl != BATTERY_LEVEL_UNKNOWN && lvl >= 0) {
                lastKnown = i;
            }
        }
        return lastKnown != -1 && trapezoidIndex < lastKnown;
    }

    private static boolean isTrapezoidIndexValid(
            @NonNull BatteryChartViewModel viewModel, int trapezoidIndex) {
        return viewModel != null && trapezoidIndex >= 0 && trapezoidIndex < viewModel.size() - 1;
    }

    private static boolean isValidToDraw(BatteryChartViewModel viewModel, int trapezoidIndex) {
        return isTrapezoidIndexValid(viewModel, trapezoidIndex)
                && isTrapezoidValid(viewModel, trapezoidIndex);
    }

    private static boolean hasAnyValidTrapezoid(@NonNull BatteryChartViewModel viewModel) {
        for (int i = 0; i < viewModel.size(); i++) {
            final Integer lvl = viewModel.getLevel(i);
            if (lvl != null && lvl != BATTERY_LEVEL_UNKNOWN && lvl >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String[] getPercentages() {
        return new String[] {
            formatPercentage(/* percentage= */ 100, /* round= */ true),
            formatPercentage(/* percentage= */ 50, /* round= */ true),
            formatPercentage(/* percentage= */ 0, /* round= */ true)
        };
    }

    private class BatteryChartAccessibilityNodeProvider extends AccessibilityNodeProvider {
        private static final int UNDEFINED = Integer.MIN_VALUE;

        private int mAccessibilityFocusNodeViewId = UNDEFINED;

        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                final AccessibilityNodeInfo hostInfo =
                        new AccessibilityNodeInfo(BatteryChartView.this);
                for (int index = 0; index < mViewModel.size() - 1; index++) {
                    hostInfo.addChild(BatteryChartView.this, index);
                }
                return hostInfo;
            }
            final int index = virtualViewId;
            if (!isTrapezoidIndexValid(mViewModel, index)) {
                Log.w(TAG, "Invalid virtual view id:" + index);
                return null;
            }
            final AccessibilityNodeInfo childInfo =
                    new AccessibilityNodeInfo(BatteryChartView.this, index);
            final String slotTimeInfo = mViewModel.getContentDescription(index);
            final String batteryLevelInfo = mViewModel.getSlotBatteryLevelText(index);
            onInitializeAccessibilityNodeInfo(childInfo);
            childInfo.setClickable(isValidToDraw(mViewModel, index));
            childInfo.setText(slotTimeInfo);
            childInfo.setContentDescription(
                    mContext.getString(
                            R.string.battery_usage_status_time_info_and_battery_level,
                            mContext.getString(
                                    mViewModel.selectedIndex() == virtualViewId
                                            ? R.string.battery_chart_slot_status_selected
                                            : R.string.battery_chart_slot_status_unselected),
                            slotTimeInfo,
                            batteryLevelInfo));
            childInfo.setAccessibilityFocused(virtualViewId == mAccessibilityFocusNodeViewId);

            final Rect bounds = new Rect();
            getBoundsOnScreen(bounds, true);
            final int hostLeft = bounds.left;
            bounds.left = round(hostLeft + mTrapezoidSlots[index].mLeft);
            bounds.right = round(hostLeft + mTrapezoidSlots[index].mRight);
            childInfo.setBoundsInScreen(bounds);
            return childInfo;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, @Nullable Bundle arguments) {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                return performAccessibilityAction(action, arguments);
            }
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    handleTap(virtualViewId);
                    return true;

                case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                    mAccessibilityFocusNodeViewId = virtualViewId;
                    return sendAccessibilityEvent(
                            virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);

                case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (mAccessibilityFocusNodeViewId == virtualViewId) {
                        mAccessibilityFocusNodeViewId = UNDEFINED;
                    }
                    return sendAccessibilityEvent(
                            virtualViewId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);

                default:
                    return performAccessibilityAction(action, arguments);
            }
        }
    }

    // A container class for each trapezoid left and right location.
    @VisibleForTesting
    static final class TrapezoidSlot {
        public float mLeft;
        public float mRight;

        @Override
        public String toString() {
            return String.format(Locale.US, "TrapezoidSlot[%f,%f]", mLeft, mRight);
        }
    }
}
