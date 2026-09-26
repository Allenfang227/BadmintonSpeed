package com.badminton.speed.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * 透明叠加层：在视频画面上绘制场地线、羽毛球轨迹、人员框。
 * 场地角点坐标是视频帧像素坐标，绘制时按 View 尺寸等比缩放。
 */
public class OverlayView extends View {

    private static final String TAG = "OverlayView";

    // 数据
    private Point[] courtCorners;      // 场地四角（视频帧像素坐标）
    private Mat homography;            // 单应矩阵（图像→标准场地米制），用于画网格
    private List<Point> trajectory;    // 羽毛球轨迹点（视频帧像素坐标）
    private List<RectF> playerBoxes;   // 人员框（视频帧像素坐标）

    // 视频帧原始尺寸（用于坐标缩放）
    private int frameWidth = 0;
    private int frameHeight = 0;

    // 画笔
    private final Paint courtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint courtBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OverlayView(Context context) {
        super(context);
        init();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 黄色场地线
        courtPaint.setColor(Color.YELLOW);
        courtPaint.setStyle(Paint.Style.STROKE);
        courtPaint.setStrokeWidth(2f);
        courtPaint.setAlpha(200);

        courtBorderPaint.setColor(Color.YELLOW);
        courtBorderPaint.setStyle(Paint.Style.STROKE);
        courtBorderPaint.setStrokeWidth(4f);
        courtBorderPaint.setAlpha(230);

        // 羽毛球点（绿色）
        ballPaint.setColor(Color.parseColor("#39FF14"));
        ballPaint.setStyle(Paint.Style.FILL);

        // 人员框（青色）
        playerPaint.setColor(Color.parseColor("#00E5FF"));
        playerPaint.setStyle(Paint.Style.STROKE);
        playerPaint.setStrokeWidth(2f);

        playerTextPaint.setColor(Color.parseColor("#00E5FF"));
        playerTextPaint.setTextSize(24f);
        playerTextPaint.setFakeBoldText(true);

        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** 设置视频帧尺寸（用于坐标缩放） */
    public void setFrameSize(int w, int h) {
        this.frameWidth = w;
        this.frameHeight = h;
        invalidate();
    }

    /** 设置场地角点 + 单应矩阵，绘制黄色场地网格 */
    public void setCourt(Point[] corners, Mat H) {
        this.courtCorners = corners;
        this.homography = H;
        invalidate();
    }

    /** 设置羽毛球轨迹点 */
    public void setTrajectory(List<Point> traj) {
        this.trajectory = traj;
        invalidate();
    }

    /** 设置人员框 */
    public void setPlayers(List<RectF> boxes) {
        this.playerBoxes = boxes;
        invalidate();
    }

    /** 清除所有叠加 */
    public void clearOverlay() {
        courtCorners = null;
        homography = null;
        trajectory = null;
        playerBoxes = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frameWidth <= 0 || frameHeight <= 0) return;

        // 视频帧到 View 的缩放比例（假设视频按比例填满 View，无 letterbox）
        float scaleX = (float) getWidth() / frameWidth;
        float scaleY = (float) getHeight() / frameHeight;
        float scale = Math.min(scaleX, scaleY);

        // 居中偏移（如果有 letterbox）
        float offsetX = (getWidth() - frameWidth * scale) / 2f;
        float offsetY = (getHeight() - frameHeight * scale) / 2f;

        // 绘制场地线
        drawCourt(canvas, scale, offsetX, offsetY);

        // 绘制羽毛球轨迹
        drawTrajectory(canvas, scale, offsetX, offsetY);

        // 绘制人员框
        drawPlayers(canvas, scale, offsetX, offsetY);
    }

    private void drawCourt(Canvas canvas, float scale, float offsetX, float offsetY) {
        if (courtCorners == null || courtCorners.length < 4) return;

        // 先画外框（加粗黄线）
        Point tl = courtCorners[0];
        Point tr = courtCorners[1];
        Point br = courtCorners[2];
        Point bl = courtCorners[3];

        float x1 = (float) (tl.x * scale) + offsetX;
        float y1 = (float) (tl.y * scale) + offsetY;
        float x2 = (float) (tr.x * scale) + offsetX;
        float y2 = (float) (tr.y * scale) + offsetY;
        float x3 = (float) (br.x * scale) + offsetX;
        float y3 = (float) (br.y * scale) + offsetY;
        float x4 = (float) (bl.x * scale) + offsetX;
        float y4 = (float) (bl.y * scale) + offsetY;

        canvas.drawLine(x1, y1, x2, y2, courtBorderPaint);
        canvas.drawLine(x2, y2, x3, y3, courtBorderPaint);
        canvas.drawLine(x3, y3, x4, y4, courtBorderPaint);
        canvas.drawLine(x4, y4, x1, y1, courtBorderPaint);

        // 画网格线（用单应矩阵逆变换，1米一格）
        if (homography != null && !homography.empty()) {
            drawCourtGrid(canvas, scale, offsetX, offsetY);
        }
    }

    private void drawCourtGrid(Canvas canvas, float scale, float offsetX, float offsetY) {
        try {
            // 标准场地单打：宽 5.18m，长 13.4m
            // 用逆单应矩阵把标准场地坐标投到图像坐标
            Mat Hinv = new Mat();
            org.opencv.core.Core.invert(homography, Hinv);
            if (Hinv.empty() || Hinv.rows() != 3 || Hinv.cols() != 3) {
                Hinv.release();
                return;
            }

            // 横线（y = 0..13.4，每 1 米）
            for (double y = 0; y <= 13.4; y += 1.0) {
                Point a = warpPoint(Hinv, new Point(0, y));
                Point b = warpPoint(Hinv, new Point(5.18, y));
                if (a != null && b != null) {
                    canvas.drawLine(
                            (float) (a.x * scale) + offsetX, (float) (a.y * scale) + offsetY,
                            (float) (b.x * scale) + offsetX, (float) (b.y * scale) + offsetY,
                            courtPaint);
                }
            }
            // 竖线（x = 0..5.18，每 1 米）
            for (double x = 0; x <= 5.18; x += 1.0) {
                Point a = warpPoint(Hinv, new Point(x, 0));
                Point b = warpPoint(Hinv, new Point(x, 13.4));
                if (a != null && b != null) {
                    canvas.drawLine(
                            (float) (a.x * scale) + offsetX, (float) (a.y * scale) + offsetY,
                            (float) (b.x * scale) + offsetX, (float) (b.y * scale) + offsetY,
                            courtPaint);
                }
            }
            Hinv.release();
        } catch (Exception e) {
            // 忽略绘制错误
        }
    }

    private Point warpPoint(Mat H, Point p) {
        if (H == null || H.empty() || H.rows() != 3 || H.cols() != 3) return null;
        Mat src = null;
        Mat dst = null;
        try {
            src = new Mat(1, 1, 6); // CV_64FC2
            src.put(0, 0, p.x, p.y);
            dst = new Mat();
            org.opencv.core.Core.perspectiveTransform(src, dst, H);
            if (dst.empty()) return null;
            double[] v = dst.get(0, 0);
            if (v == null || v.length < 2) return null;
            return new Point(v[0], v[1]);
        } catch (Exception e) {
            return null;
        } finally {
            if (src != null) src.release();
            if (dst != null) dst.release();
        }
    }

    private void drawTrajectory(Canvas canvas, float scale, float offsetX, float offsetY) {
        if (trajectory == null || trajectory.isEmpty()) return;

        // 画轨迹连线
        Point prev = null;
        for (Point p : trajectory) {
            if (p == null) continue;
            float x = (float) (p.x * scale) + offsetX;
            float y = (float) (p.y * scale) + offsetY;
            if (prev != null) {
                canvas.drawLine(
                        (float) (prev.x * scale) + offsetX, (float) (prev.y * scale) + offsetY,
                        x, y, ballPaint);
            }
            prev = p;
        }
        // 画最新的球点（大一点）
        if (prev != null) {
            ballPaint.setColor(Color.parseColor("#39FF14"));
            canvas.drawCircle((float) (prev.x * scale) + offsetX,
                    (float) (prev.y * scale) + offsetY, 6f, ballPaint);
        }
    }

    private void drawPlayers(Canvas canvas, float scale, float offsetX, float offsetY) {
        if (playerBoxes == null || playerBoxes.isEmpty()) return;
        int id = 0;
        for (RectF r : playerBoxes) {
            if (r == null) continue;
            float left = r.left * scale + offsetX;
            float top = r.top * scale + offsetY;
            float right = r.right * scale + offsetX;
            float bottom = r.bottom * scale + offsetY;
            canvas.drawRect(left, top, right, bottom, playerPaint);
            canvas.drawText("P" + id, left, top - 4f, playerTextPaint);
            id++;
        }
    }

    /** OpenCV Rect 转 Android RectF 辅助 */
    public static RectF toRectF(org.opencv.core.Rect r) {
        if (r == null) return null;
        return new RectF(r.x, r.y, r.x + r.width, r.y + r.height);
    }

    public static List<RectF> toRectFList(List<org.opencv.core.Rect> rects) {
        List<RectF> list = new ArrayList<>();
        if (rects == null) return list;
        for (org.opencv.core.Rect r : rects) {
            RectF rf = toRectF(r);
            if (rf != null) list.add(rf);
        }
        return list;
    }
}
