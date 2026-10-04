#!/usr/bin/env python3
"""投影聚类版场地识别（镜像 Kotlin CourtRegressor v3 逻辑，无 Hough）：
白线掩码(HSV绿色地板) → 白像素法向投影聚类平行线族(族数选向) → 交点十字校验 → BWF模板RANSAC+支撑 → 球网选场 → 精配准"""
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
    # HSV 绿色场地掩码
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    hh, ss, vv = cv2.split(hsv)
    greenMask = (((hh >= 55) & (hh <= 130)) & (ss >= 60)).astype(np.uint8) * 255
    floorMask = cv2.dilate(greenMask, np.ones((7, 7), np.uint8))
    white = cv2.max(m_floor, m_loc)
    white = cv2.bitwise_and(white, floorMask)
    white = cv2.morphologyEx(white, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    white = cv2.morphologyEx(white, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    return white, gray0

def project_clusters(wx, wy, axis_deg, min_span):
    rad = math.radians(axis_deg)
    n0, n1 = -math.sin(rad), math.cos(rad)
    tx, ty = n1, -n0
    proj = n0*wx + n1*wy
    order = np.argsort(proj)
    clusters = []
    cur = [order[0]]
    for oi in order[1:]:
        if proj[oi] - proj[cur[-1]] <= 8:
            cur.append(oi)
        else:
            clusters.append(cur); cur = [oi]
    clusters.append(cur)
    lines = []
    for c in clusters:
        if len(c) < 8: continue
        a = tx*wx[c] + ty*wy[c]
        span = a.max() - a.min()
        if span < min_span: continue
        lines.append(float(np.median(proj[c])))
    lines.sort()
    return lines

def compute_families(wx, wy, W, H):
    min_span = max(80, max(W, H)//4)
    counts = []
    fams = []
    for b in range(8):
        a = b*22.5 + 11.25
        fam = project_clusters(wx, wy, a, min_span)
        counts.append(len(fam)); fams.append(fam)
    bi = int(np.argmax(counts))
    a0 = bi*22.5 + 11.25
    if counts[bi] < 2: return None
    best2 = None; best2c = 0
    for b in range(8):
        if counts[b] < 2: continue
        d = abs((b*22.5+11.25 - a0) % 180); d = min(d, 180-d)
        if 70 <= d <= 110 and counts[b] > best2c:
            best2c = counts[b]; best2 = b*22.5+11.25
    if best2 is None: return None
    L0 = fams[bi]; L1 = project_clusters(wx, wy, best2, min_span)
    if len(L0) < 2 or len(L1) < 2: return None
    return (a0, L0), (best2, L1)

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

def run(img):
    white, gray0 = enhance_white(img)
    hgt, wdt = img.shape[:2]
    yy, xx = np.nonzero(white)
    nw0 = len(xx)
    if nw0 < 400: return None, f"白线不足 {nw0}", 0.0
    step = max(1, nw0 // 7000)
    xx = xx[::step][:7000]; yy = yy[::step][:7000]
    fam = compute_families(xx, yy, wdt, hgt)
    if fam is None: return None, "无正交线族", 0.0
    (a0, L1), (a1, L2) = fam
    n0, n1 = -math.sin(math.radians(a0)), math.cos(math.radians(a0))
    m0, m1 = -math.sin(math.radians(a1)), math.cos(math.radians(a1))
    kps = []
    for c1 in L1:
        for c2 in L2:
            det = n0*m1 - n1*m0
            if abs(det) < 1e-6: continue
            x = (c1*m1 - n1*c2)/det
            y = (n0*c2 - c1*m0)/det
            if 0 <= x <= wdt and 0 <= y <= hgt:
                kps.append((x, y))
    if len(kps) < 5: return None, f"交点不足 {len(kps)}", 0.0
    def best_line(lines, n0, n1, x, y):
        d = [abs(n0*x+n1*y-c) for c in lines]
        return int(np.argmin(d))
    kpf = []
    for (x, y) in kps:
        i1 = best_line(L1, n0, n1, x, y)
        i2 = best_line(L2, m0, m1, x, y)
        a = int(np.sum(np.abs(n0*xx+n1*yy-L1[i1]) < 8))
        b = int(np.sum(np.abs(m0*xx+m1*yy-L2[i2]) < 8))
        if a > 40 and b > 40:
            kpf.append((x, y))
    if len(kpf) < 5: return None, f"十字校验后不足 {len(kpf)}", 0.0
    n = len(kpf)
    rng = np.random.RandomState(42)
    bestH = None; best_score = -1; best_sup = 0; best_corners = None
    candidates = []
    iters = min(400, max(120, n*n))
    tol = wdt*0.09
    for _ in range(iters):
        idx = rng.randint(0, n, 4)
        p = [kpf[i] for i in idx]
        q0, q1, q2, q3 = p
        quadArea = abs((q1[0]-q0[0])*(q3[1]-q0[1]) - (q1[1]-q0[1])*(q3[0]-q0[0]) +
                       (q3[0]-q2[0])*(q0[1]-q2[1]) - (q3[1]-q2[1])*(q0[0]-q2[0]))/2
        if quadArea < wdt*hgt*0.02: continue
        Hm, _ = cv2.findHomography(np.float32(p), TP, method=0)
        if Hm is None: continue
        Hinv = np.linalg.inv(Hm)
        corners = [cv2.perspectiveTransform(np.float32([[t]]), Hinv)[0][0] for t in TP]
        cx = np.array(corners)
        area = abs(cv2.contourArea(cx.astype(np.float32)))
        if area < wdt*hgt*0.10: continue
        ew = max(np.hypot(cx[1,0]-cx[0,0], cx[1,1]-cx[0,1]), np.hypot(cx[2,0]-cx[3,0], cx[2,1]-cx[3,1]))
        eh = max(np.hypot(cx[3,0]-cx[0,0], cx[3,1]-cx[0,1]), np.hypot(cx[2,0]-cx[1,0], cx[2,1]-cx[1,1]))
        if ew < 1 or eh/ew > 4 or eh/ew < 0.25: continue
        sup = line_support_mask(Hinv, white, wdt, hgt)
        score = 0
        for c in corners:
            md = min(math.hypot(c[0]-k[0], c[1]-k[1]) for k in kpf)
            if md < tol: score += 1
        total = sup*3 + score
        if total > best_score:
            best_score = total; bestH = Hm; best_sup = sup; best_corners = corners
        if sup >= 5:
            dup = any(math.hypot(ch[0]-corners[0][0], ch[1]-corners[0][1]) < 40 for ch, _ in candidates)
            if not dup:
                candidates.append((corners[0], Hm))
    if bestH is None or best_sup < 1: return None, "RANSAC无解", 0.0
    # 球网选场
    if candidates:
        best_net = -1; best_netH = bestH
        for _, Hm in candidates:
            ns = net_support(np.linalg.inv(Hm), white)
            if ns > best_net:
                best_net = ns; best_netH = Hm
        if best_net > 0.35:
            bestH = best_netH
            best_sup = line_support_mask(np.linalg.inv(bestH), white, wdt, hgt)
    # 精配准
    refineH = bestH; refineSup = best_sup
    for _ in range(4):
        Hinv = np.linalg.inv(refineH)
        good_src, good_dst = [], []
        for (kx, ky) in kpf:
            t = cv2.perspectiveTransform(np.float32([[[kx, ky]]]), refineH)[0][0]
            tx_, ty_ = t[0], t[1]
            hline = [0.0, 0.76, 4.72, 8.68, 12.64, 13.40]
            vline = [0.0, 0.46, 3.05, 5.64, 6.10]
            dh = min(abs(ty_ - yy) for yy in hline)
            dv = min(abs(tx_ - xx) for xx in vline)
            if dh < 0.45 or dv < 0.45:
                good_src.append((kx, ky)); good_dst.append((tx_, ty_))
        if len(good_src) < 5: break
        H2, _ = cv2.findHomography(np.float32(good_src), np.float32(good_dst), method=0)
        if H2 is None: break
        sup2 = line_support_mask(np.linalg.inv(H2), white, wdt, hgt)
        if sup2 > refineSup:
            refineH = H2; refineSup = sup2
    Hinv = np.linalg.inv(refineH)
    corners = [cv2.perspectiveTransform(np.float32([[t]]), Hinv)[0][0] for t in TP]
    n_corner = 0
    for c in corners:
        md = min(math.hypot(c[0]-k[0], c[1]-k[1]) for k in kpf)
        if md < tol: n_corner += 1
    if refineSup < 5 or n_corner < 2:
        return None, f"精修后支撑{refineSup}", 0.0
    conf = 0.6*(refineSup/11) + 0.4*(n_corner/4)
    return corners, None, conf

if __name__ == "__main__":
    d = os.path.dirname(os.path.abspath(__file__))
    files = sorted(f for f in os.listdir(d) if f.endswith('.jpg') and not f.endswith('_corners.jpg'))
    ok = 0
    for f in files:
        img, sc = load(os.path.join(d, f))
        corners, err, conf = run(img)
        if corners:
            ok += 1
            print(f"{f:26s} OK {conf*100:4.0f}% {[(int(c[0]),int(c[1])) for c in corners]}")
        else:
            print(f"{f:26s} FAIL {err}")
    print(f"识别率: {ok}/{len(files)}")
