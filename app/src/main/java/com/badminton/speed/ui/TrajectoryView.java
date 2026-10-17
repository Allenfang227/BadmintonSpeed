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
 * 用简单 3D 透视投影（绕 X/Z 轴旋转 + 透视除法）在 Canvas 上绘制标准羽毛球场地：
 *   - 地板填充 + 白色场地线（双打外框 / 单打边线 / 双打后发球线 / 前发球线 / 中线 T）
 *   - 球网（网柱 + 顶带 + 竖向网眼）
 *   - 绿色羽毛球飞行轨迹（带抛物线高度）+ 地面投影阴影 + 动态羽毛球
 * 支持手指拖动 360° 旋转视角。
 *
 * v2.45：重绘球场建模（原版仅极简线框、轨迹高度为整体假弧）。
 */
public class TrajectoryView extends View {

    // 标准双打场地：长 13.4m（Y 轴），宽 6.1m（X 轴），中心为原点
    private static final float COURT_L = 13.4f;
    private static final float COURT_W = 6.1f;
    // 关键标线（米）
    private static final float SINGLES_HALF_W = 5.18f / 2f;   // 单打半宽 2.59
    private static final float SHORT_SERVICE = 1.98f;          // 前发球线距网
    private static final float LONG_SERVICE_D = COURT_L / 2f - 0.76f; // 双打后发球线 5.94
    private static final float NET_H = 1.55f;                  // 网高

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

    private Paint gridPaint, netPaint, netMeshPaint, trajPaint, ballPaint,
            floorPaint, shadowPaint, postPaint;

    // 拖动旋转
    private float lastX, lastY;

    public TrajectoryView(Context ctx) { super(ctx); init(); }
    public TrajectoryView(Context ctx, AttributeSet attrs) { super(ctx); init(); }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.WHITE);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        netPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        netPaint.setColor(Color.parseColor("#E0E0E0"));
        netPaint.setStyle(Paint.Style.STROKE);
        netPaint.setStrokeWidth(2.5f);

        netMeshPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        netMeshPaint.setColor(Color.parseColor("#88FFFFFF"));
        netMeshPaint.setStyle(Paint.Style.STROKE);
        netMeshPaint.setStrokeWidth(1f);

        trajPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trajPaint.setColor(Color.parseColor("#39FF14"));
        trajPaint.setStyle(Paint.Style.STROKE);
        trajPaint.setStrokeWidth(3.5f);
        trajPaint.setStrokeCap(Paint.Cap.ROUND);

        ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ballPaint.setColor(Color.parseColor("#7CFF6B"));
        ballPaint.setStyle(Paint.Style.FILL);
        ballPaint.setShadowLayer(8f, 0, 0, Color.parseColor("#39FF14"));

        floorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        floorPaint.setColor(Color.parseColor("#1E6B3A")); // 场地绿
        floorPaint.setStyle(Paint.Style.FILL);
        floorPaint.setAlpha(120);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.parseColor("#40000000"));
        shadowPaint.setStyle(Paint.Style.FILL);

        postPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        postPaint.setColor(Color.parseColor("#CFD8DC"));
        postPaint.setStyle(Paint.Style.STROKE);
        postPaint.setStrokeWidth(4f);
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
                // 抛物线高度：按击球段模拟弧线（真实数据 z=0，这里做可视化抬升）
                float t = (float) i / (n - 1);
                float z = 1.6f * (float) Math.sin(Math.PI * t);
                traj3D.add(new float[]{x, y, z});
            }
        }
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
        float cz = (float) Math.cos(rotZ), sz = (float) Math.sin(rotZ);
        float x1 = x * cz - y * sz;
        float y1 = x * sz + y * cz;
        float cx = (float) Math.cos(rotX), sx = (float) Math.sin(rotX);
        float y2 = y1 * cx - z * sx;
        float z2 = y1 * sx + z * cx;
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

        float hw = COURT_W / 2f, hl = COURT_L / 2f;

        // 1) 地板填充（双打外框四边形）
        Path floor = new Path();
        float[] c0 = project(-hw, -hl, 0, w, h);
        float[] c1 = project(hw, -hl, 0, w, h);
        float[] c2 = project(hw, hl, 0, w, h);
        float[] c3 = project(-hw, hl, 0, w, h);
        floor.moveTo(c0[0], c0[1]);
        floor.lineTo(c1[0], c1[1]);
        floor.lineTo(c2[0], c2[1]);
        floor.lineTo(c3[0], c3[1]);
        floor.close();
        canvas.drawPath(floor, floorPaint);

        // 2) 场地标线（白）
        // 双打外框
        drawLine3D(canvas, -hw, -hl, 0, hw, -hl, 0, w, h, gridPaint);
        drawLine3D(canvas, hw, -hl, 0, hw, hl, 0, w, h, gridPaint);
        drawLine3D(canvas, hw, hl, 0, -hw, hl, 0, w, h, gridPaint);
        drawLine3D(canvas, -hw, hl, 0, -hw, -hl, 0, w, h, gridPaint);
        // 单打边线（x=±2.59）
        drawLine3D(canvas, -SINGLES_HALF_W, -hl, 0, -SINGLES_HALF_W, hl, 0, w, h, gridPaint);
        drawLine3D(canvas, SINGLES_HALF_W, -hl, 0, SINGLES_HALF_W, hl, 0, w, h, gridPaint);
        // 双打后发球线（y=±5.94）
        drawLine3D(canvas, -hw, -LONG_SERVICE_D, 0, hw, -LONG_SERVICE_D, 0, w, h, gridPaint);
        drawLine3D(canvas, -hw, LONG_SERVICE_D, 0, hw, LONG_SERVICE_D, 0, w, h, gridPaint);
        // 前发球线（y=±1.98）
        drawLine3D(canvas, -hw, -SHORT_SERVICE, 0, hw, -SHORT_SERVICE, 0, w, h, gridPaint);
        drawLine3D(canvas, -hw, SHORT_SERVICE, 0, hw, SHORT_SERVICE, 0, w, h, gridPaint);
        // 中线 T（x=0，前发球线到后界线）
        drawLine3D(canvas, 0, -hl, 0, 0, -SHORT_SERVICE, 0, w, h, gridPaint);
        drawLine3D(canvas, 0, SHORT_SERVICE, 0, 0, hl, 0, w, h, gridPaint);
        // 中线（网下，整条）
        drawLine3D(canvas, -hw, 0, 0, hw, 0, 0, w, h, netPaint);

        // 3) 球网：网柱 + 顶带 + 竖向网眼
        drawLine3D(canvas, -hw, 0, 0, -hw, 0, NET_H, w, h, postPaint);
        drawLine3D(canvas, hw, 0, 0, hw, 0, NET_H, w, h, postPaint);
        drawLine3D(canvas, -hw, 0, NET_H, hw, 0, NET_H, w, h, netPaint); // 顶带
        int mesh = 16;
        for (int i = 0; i <= mesh; i++) {
            float mx = -hw + (2f * hw) * i / mesh;
            drawLine3D(canvas, mx, 0, 0.05f, mx, 0, NET_H, w, h, netMeshPaint);
        }

        // 4) 轨迹 + 动态球 + 地面阴影
        if (traj3D != null && traj3D.size() > 1) {
            Path path = new Path();
            boolean first = true;
            for (float[] p : traj3D) {
                float[] s = project(p[0], p[1], p[2], w, h);
                if (first) { path.moveTo(s[0], s[1]); first = false; }
                else path.lineTo(s[0], s[1]);
            }
            canvas.drawPath(path, trajPaint);

            int idx = (int) (animT * (traj3D.size() - 1));
            idx = Math.min(idx, traj3D.size() - 1);
            float[] bp = traj3D.get(idx);
            // 地面阴影（球的地面投影）
            float[] sh = project(bp[0], bp[1], 0f, w, h);
            canvas.drawOval(sh[0] - 7f, sh[1] - 3f, sh[0] + 7f, sh[1] + 3f, shadowPaint);
            // 球体
            float[] s = project(bp[0], bp[1], bp[2], w, h);
            canvas.drawCircle(s[0], s[1], 6.5f, ballPaint);
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
