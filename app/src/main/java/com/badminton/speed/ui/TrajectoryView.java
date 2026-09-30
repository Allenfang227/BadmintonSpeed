package com.badminton.speed.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D 球场动态模拟视图。
 * 用简单 3D 透视投影（绕 X/Y 轴旋转 + 透视除法）在 Canvas 上绘制：
 *   - 白色网格羽毛球场（俯视平面）
 *   - 绿色羽毛球飞行轨迹（带抛物线高度）
 *   - 动态羽毛球点沿轨迹循环飞行（动画）
 * 支持手指拖动 360° 旋转视角。
 */
public class TrajectoryView extends View {

    // 标准场地：长 13.4m（Y 轴），宽 6.1m（X 轴），中心为原点
    private static final float COURT_L = 13.4f;
    private static final float COURT_W = 6.1f;

    private List<float[]> traj3D; // {x, y, z} 米制，原点在场地中心

    // 视角旋转角（弧度）
    private float rotX = (float) Math.toRadians(55); // 俯仰
    private float rotZ = (float) Math.toRadians(-25); // 水平旋转

    // 动画：羽毛球沿轨迹飞行
    private float animT = 0f;
    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private final Runnable animRunnable = new Runnable() {
        @Override
        public void run() {
            animT += 0.012f;
            if (animT > 1f) animT = 0f;
            invalidate();
            animHandler.postDelayed(this, 33);
        }
    };

    private Paint gridPaint, trajPaint, ballPaint, netPaint;

    // 拖动旋转
    private float lastX, lastY;

    public TrajectoryView(Context ctx) { super(ctx); init(); }
    public TrajectoryView(Context ctx, AttributeSet attrs) { super(ctx); init(); }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.WHITE);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.5f);

        netPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        netPaint.setColor(Color.parseColor("#BBBBBB"));
        netPaint.setStyle(Paint.Style.STROKE);
        netPaint.setStrokeWidth(1.5f);

        trajPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trajPaint.setColor(Color.parseColor("#39FF14"));
        trajPaint.setStyle(Paint.Style.STROKE);
        trajPaint.setStrokeWidth(3f);

        ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ballPaint.setColor(Color.parseColor("#39FF14"));
        ballPaint.setStyle(Paint.Style.FILL);
    }

    /** 接收轨迹（米制，x∈[0,5.18], y∈[0,13.4]），转换为以场地中心为原点并加抛物线高度 */
    public void setTrajectory(List<double[]> traj) {
        traj3D = new ArrayList<>();
        if (traj != null && traj.size() > 1) {
            int n = traj.size();
            for (int i = 0; i < n; i++) {
                double[] p = traj.get(i);
                float x = (float) p[0] - COURT_W / 2f;
                float y = (float) p[1] - COURT_L / 2f;
                // 抛物线高度：把整条轨迹按击球段模拟弧线，这里用整体 sin 弧
                float t = (float) i / (n - 1);
                float z = 1.6f * (float) Math.sin(Math.PI * t); // 最高 1.6m
                traj3D.add(new float[]{x, y, z});
            }
        }
        // 启动动画
        animHandler.removeCallbacks(animRunnable);
        animHandler.post(animRunnable);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - lastX;
                float dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                rotZ += dx * 0.01f;
                rotX += dy * 0.01f;
                rotX = Math.max(0.2f, Math.min(1.4f, rotX));
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** 3D→2D 透视投影 */
    private float[] project(float x, float y, float z, int w, int h) {
        // 绕 Z 轴旋转（水平）
        float cz = (float) Math.cos(rotZ), sz = (float) Math.sin(rotZ);
        float x1 = x * cz - y * sz;
        float y1 = x * sz + y * cz;
        // 绕 X 轴旋转（俯仰）
        float cx = (float) Math.cos(rotX), sx = (float) Math.sin(rotX);
        float y2 = y1 * cx - z * sx;
        float z2 = y1 * sx + z * cx;
        // 透视除法
        float dist = 20f;
        float f = dist / (dist + z2 + 8f);
        float scale = Math.min(w, h) / 18f;
        return new float[]{w / 2f + x1 * f * scale, h / 2f + y2 * f * scale};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 场地网格（白线）：外框 + 中线 + 发球线
        float hw = COURT_W / 2f, hl = COURT_L / 2f;
        // 外框
        drawLine3D(canvas, -hw, -hl, 0, hw, -hl, 0, w, h, gridPaint);
        drawLine3D(canvas, hw, -hl, 0, hw, hl, 0, w, h, gridPaint);
        drawLine3D(canvas, hw, hl, 0, -hw, hl, 0, w, h, gridPaint);
        drawLine3D(canvas, -hw, hl, 0, -hw, -hl, 0, w, h, gridPaint);
        // 中线（网）
        drawLine3D(canvas, -hw, 0, 0, hw, 0, 0, w, h, gridPaint);
        // 网柱高 1.55m
        drawLine3D(canvas, -hw, 0, 0, -hw, 0, 1.55f, w, h, netPaint);
        drawLine3D(canvas, hw, 0, 0, hw, 0, 1.55f, w, h, netPaint);
        drawLine3D(canvas, -hw, 0, 1.55f, hw, 0, 1.55f, w, h, netPaint);
        // 前发球线（网前后 1.98m）
        drawLine3D(canvas, -hw, -1.98f, 0, hw, -1.98f, 0, w, h, gridPaint);
        drawLine3D(canvas, -hw, 1.98f, 0, hw, 1.98f, 0, w, h, gridPaint);
        // 中线 T
        drawLine3D(canvas, 0, -hl, 0, 0, -1.98f, 0, w, h, gridPaint);
        drawLine3D(canvas, 0, 1.98f, 0, 0, hl, 0, w, h, gridPaint);

        // 轨迹
        if (traj3D != null && traj3D.size() > 1) {
            Path path = new Path();
            boolean first = true;
            for (float[] p : traj3D) {
                float[] s = project(p[0], p[1], p[2], w, h);
                if (first) { path.moveTo(s[0], s[1]); first = false; }
                else path.lineTo(s[0], s[1]);
            }
            canvas.drawPath(path, trajPaint);

            // 动态羽毛球点
            int idx = (int) (animT * (traj3D.size() - 1));
            float[] bp = traj3D.get(Math.min(idx, traj3D.size() - 1));
            float[] s = project(bp[0], bp[1], bp[2], w, h);
            canvas.drawCircle(s[0], s[1], 6f, ballPaint);
        }
    }

    private void drawLine3D(Canvas canvas, float x1, float y1, float z1,
                            float x2, float y2, float z2, int w, int h, Paint paint) {
        float[] a = project(x1, y1, z1, w, h);
        float[] b = project(x2, y2, z2, w, h);
        canvas.drawLine(a[0], a[1], b[0], b[1], paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animHandler.removeCallbacks(animRunnable);
    }
}
