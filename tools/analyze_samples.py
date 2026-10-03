#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
训练样本自动分析 + 预标注 (analyze_samples.py)
================================================
对每个样本：
  1. 白线掩码（亮 + 低饱和）统计——漏检风险（线太淡）
  2. 大块白色区域定位——干扰源（墙面广告字/地面BWF标志/反光）→ B误检风险
  3. 白线聚类拟合直线 → 交点 → RANSAC 拟合 BWF 模板 → 反投影 12 关键点
  4. 生成 court_labels.json（train_keypoints.py 直接可用的标注格式）
     —— 角不可见（出画面）的点自动置缺；输出每张图的可信角点数

用法：python3 analyze_samples.py samples/  out_labels/
依赖：pip install opencv-python numpy
"""
import argparse
import glob
import json
import os
import sys

import cv2
import numpy as np

INPUT = 720
SIGMA = 8
TEMPLATE = {
    "tl_out": (0.00, 0.00), "tr_out": (6.10, 0.00),
    "br_out": (6.10, 13.40), "bl_out": (0.00, 13.40),
    "net_l": (0.00, 6.70), "net_r": (6.10, 6.70),
    "servl_tl": (0.46, 4.72), "servl_tr": (5.64, 4.72),
    "servl_bl": (0.46, 12.64), "servl_br": (5.64, 12.64),
    "mid_t": (3.05, 4.72), "mid_b": (3.05, 12.64),
}
ORDER = ["tl_out", "tr_out", "br_out", "bl_out"]


def white_mask(gray, sat):
    """白线掩码 = 亮 + 低饱和，再做细长连通域过滤。

    关键：场馆地面/墙面有大量白色广告字（BWF认证标志、CHALLENGE、Kumpoo、
    补水啦等），大块白字和场地线的区别在形状——场地线是细长条
    （线宽 40mm 投影 5~12px，长度数十~数百px），广告字是大块连通域。
    过滤规则（面积阈值相对帧面积）：
      - 面积 > 0.25% 帧面积 且 长宽比 < 4 → 广告字/标志 → 剔除
      - 其余（细长条 或 小碎片）→ 保留
    """
    base = (gray > 120) & (sat < 70)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(base.astype(np.uint8) * 255, 8)
    out = np.zeros_like(base)
    area_frame = base.shape[0] * base.shape[1]
    for i in range(1, n):
        a = stats[i, cv2.CC_STAT_AREA]
        w = stats[i, cv2.CC_STAT_WIDTH]
        h = stats[i, cv2.CC_STAT_HEIGHT]
        if a > area_frame * 0.0025 and max(w, h) / (min(w, h) + 1e-6) < 4.0:
            continue  # 大块方形白色（广告字/标志）
        out[lab == i] = True
    return out


def scan_segments(white, W, H, min_run):
    """横向+纵向扫描白色线段，返回点集（white 为 2D bool mask）"""
    hpts, vpts = [], []
    for y in range(H):
        x = 0
        while x < W:
            if not white[y, x]:
                x += 1
                continue
            e = x
            while e < W and white[y, e]:
                e += 1
            if e - x >= min_run:
                hpts.append(((x + e) / 2, y))
            x = e + 1
    for x in range(W):
        y = 0
        while y < H:
            if not white[y, x]:
                y += 1
                continue
            e = y
            while e < H and white[e, x]:
                e += 1
            if e - y >= min_run:
                vpts.append((x, (y + e) / 2))
            y = e + 1
    return hpts, vpts


def ransac_line(pts, tol=6.0, iters=100):
    """拟合一条线，返回 (nx, ny, c, midX, midY)"""
    if len(pts) < 20:
        return None
    best = None
    best_n = -1
    rng = np.random.default_rng(7)
    for _ in range(iters):
        a = pts[rng.integers(0, len(pts))]
        b = pts[rng.integers(0, len(pts))]
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = np.hypot(dx, dy)
        if L < 10:
            continue
        nx, ny = -dy / L, dx / L
        c = -(nx * a[0] + ny * a[1])
        d = np.abs(pts[:, 0] * nx + pts[:, 1] * ny + c)
        n = int((d < tol).sum())
        if n > best_n:
            best_n = n
            best = (nx, ny, c, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    return best


def fit_lines(pts, max_lines=5):
    pts = np.asarray(pts, dtype=np.float64)
    if len(pts) < 20:
        return []
    lines = []
    rem = pts
    for _ in range(max_lines):
        line = ransac_line(rem)
        if line is None:
            break
        lines.append(line)
        nx, ny, c, _, _ = line
        d = np.abs(rem[:, 0] * nx + rem[:, 1] * ny + c)
        rem = rem[d > 6.0]
        if len(rem) < 20:
            break
    lines.sort(key=lambda l: l[3])
    return lines


def intersect(l1, l2):
    a1, b1, c1 = l1[0], l1[1], l1[2]
    a2, b2, c2 = l2[0], l2[1], l2[2]
    det = a1 * b2 - a2 * b1
    if abs(det) < 1e-6:
        return None
    return ((b1 * c2 - b2 * c1) / det, (a2 * c1 - a1 * c2) / det)


def regress_court(img):
    """返回 4 角（tl,tr,br,bl 像素坐标）或 None"""
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 1]
    white = white_mask(gray, sat)
    hpts, vpts = scan_segments(white, w, h, int(w * 0.04))
    if len(hpts) < 12 or len(vpts) < 12:
        return None
    hlines = fit_lines(hpts)
    vlines = fit_lines(vpts)
    if len(hlines) < 2 or len(vlines) < 2:
        return None
    keys = []
    for hl in hlines:
        for vl in vlines:
            p = intersect(hl, vl)
            if p and 0 <= p[0] <= w and 0 <= p[1] <= h:
                keys.append(p)
    if len(keys) < 6:
        return None
    keys = np.asarray(keys, dtype=np.float32)
    dst = np.array([[0, 0], [6.10, 0], [6.10, 13.40], [0, 13.40]], dtype=np.float32)
    best = None
    best_score = -1
    rng = np.random.default_rng(42)
    tol = w * 0.10
    for _ in range(240):
        idx = rng.integers(0, len(keys), 4)
        p0, p1, p2, p3 = keys[idx]
        area = abs((p1[0] - p0[0]) * (p3[1] - p0[1]) - (p1[1] - p0[1]) * (p3[0] - p0[0])
                   + (p3[0] - p2[0]) * (p0[1] - p2[1]) - (p3[1] - p2[1]) * (p0[0] - p2[0])) / 2
        if area < w * h * 0.02:
            continue
        H = cv2.getPerspectiveTransform(np.array([p0, p1, p2, p3], dtype=np.float32), dst)
        score = 0
        for t in dst:
            pr = project(H, t[0], t[1])
            d = np.min(np.hypot(keys[:, 0] - pr[0], keys[:, 1] - pr[1]))
            if d < tol:
                score += 1
        if score > best_score:
            best_score = score
            best = H
    if best is None or best_score < 2:
        return None
    corners = [project(best, t[0], t[1]) for t in dst]
    return corners, best


def project(H, x, y):
    w = H[2, 0] * x + H[2, 1] * y + H[2, 2]
    if abs(w) < 1e-9:
        return (x, y)
    return ((H[0, 0] * x + H[0, 1] * y + H[0, 2]) / w,
            (H[1, 0] * x + H[1, 1] * y + H[1, 2]) / w)


def find_white_blobs(img, min_area_px):
    """定位大块白色区域（干扰源：广告字/BWF标志/反光）"""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 1]
    mask = ((gray > 150) & (sat < 60)).astype(np.uint8) * 255
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    blobs = []
    for i in range(1, n):
        area = stats[i, cv2.CC_STAT_AREA]
        if area >= min_area_px:
            x, y, w_, h_ = stats[i, cv2.CC_STAT_LEFT], stats[i, cv2.CC_STAT_TOP], stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
            blobs.append({"x": int(x), "y": int(y), "w": int(w_), "h": int(h_), "area": int(area)})
    return sorted(blobs, key=lambda b: -b["area"])[:12]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("samples_dir", help="样本图片目录")
    ap.add_argument("out_dir", help="标注 json 输出目录")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    images = sorted(glob.glob(os.path.join(args.samples_dir, "*.[jJ][pP][gG]")) +
                    sorted(glob.glob(os.path.join(args.samples_dir, "*.[pP][nN][gG]"))))
    if not images:
        sys.exit("目录里没有图片")

    summary = {"auto_ok": [], "auto_fail": [], "interference": {}}
    for img_path in images:
        img = cv2.imread(img_path)
        if img is None:
            continue
        h, w = img.shape[:2]
        if min(h, w) < 800:
            print(f"[{os.path.basename(img_path).rsplit('.',1)[0]}] 跳过：小图（球/误检红框标注样本，非场地标定样本）")
            continue
        s = min(1.0, INPUT / max(h, w))
        small = cv2.resize(img, (int(w * s), int(h * s))) if s < 1 else img
        sh, sw = small.shape[:2]

        # 白线密度
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
        white = white_mask(gray, hsv[:, :, 1])
        white_ratio = float(white.mean())

        # 干扰源
        blobs = find_white_blobs(small, sw * sh * 0.0008)  # ≥0.08% 面积
        inter = [b for b in blobs if b["area"] > sw * sh * 0.002]  # 只报告大块

        # 自动标定
        reg = regress_court(small)
        name = os.path.basename(img_path).rsplit(".", 1)[0]
        if reg is None:
            summary["auto_fail"].append(name)
            summary["interference"][name] = {"white_ratio": round(white_ratio, 3), "big_blobs": len(inter)}
            print(f"[{name}] 标定失败 白线密度={white_ratio:.3f} 干扰块={len(inter)}")
            continue
        corners, H = reg
        # 角点分布校验：4 角两两最小距离必须 > 帧宽 8%，否则是退化拟合（角点挤在一起）
        cx = [c[0] for c in corners]
        min_d = min(min(abs(corners[i][0] - corners[j][0]) + abs(corners[i][1] - corners[j][1])
                        for j in range(4) if j != i) for i in range(4))
        if min_d < sw * 0.08:
            summary["auto_fail"].append(name)
            summary["interference"][name] = {"white_ratio": round(white_ratio, 3),
                                             "big_blobs": len(inter), "reason": "退化拟合(角点挤在一起)"}
            print(f"[{name}] 标定失败 退化拟合(角距 {min_d:.0f}px < {sw*0.08:.0f}px)")
            continue

        summary["auto_ok"].append(name)
        # 反投影 12 关键点；出画面 5% 边缘内的点视为不可见（缺）
        pts = {}
        n_vis = 0
        for k, (mx, my) in TEMPLATE.items():
            x, y = project(H, mx, my)
            if x < -0.05 * sw or x > 1.05 * sw or y < -0.05 * sh or y > 1.05 * sh:
                continue
            pts[k] = {"x": round(x / s), "y": round(y / s), "vis": 1}
            n_vis += 1
        ann = {"image": os.path.basename(img_path), "W": w, "H": h, "points": pts}
        out = os.path.join(args.out_dir, name + ".json")
        with open(out, "w") as f:
            json.dump(ann, f, indent=1, ensure_ascii=False)
        summary["interference"][name] = {"white_ratio": round(white_ratio, 3),
                                         "big_blobs": len(inter), "points": n_vis}
        print(f"[{name}] 标定OK 可见关键点={n_vis}/12 白线密度={white_ratio:.3f} 干扰块={len(inter)}")

    ok = len(summary["auto_ok"])
    print(f"\n===== 汇总 =====")
    print(f"自动标定成功 {ok}/{len(images)}；失败 {len(summary['auto_fail'])}")
    print(f"失败样本：{summary['auto_fail']}")
    with open(os.path.join(args.out_dir, "summary.json"), "w") as f:
        json.dump(summary, f, indent=1, ensure_ascii=False)
    print(f"标注与汇总已输出到 {args.out_dir}/")


if __name__ == "__main__":
    main()
