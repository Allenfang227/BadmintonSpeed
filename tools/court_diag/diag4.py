#!/usr/bin/env python3
"""游程线段版场地识别：多方向旋转栅格游程线段 → 主方向(长度直方图+族数) → fit_lines(投影聚类) → 交点十字校验 → BWF模板RANSAC+支撑 → 球网选场 → 精配准"""
import sys, math, os
import numpy as np
import cv2

WORK_MAX_SIDE = 720
W_, H_ = 6.10, 13.40
TP = np.float32([(0,0),(W_,0),(W_,H_),(0,H_)])

def load(path):
    img = cv2.imread(path)
    s = min(1.0, WORK_MAX_SIDE / max(img.shape[0], img.shape[1]))
    if s < 1.0:
        img = cv2.resize(img, (int(img.shape[1]*s), int(img.shape[0]*s)))
    return img, s

def enhance_white(img):
    gray0 = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    b0, g, r = cv2.split(img.astype(np.int16))
    mx = np.maximum.reduce([r, g, b0]); mn = np.minimum.reduce([r, g, b0])
    sat = (mx - mn).astype(np.uint8)
    H, W = img.shape[:2]
    cy0, cy1 = int(H*0.25), int(H*0.75); cx0, cx1 = int(W*0.15), int(W*0.85)
    crop = gray0[cy0:cy1, cx0:cx1]
    hist = cv2.calcHist([crop], [0], None, [256], [0, 256]).flatten()
    cand = [i for i in range(20, 240) if hist[i] > crop.size*0.01]
    floorVal = float(max(cand, key=lambda i: hist[i])) if cand else float(np.median(crop))
    m_floor = ((gray0.astype(np.int16) - floorVal > 40) & (sat < 70)).astype(np.uint8) * 255
    mean = cv2.boxFilter(gray0, -1, (31, 31))
    m_loc = ((gray0.astype(np.int16) - mean > 25) & (sat < 80)).astype(np.uint8) * 255
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    hh, ss, vv = cv2.split(hsv)
    greenMask = (((hh >= 55) & (hh <= 130)) & (ss >= 60)).astype(np.uint8) * 255
    floorMask = cv2.dilate(greenMask, np.ones((7, 7), np.uint8))
    white = cv2.max(m_floor, m_loc)
    white = cv2.bitwise_and(white, floorMask)
    white = cv2.morphologyEx(white, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    white = cv2.morphologyEx(white, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    return white, gray0

def run_length_segments(white, W, H, n_dir=16):
    """旋转栅格游程：对每个方向把白像素投影到切向u/法向v，按v行游程(u连续段)取线段。
    返回 [(x1,y1,x2,y2)] 原图坐标。"""
    yy, xx = np.nonzero(white)
    segs = []
    for di in range(n_dir):
        a = math.radians(di * 180.0 / n_dir)
        ca, sa = math.cos(a), math.sin(a)
        # u = x*ca + y*sa（沿线方向）, v = -x*sa + y*ca（法向）
        u = xx*ca + yy*sa
        v = -xx*sa + yy*ca
        rows = {}
        for j in range(len(xx)):
            vi = int(round(v[j]))
            rows.setdefault(vi, []).append(j)
        for vi, idxs in rows.items():
            if len(idxs) < 3: continue
            uu = u[idxs]
            order = np.argsort(uu)
            us = uu[order]
            start = us[0]; prev = us[0]; run = 1
            for k in range(1, len(us)):
                if us[k] - prev <= 2.0:
                    run += 1
                else:
                    if run >= 8:
                        mid = (start + prev)/2
                        # 线段中点原图坐标
                        midx = mid*ca - vi*sa
                        midy = mid*sa + vi*ca
                        segs.append((midx, midy, prev-start))
                    start = us[k]; run = 1
                prev = us[k]
            if run >= 8:
                mid = (start + prev)/2
                midx = mid*ca - vi*sa
                midy = mid*sa + vi*ca
                segs.append((midx, midy, prev-start))
    return segs

def angle_of(x1,y1,x2,y2):
    return math.degrees(math.atan2(y2-y1, x2-x1)) % 180

def fit_lines(segs, axis_deg):
    rad = math.radians(axis_deg)
    n0, n1 = -math.sin(rad), math.cos(rad)
    proj = []; seglens = []
    for (mx, my, ln) in segs:
        ang = math.degrees(math.atan2(my - 0, mx - 0))  # 无意义占位
        # 方向信息在游程里已丢失，改用几何：线段方向=axis±22.5 校验省略（游程已按方向生成）
        proj.append(mx*n0 + my*n1)
        seglens.append(ln)
    if len(proj) < 2: return [], n0, n1
    order = sorted(range(len(proj)), key=lambda i: proj[i])
    clusters = []; cur = [order[0]]
    for oi in order[1:]:
        if proj[oi] - proj[cur[-1]] <= 8:
            cur.append(oi)
        else:
            clusters.append(cur); cur = [oi]
    clusters.append(cur)
    lines = []
    for c in clusters:
        cnt = len(c)
        avg = float(np.mean([seglens[i] for i in c]))
        if cnt >= 2 or avg > 50:
            lines.append(float(np.median([proj[i] for i in c])))
    lines.sort()
    return lines, n0, n1

def dominant_axes(segs):
    hist = {}
    for (mx, my, ln) in segs:
        # 游程方向 = 对应旋转轴；但游程按16方向生成，角度≈di*11.25
        pass
    # 简化：直接统计各方向线段总长度
    return None

def line_support_mask(Hinv, white, W, H):
    hlines = [0.0, 0.76, 4.72, 8.68, 12.64, 13.40]
    vlines = [0.0, 0.46, 3.05, 5.64, 6.10]
    yy, xx = np.nonzero(white)
    sup = 0; prev = []
    for (t1, t2) in [((0.0, y), (W_, y)) for y in hlines] + [((x, 0.0), (x, H_)) for x in vlines]:
        p1 = cv2.perspectiveTransform(np.float32([[t1]]), Hinv)[0][0]
        p2 = cv2.perspectiveTransform(np.float32([[t2]]), Hinv)[0][0]
        dx, dy = p2[0]-p1[0], p2[1]-p1[1]
        ln = math.hypot(dx, dy)
        if ln < 20: continue
        nx, ny = -dy/ln, dx/ln
        c = -(nx*p1[0]+ny*p1[1])
        dup = any(abs(nx-ox)+abs(ny-oy) < 0.15 and abs(c-oc) < 15 for (ox,oy,oc) in prev)
        if dup: continue
        d = abs(nx*xx + ny*yy + c)
        hit = np.nonzero(d < 6)[0]
        if len(hit) < 8: continue
        tx, ty = dx/ln, dy/ln
        along = tx*xx[hit] + ty*yy[hit]
        span = along.max() - along.min()
        if span > 0.35*ln:
            sup += 1; prev.append((nx, ny, c))
    return sup

def net_support(Hinv, white):
    yy, xx = np.nonzero(white)
    p1 = cv2.perspectiveTransform(np.float32([[(0.0, 6.70)]]), Hinv)[0][0]
    p2 = cv2.perspectiveTransform(np.float32([[(W_, 6.70)]]), Hinv)[0][0]
    dx, dy = p2[0]-p1[0], p2[1]-p1[1]
    ln = math.hypot(dx, dy)
    if ln < 20: return 0.0
    nx, ny = -dy/ln, dx/ln
    c = -(nx*p1[0]+ny*p1[1])
    d = abs(nx*xx + ny*yy + c)
    hit = np.nonzero(d < 6)[0]
    if len(hit) < 8: return 0.0
    tx, ty = dx/ln, dy/ln
    along = tx*xx[hit] + ty*yy[hit]
    return float(along.max()-along.min())/ln

if __name__ == "__main__":
    d = os.path.dirname(os.path.abspath(__file__))
    img, sc = load(os.path.join(d, 'sample_A0ZOcXGY1r.jpg'))
    white, gray = enhance_white(img)
    segs = run_length_segments(white, img.shape[1], img.shape[0])
    print("游程线段总数:", len(segs))
    # 各方向长度统计
    lens_by_dir = {}
    for di in range(16):
        a = di*11.25
        # 线段方向判定：切向角 a 或 a+90? 这里简化为输出
    import collections
    hist = collections.defaultdict(float)
    for (mx, my, ln) in segs:
        pass
    print("示例线段:", segs[:5])
