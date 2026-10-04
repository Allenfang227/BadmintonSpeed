#!/usr/bin/env python3
"""场地线识别诊断：用用户 8 张真实样本图，复刻 App CourtRegressor 的
白线→交点→BWF 模板 RANSAC 管线，逐张量化成败并可视化。
目标：定位"为什么识别不了"，再针对性修算法。
"""
import sys, math, os
import numpy as np
import cv2

WORK_MAX_SIDE = 720
W_, H_ = 6.10, 13.40  # BWF 模板尺寸（米）

def load(path):
    img = cv2.imread(path)
    s = min(1.0, WORK_MAX_SIDE / max(img.shape[0], img.shape[1]))
    if s < 1.0:
        img = cv2.resize(img, (int(img.shape[1]*s), int(img.shape[0]*s)))
    return img, s

def white_mask(img):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    sat = gray.copy()
    b, g, r = cv2.split(img.astype(np.int16))
    mx = np.maximum.reduce([r, g, b]); mn = np.minimum.reduce([r, g, b])
    sat = (mx - mn).astype(np.uint8)
    # 白线 = 亮 + 低饱和
    m = (gray > 120) & (sat < 70)
    return m, gray

def scan_lines(white, W, H):
    """行/列扫描出横向、纵向线段中点"""
    hPts, vPts = [], []
    MIN_RUN = max(8, int(W*0.04)); MIN_RUN_V = max(8, int(H*0.04))
    for y in range(H):
        x = 0
        while x < W:
            if not white[y, x]: x += 1; continue
            end = x
            while end < W and white[y, end]: end += 1
            if end-x >= MIN_RUN: hPts.append(((x+end)/2, y))
            x = end+1
    for x in range(W):
        y = 0
        while y < H:
            if not white[y, x]: y += 1; continue
            end = y
            while end < H and white[y, end]: end += 1
            if end-y >= MIN_RUN_V: vPts.append((x, (y+end)/2))
            y = end+1
    return hPts, vPts

def ransac_line(pts):
    best = None; best_in = -1
    rng = np.random.RandomState(7)
    n = len(pts)
    for _ in range(100):
        a, b = pts[rng.randint(n)], pts[rng.randint(n)]
        dx, dy = b[0]-a[0], b[1]-a[1]
        ln = math.hypot(dx, dy)
        if ln < 10: continue
        nx, ny = -dy/ln, dx/ln
        c = -(nx*a[0] + ny*a[1])
        d = np.abs(np.array(pts) @ np.array([nx, ny]) + c)
        inl = int((d < 6).sum())
        if inl > best_in: best_in = inl; best = (nx, ny, c)
    return best

def fit_parallel(pts):
    lines = []
    rem = list(pts)
    for _ in range(5):
        if len(rem) < 20: break
        ln = ransac_line(rem)
        if ln is None: break
        lines.append(ln)
        nx, ny, c = ln
        rem = [p for p in rem if abs(p[0]*nx + p[1]*ny + c) > 6]
    lines.sort(key=lambda l: l[0]*l[0]+l[1]*l[1])  # 稳定排序
    return lines

def intersect(l1, l2):
    a1, b1, c1 = l1; a2, b2, c2 = l2
    det = a1*b2 - a2*b1
    if abs(det) < 1e-6: return None
    return ((b1*c2 - b2*c1)/det, (a2*c1 - a1*c2)/det)

def homography(p_src, p_dst):
    """4 点求单应性（米→像素）"""
    H, _ = cv2.findHomography(np.float32(p_dst), np.float32(p_src), method=0)
    return H

def run_regressor(img, Wpx, Hpx):
    m, gray = white_mask(img)
    hPts, vPts = scan_lines(m, Wpx, Hpx)
    if len(hPts) < 12 or len(vPts) < 12:
        return None, f"线点不足 h={len(hPts)} v={len(vPts)}"
    hL = fit_parallel(hPts); vL = fit_parallel(vPts)
    if len(hL) < 2 or len(vL) < 2:
        return None, f"直线不足 h={len(hL)} v={len(vL)}"
    kps = []
    for hl in hL:
        for vl in vL:
            p = intersect(hl, vl)
            if p and 0 <= p[0] <= Wpx and 0 <= p[1] <= Hpx:
                kps.append(p)
    if len(kps) < 6: return None, f"交点不足 {len(kps)}"
    # RANSAC 模板拟合
    tpl = [(0, 0), (W_, 0), (W_, H_), (0, H_)]
    rng = np.random.RandomState(42)
    n = len(kps)
    best = None; best_score = -1
    iters = min(240, max(60, n*n//2))
    tol = Wpx*0.10
    for _ in range(iters):
        idx = rng.choice(n, 4, replace=False)
        pts = [kps[i] for i in idx]
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        if (max(xs)-min(xs))*(max(ys)-min(ys)) < Wpx*Hpx*0.02: continue
        Hm = homography(pts, tpl)
        if Hm is None: continue
        # 反投影模板角点→像素，就近匹配交点
        score = 0
        corners = []
        for t in tpl:
            pp = cv2.perspectiveTransform(np.float32([[t]]), Hm)[0][0]
            dd = [math.hypot(pp[0]-k[0], pp[1]-k[1]) for k in kps]
            if min(dd) < tol: score += 1
            corners.append(pp)
        if score > best_score: best_score = score; best = (Hm, corners)
    if best is None or best_score < 2:
        return None, f"模板拟合分 {best_score} 太低"
    return best[1], None

def main():
    d = os.path.dirname(os.path.abspath(__file__))
    files = sorted(f for f in os.listdir(d) if f.endswith('.jpg'))
    total_ok = 0
    print(f"{'文件':28s} {'结果':6s} 原因/信息")
    print("-"*70)
    for f in files:
        img, sc = load(os.path.join(d, f))
        Hpx, Wpx = img.shape[:2]
        try:
            corners, err = run_regressor(img, Wpx, Hpx)
        except Exception as e:
            corners, err = None, f"异常 {e}"
        if corners:
            total_ok += 1
            print(f"{f:28s} {'OK':6s} 4角={[(int(c[0]),int(c[1])) for c in corners]}")
            # 可视化
            vis = img.copy()
            for c in corners:
                cv2.circle(vis, (int(c[0]), int(c[1])), 8, (0, 0, 255), -1)
            cv2.imwrite(os.path.join(d, f.replace('.jpg', '_corners.jpg')), vis)
        else:
            print(f"{f:28s} {'FAIL':6s} {err}")
    print("-"*70)
    print(f"识别率: {total_ok}/{len(files)}")

if __name__ == '__main__':
    main()
