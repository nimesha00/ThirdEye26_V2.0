package com.sliit.thirdeye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws yellow detection boxes and labels over the live camera picture (same look as ObjDetection). */
public final class BoxOverlayView extends View {

    private final List<RectF> boxes = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackground = new Paint();
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BoxOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int yellow = Color.parseColor("#FFD600");
        float density = getResources().getDisplayMetrics().density;
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(yellow);
        boxPaint.setStrokeWidth(4 * density);
        labelBackground.setStyle(Paint.Style.FILL);
        labelBackground.setColor(yellow);
        labelText.setColor(Color.BLACK);
        labelText.setFakeBoldText(true);
        labelText.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
    }

    /** Replaces the boxes (in this view's pixels). Call on the UI thread. */
    void show(List<RectF> newBoxes, List<String> newLabels) {
        boxes.clear();
        boxes.addAll(newBoxes);
        labels.clear();
        labels.addAll(newLabels);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = labelText.getTextSize() * 0.3f;
        for (int i = 0; i < boxes.size(); i++) {
            RectF box = boxes.get(i);
            canvas.drawRect(box, boxPaint);
            String text = labels.get(i);
            float textWidth = labelText.measureText(text);
            float top = Math.max(0f, box.top - labelText.getTextSize() - 2 * pad);
            canvas.drawRect(box.left, top, box.left + textWidth + 2 * pad,
                    top + labelText.getTextSize() + 2 * pad, labelBackground);
            canvas.drawText(text, box.left + pad, top + labelText.getTextSize() + pad * 0.5f, labelText);
        }
    }
}
