package com.badminton.speed.core;
import org.opencv.core.MatOfDouble;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.HOGDescriptor;

import java.util.ArrayList;
import java.util.List;

/**
 * 人员检测：OpenCV 内置 HOG + 预训练的 SVM（默认行人检测模型）。
 * 注意：HOGDescriptor.getDefaultPeopleDetector() 返回的是行人模型，
 * 对打羽毛球的运动员也是有效的（因为人还是人），只是精度取决于拍摄角度。
 */
public class PlayerDetector {

    private static final String TAG = "PlayerDetector";
    private HOGDescriptor hog;

    public PlayerDetector() {
        this.hog = new HOGDescriptor();
        hog.setSVMDetector(HOGDescriptor.getDefaultPeopleDetector());
    }

    public static class PlayerBox {
        public Rect rect;
        public double confidence;
        public int id;
    }

    /**
     * 在一帧上检测所有人员（边界框）。
     * @return 检测到的人员列表
     */
    public List<PlayerBox> detect(Mat frame) {
        List<PlayerBox> result = new ArrayList<>();
        if (frame == null || frame.empty()) return result;

        Mat gray = new Mat();
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

        // 多尺度检测
        MatOfRect rects = new MatOfRect();
        hog.detectMultiScale(gray, rects, new MatOfDouble(), 0, new Size(8, 8), new Size(32, 32), 1.05, 2.0, false);

        List<Rect> rectList = rects.toList();
        // NMS（非极大值抑制）简化版：去掉重叠的框
        List<Rect> filtered = nms(rectList, 0.3);

        int id = 0;
        for (Rect r : filtered) {
            PlayerBox pb = new PlayerBox();
            pb.rect = r;
            pb.confidence = 1.0; // HOG detectMultiScale 没有置信度
            pb.id = id++;
            result.add(pb);
        }
        return result;
    }

    private List<Rect> nms(List<Rect> rects, double overlapThresh) {
        if (rects.isEmpty()) return rects;
        List<Rect> out = new ArrayList<>();
        boolean[] suppressed = new boolean[rects.size()];

        // 按面积降序
        rects.sort((a, b) -> Double.compare(b.width * b.height, a.width * a.height));

        for (int i = 0; i < rects.size(); i++) {
            if (suppressed[i]) continue;
            out.add(rects.get(i));
            for (int j = i + 1; j < rects.size(); j++) {
                if (suppressed[j]) continue;
                if (iou(rects.get(i), rects.get(j)) > overlapThresh) suppressed[j] = true;
            }
        }
        return out;
    }

    private double iou(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x), y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        int w = Math.max(0, x2 - x1), h = Math.max(0, y2 - y1);
        double inter = w * h;
        double union = a.width * a.height + b.width * b.height - inter;
        return union > 0 ? inter / union : 0;
    }
}
