package com.badminton.speed.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.opencv.core.Point;

import java.util.List;

/**
 * 2D 球场轨迹可视化视图。
 * 绘制标准羽毛球场（俯视），叠加羽毛球飞行轨迹。
 */
public class TrajectoryView extends View {

    private static final int COURT_W = 518; // 标准场地宽 5.18m
    private static final int COURT_H = 1340; // 标准场地长 13.4m

    private List<double[]> trajectory; // {x, y} 米制坐标
    private Paint courtPaint, linePaint, trajectoryPaint, hitPaint, bgPaint;

    public TrajectoryView(Context ctx) { super(ctx); init(); }
    public TrajectoryView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        bgPaint = new Paint(); bgPaint.setColor(Color.parseColor("#0F1612"));
        courtPaint = new Paint(); courtPaint.setColor(Color.parseColor("#1A241E"));
        linePaint = new Paint(); linePaint.setColor(Color.parseColor("#FFDD00")); linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(2f);
        trajectoryPaint = new Paint(); trajectoryPaint.setColor(Color.parseColor("#39FF14")); trajectoryPaint.setStyle(Paint.Style.STROKE); trajectoryPaint.setStrokeWidth(3f); trajectoryPaint.setAntiAlias(true);
        hitPaint = new Paint(); hitPaint.setColor(Color.parseColor("#FF4444")); hitPaint.setStyle(Paint.Style.FILL);
    }

    public void setTrajectory(List<double[]> traj) {
        this.trajectory = traj;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 背景
        canvas.drawRect(0, 0, w, h, bgPaint);

        // 缩放：场地比例 5.18:13.4
        float scale = Math.min((float) w / COURT_W, (float) h / COURT_H);
        float offX = (w - COURT_W * scale) / 2;
        float offY = (h - COURT_H * scale) / 2;

        // 场地底色
        canvas.drawRect(offX, offY, offX + COURT_W * scale, offY + COURT_H * scale, courtPaint);

        // 外框
        canvas.drawRect(offX, offY, offX + COURT_W * scale, offY + COURT_H * scale, linePaint);

        // 中线（网）
        canvas.drawLine(offX, offY + COURT_H * scale / 2,
                offX + COURT_W * scale, offY + COURT_H * scale / 2, linePaint);

        // 前发球线（网前后各 1.98m）
        float serveY = offY + (COURT_H / 2f - 198) * scale;
        float serveY2 = offY + (COURT_H / 2f + 198) * scale;
        canvas.drawLine(offX, serveY, offX + COURT_W * scale, serveY, linePaint);
        canvas.drawLine(offX, serveY2, offX + COURT_W * scale, serveY2, linePaint);

        // 中线（T 点）
        canvas.drawLine(offX + COURT_W * scale / 2, offY,
                offX + COURT_W * scale / 2, serveY, linePaint);
        canvas.drawLine(offX + COURT_W * scale / 2, serveY2,
                offX + COURT_W * scale / 2, offY + COURT_H * scale, linePaint);

        // 双打边线
        // （单打已经是外框，这里不额外画）

        // 轨迹
        if (trajectory != null && trajectory.size() > 1) {
            Path path = new Path();
            boolean first = true;
            for (double[] p : trajectory) {
                float x = offX + (float) p[0] * scale;
                float y = offY + (float) p[1] * scale;
                if (first) { path.moveTo(x, y); first = false; }
                else path.lineTo(x, y);
            }
            canvas.drawPath(path, trajectoryPaint);

            // 起点和终点
            double[] start = trajectory.get(0);
            double[] end = trajectory.get(trajectory.size() - 1);
            canvas.drawCircle(offX + (float) start[0] * scale, offY + (float) start[1] * scale, 6, hitPaint);
            canvas.drawCircle(offX + (float) end[0] * scale, offY + (float) end[1] * scale, 6, hitPaint);
        }
    }
}
