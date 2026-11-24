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

/**
 * Draws yellow boxes and values over the camera preview for the notes the note reader found.
 * Boxes arrive in the coordinates of the analysed (upright) camera image and are mapped the way
 * PreviewView's default FILL_CENTER scaling shows that image.
 */
public class NoteBoxesView extends View {

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackground = new Paint();
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mapped = new RectF();

    private List<RectF> boxes = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private int imageWidth = 1;
    private int imageHeight = 1;

    public NoteBoxesView(Context context) {
        this(context, null);
    }

    public NoteBoxesView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        int accent = Color.parseColor("#FFD600");
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4 * density);
        boxPaint.setColor(accent);
        labelBackground.setStyle(Paint.Style.FILL);
        labelBackground.setColor(accent);
        labelText.setColor(Color.BLACK);
        labelText.setFakeBoldText(true);
        labelText.setTextSize(22 * getResources().getDisplayMetrics().scaledDensity);
    }

    /** Boxes in image pixels, with one label each. Call on the UI thread. */
    public void setNotes(List<RectF> boxes, List<String> labels, int imageWidth, int imageHeight) {
        this.boxes = boxes;
        this.labels = labels;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boxes.isEmpty()) return;
        float scale = Math.max((float) getWidth() / imageWidth, (float) getHeight() / imageHeight);
        float dx = (getWidth() - imageWidth * scale) / 2f;
        float dy = (getHeight() - imageHeight * scale) / 2f;
        float pad = labelText.getTextSize() * 0.3f;
        for (int i = 0; i < boxes.size(); i++) {
            RectF b = boxes.get(i);
            mapped.set(b.left * scale + dx, b.top * scale + dy, b.right * scale + dx, b.bottom * scale + dy);
            canvas.drawRect(mapped, boxPaint);
            String text = labels.get(i);
            float textWidth = labelText.measureText(text);
            float top = Math.max(0f, mapped.top - labelText.getTextSize() - 2 * pad);
            canvas.drawRect(mapped.left, top, mapped.left + textWidth + 2 * pad,
                    top + labelText.getTextSize() + 2 * pad, labelBackground);
            canvas.drawText(text, mapped.left + pad, top + labelText.getTextSize() + pad * 0.5f, labelText);
        }
    }
}
