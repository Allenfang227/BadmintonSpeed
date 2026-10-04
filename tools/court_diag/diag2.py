#!/usr/bin/env python3
"""场地线识别 v2：任意方向线段检测（概率Hough）+ 正交主方向聚类 + 交点 + BWF 模板 RANSAC + 置信度。
目标：用户 8 张真实样本全识别。"""
import sys, math, os
import numpy as np
import cv2

WORK_MAX_SIDE = 720
W_, H_ = 6.10, 13.40

def load(path):
    img = cv2.imread(path)
    s = min(1.0, WORK_MAX_SIDE / max(img.shape[0], img.shape[1]))
    if s < 1.0:
        img = cv2.resize(img, (int(img.shape[1]*s), int(img.shape[0]*s)))
    return img, s

def enhance_white(img):
    """自适应白线掩码：以地板色众数为基准（白线 = 比地板亮 ≥40 且低饱和），
    反光区被地板色基线吸收，不会整片过曝。"""
    gray0 = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    b0, g, r = cv2.split(img.astype(np.int16))
    mx = np.maximum.reduce([r, g, b0]); mn = np.minimum.reduce([r, g, b0])
    sat = (mx - mn).astype(np.uint8)
    # 地板色众数：直方图峰值（在画面中部区域统计，避开观众席/广告亮区）
    H, W = img.shape[:2]
    cy0, cy1 = int(H*0.25), int(H*0.75); cx0, cx1 = int(W*0.15), int(W*0.85)
    crop = gray0[cy0:cy1, cx0:cx1]
    hist = cv2.calcHist([crop], [0], None, [256], [0, 256]).flatten()
    # 只找占比 ≥1% 的灰度峰中最大者（地板主色）
    cand = [i for i in range(20, 240) if hist[i] > crop.size*0.01]
    floorVal = float(max(cand, key=lambda i: hist[i])) if cand else float(np.median(crop))
    # 白线：比地板亮 ≥40 且低饱和；再叠加局部对比（地板暗部/阴影里的线）
    m_floor = ((gray0.astype(np.int16) - floorVal > 40) & (sat < 70)).astype(np.uint8) * 255
    mean = cv2.boxFilter(gray0, -1, (31, 31))
    m_loc = ((gray0.astype(np.int16) - mean > 25) & (sat < 80)).astype(np.uint8) * 255
    # 地板色空间约束：仅"绿色塑胶场地"区域（HSV hue 55~130 且 sat≥60）内才可能有场地线。
    # 人物白衣/广告白底/黄色护墙/显示屏 hue 不在绿色区间 → 全部排除，根治 B 误检。
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    hh, ss, vv = cv2.split(hsv)
    greenMask = (((hh >= 55) & (hh <= 130)) & (ss >= 60)).astype(np.uint8) * 255
    # 膨胀：白线本身不是绿色，但其两侧是
    floorMask = cv2.dilate(greenMask, np.ones((7, 7), np.uint8))
    white = cv2.max(m_floor, m_loc)
    white = cv2.bitwise_and(white, floorMask)
    # 形态学：闭合断线 + 去孤点
    white = cv2.morphologyEx(white, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    white = cv2.morphologyEx(white, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    return white, gray0

def detect_lines(white):
    """手写多方向游程线段（16 方向旋转栅格）→ (x1,y1,x2,y2)。
    对每个方向把白像素投影到 (切向u, 法向v)，按 v 行对 u 游程取连续段，
    等价于任意方向 Hough，但不依赖 OpenCV（Android 端同样可手写）。"""
    n_dir = 16
    yy, xx = np.nonzero(white)
    segs = []
    for di in range(n_dir):
        a = math.radians(di * 180.0 / n_dir)
        ca, sa = math.cos(a), math.sin(a)
        u = xx * ca + yy * sa
        v = -xx * sa + yy * ca
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
                        mid = (start + prev) / 2
                        ln = prev - start
                        midx = mid * ca - vi * sa
                        midy = mid * sa + vi * ca
                        # 沿方向展开虚拟线段两端
                        segs.append((midx - 0.5*ln*ca, midy - 0.5*ln*sa,
                                     midx + 0.5*ln*ca, midy + 0.5*ln*sa))
                    start = us[k]; run = 1
                prev = us[k]
            if run >= 8:
                mid = (start + prev) / 2
                ln = prev - start
                midx = mid * ca - vi * sa
                midy = mid * sa + vi * ca
                segs.append((midx - 0.5*ln*ca, midy - 0.5*ln*sa,
                             midx + 0.5*ln*ca, midy + 0.5*ln*sa))
    return segs

def angle_of(x1, y1, x2, y2):
    return math.degrees(math.atan2(y2-y1, x2-x1)) % 180

def dominant_axes(segs, W, H):
    """按"平行线族数量"选主方向：羽毛球场地横线族是 4~7 条等距平行线，
    干扰（广告/网杆/反光）一般只有 1~2 条。再配最正交的第二方向。"""
    if not segs: return []
    # 候选：前 10 个角度桶
    hist = {}
    for (x1, y1, x2, y2) in segs:
        ln = math.hypot(x2-x1, y2-y1)
        a = angle_of(x1, y1, x2, y2)
        b = int(a // 22.5)
        hist[b] = hist.get(b, 0) + ln
    peaks = sorted(hist, key=lambda k: -hist[k])[:10]
    cand = []
    for b in peaks:
        a = b * 22.5 + 11.25
        lines, n0, n1 = fit_lines(segs, a, W, H)
        cand.append((a, lines))
    # 平行线数最多 = 主横线族方向
    cand.sort(key=lambda c: -len(c[1]))
    if not cand: return []
    a0, l0 = cand[0]
    if len(l0) < 2: return [a0]
    # 与 a0 最正交且本身也有 ≥2 条平行线的方向
    best = None; bestD = 999
    for a, lines in cand[1:]:
        if a is None or len(lines) < 2: continue
        d = abs((a - a0) % 180); d = min(d, 180 - d)
        dd = abs(d - 90)
        if dd < bestD: bestD = dd; best = a
    if best is None:
        return [a0]
    return sorted([a0, best])

def fit_lines(segs, axis_deg, W, H):
    """把该方向的线段投影到法向量上，1D 贪心聚类成若干条直线。
    场地线常被 Hough 拆成 1~3 条长线段，用投影聚类比 RANSAC 点拟合可靠得多。"""
    rad = math.radians(axis_deg)
    n0, n1 = -math.sin(rad), math.cos(rad)
    proj = []
    seglens = []
    for (x1, y1, x2, y2) in segs:
        ang = angle_of(x1, y1, x2, y2)
        d = abs(ang - axis_deg) % 180
        d = min(d, 180 - d)
        if d > 22: continue
        ln = math.hypot(x2-x1, y2-y1)
        if ln < 15: continue
        proj.append(((x1+x2)/2)*n0 + ((y1+y2)/2)*n1)
        seglens.append(ln)
    if len(proj) < 2: return [], n0, n1
    order = sorted(range(len(proj)), key=lambda i: proj[i])
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
        cnt = len(c)
        avg = float(np.mean([seglens[i] for i in c]))
        # 至少 2 条线段，或单条超长线段（接近完整场地线）都算候选直线
        if cnt >= 2 or avg > 50:
            lines.append(float(np.median([proj[i] for i in c])))
    lines.sort()
    return lines, n0, n1

def line_support(Hinv, all_segs_mid, wdt, hgt):
    """投影 BWF 模板线→像素，统计 8px 内的线段中点支撑数（去重+跨度校验）。
    返回 [0,11]：6 横 + 5 竖模板线中被支撑的条数。"""
    lines = []
    for (t1, t2) in [((0.0, y), (W_, y)) for y in (0.0, 0.76, 4.72, 8.68, 12.64, 13.40)]:
        p1 = cv2.perspectiveTransform(np.float32([[t1]]), Hinv)[0][0]
        p2 = cv2.perspectiveTransform(np.float32([[t2]]), Hinv)[0][0]
        lines.append((p1, p2))
    for (t1, t2) in [((x, 0.0), (x, H_)) for x in (0.0, 0.46, 3.05, 5.64, 6.10)]:
        p1 = cv2.perspectiveTransform(np.float32([[t1]]), Hinv)[0][0]
        p2 = cv2.perspectiveTransform(np.float32([[t2]]), Hinv)[0][0]
        lines.append((p1, p2))
    sup = 0
    prev = []
    for (p1, p2) in lines:
        dx, dy = p2[0]-p1[0], p2[1]-p1[1]
        ln = math.hypot(dx, dy)
        if ln < 20: continue
        nx, ny = -dy/ln, dx/ln
        c = -(nx*p1[0] + ny*p1[1])
        dup = any(abs(nx-ox) + abs(ny-oy) < 0.15 and abs(c-oc) < 15 for (ox, oy, oc) in prev)
        if dup: continue
        hits = 0
        along = []
        for (mx, my, ml) in all_segs_mid:
            if abs(nx*mx + ny*my + c) < 8 and ml > 15:
                along.append(mx*nx + my*ny)
                hits += 1
        if hits >= 2:
            spread = (max(along) - min(along)) / max(ln, 1)
            if spread >= 0.15:
                sup += 1
                prev.append((nx, ny, c))
    return sup

def line_support_mask(Hinv, white):
    """用白线掩码像素覆盖率评估模板线支撑：沿线法向 6px 条带内白色像素占比。
    正确 H 下场地线像素就在投影位置 → 覆盖率>0.55。返回 [0,11]。"""
    hgt, wdt = white.shape[:2]
    lines = []
    for (t1, t2) in [((0.0, y), (W_, y)) for y in (0.0, 0.76, 4.72, 8.68, 12.64, 13.40)]:
        p1 = cv2.perspectiveTransform(np.float32([[t1]]), Hinv)[0][0]
        p2 = cv2.perspectiveTransform(np.float32([[t2]]), Hinv)[0][0]
        lines.append((p1, p2))
    for (t1, t2) in [((x, 0.0), (x, H_)) for x in (0.0, 0.46, 3.05, 5.64, 6.10)]:
        p1 = cv2.perspectiveTransform(np.float32([[t1]]), Hinv)[0][0]
        p2 = cv2.perspectiveTransform(np.float32([[t2]]), Hinv)[0][0]
        lines.append((p1, p2))
    yy, xx = np.nonzero(white)
    if len(yy) == 0: return 0
    sup = 0
    prev = []
    for (p1, p2) in lines:
        dx, dy = p2[0]-p1[0], p2[1]-p1[1]
        ln = math.hypot(dx, dy)
        if ln < 20: continue
        nx, ny = -dy/ln, dx/ln
        c = -(nx*p1[0] + ny*p1[1])
        dup = any(abs(nx-ox) + abs(ny-oy) < 0.15 and abs(c-oc) < 15 for (ox, oy, oc) in prev)
        if dup: continue
        d = np.abs(nx*xx + ny*yy + c)
        hit = np.nonzero(d < 6)[0]
        if len(hit) >= 8:
            tx, ty = dx/ln, dy/ln        # 切向量（沿线方向）
            along = tx*xx[hit] + ty*yy[hit]
            span = float(np.max(along) - np.min(along))
            if span > 0.35*ln:
                sup += 1
                prev.append((nx, ny, c))
    return sup

def run(img):
    hgt, wdt = img.shape[:2]
    white, gray = enhance_white(img)
    segs = detect_lines(white)
    if len(segs) < 10: return None, f"线段不足 {len(segs)}", 0.0
    axes = dominant_axes(segs, wdt, hgt)
    if len(axes) < 2: return None, f"主方向不足 {axes}", 0.0
    # 两条主方向分别拟合直线族 → 求交
    L1, n0, n1 = fit_lines(segs, axes[0], wdt, hgt)
    L2, m0, m1 = fit_lines(segs, axes[1], wdt, hgt)
    if len(L1) < 2 or len(L2) < 2:
        return None, f"直线不足 {len(L1)}/{len(L2)} 轴={[round(a) for a in axes]}", 0.0
    # 交点：直线 n·p = c 与 m·p = c2（克拉默）
    kps = []
    for c1 in L1:
        for c2 in L2:
            det = n0*m1 - n1*m0
            if abs(det) < 1e-6: continue
            x = (c1*m1 - n1*c2) / det
            y = (n0*c2 - c1*m0) / det
            if 0 <= x <= wdt and 0 <= y <= hgt:
                kps.append((x, y))
    if len(kps) < 5: return None, f"交点不足 {len(kps)}", 0.0
    # 十字校验：真交点是两条场地线的交叉——沿 L1 方向与沿 L2 方向都须有白像素延伸
    yy, xx = np.nonzero(white)
    if len(yy) == 0: return None, "白线掩码为空", 0.0
    d1 = np.abs(n0*xx + n1*yy - np.array(L1)[:, None])   # (lenL1, N) 每条 L1 线到所有白像素距离
    d2 = np.abs(m0*xx + m1*yy - np.array(L2)[:, None])
    kps2 = []
    for (x, y) in kps:
        # 该交点所在 L1 线 & L2 线的索引（距离最近）
        i1 = int(np.argmin([abs(n0*x + n1*y - c1) for c1 in L1]))
        i2 = int(np.argmin([abs(m0*x + m1*y - c2) for c2 in L2]))
        a = int(np.count_nonzero(d1[i1] < 8))   # 全图白像素中沿 L1 线附近的数
        b = int(np.count_nonzero(d2[i2] < 8))
        if a > 40 and b > 40:
            kps2.append((x, y))
    kps = kps2
    if len(kps) < 5: return None, f"十字交点不足 {len(kps)}", 0.0
    all_segs_mid = [((x1+x2)/2, (y1+y2)/2, math.hypot(x2-x1, y2-y1)) for (x1, y1, x2, y2) in segs]
    # 模板 RANSAC（BWF 4 角求解 + 全模板线支撑评分）
    tpl = [(0, 0), (W_, 0), (W_, H_), (0, H_)]
    rng = np.random.RandomState(42)
    n = len(kps)
    best = None; best_score = -1; best_corners = None; best_inl = []; best_sup = 0
    candidates = []
    iters = min(600, max(120, n*n))
    tol = wdt*0.09
    for _ in range(iters):
        idx = rng.choice(n, 4, replace=False)
        pts = [kps[i] for i in idx]
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        if (max(xs)-min(xs))*(max(ys)-min(ys)) < wdt*hgt*0.02: continue
        Hm, _ = cv2.findHomography(np.float32(pts), np.float32(tpl), method=0)
        if Hm is None: continue
        Hinv = np.linalg.inv(Hm)   # 模板→像素
        corners = []
        inl = []
        for t in tpl:
            pp = cv2.perspectiveTransform(np.float32([[t]]), Hinv)[0][0]
            dd = [math.hypot(pp[0]-k[0], pp[1]-k[1]) for k in kps]
            md = min(dd); i0 = int(np.argmin(dd))
            inl.append(md)
            corners.append(pp)
        score = sum(1 for d in inl if d < tol)
        # 几何合理性：四边形面积占比 + 长宽比（模板 13.4/6.1≈2.2，透视下 0.25~4）
        cx = np.array(corners)
        area = abs(cv2.contourArea(cx.astype(np.float32)))
        if area < wdt*hgt*0.10: continue
        ew = max(np.hypot(cx[1,0]-cx[0,0], cx[1,1]-cx[0,1]), np.hypot(cx[2,0]-cx[3,0], cx[2,1]-cx[3,1]))
        eh = max(np.hypot(cx[3,0]-cx[0,0], cx[3,1]-cx[0,1]), np.hypot(cx[2,0]-cx[1,0], cx[2,1]-cx[1,1]))
        if ew < 1 or eh/ew > 4 or eh/ew < 0.25: continue
        sup = line_support_mask(Hinv, white)
        # 主评：模板线支撑数 + 角点匹配
        total = sup*3 + score
        if total > best_score:
            best_score = total; best = Hm; best_corners = corners; best_inl = inl; best_sup = sup
        # 收集所有"支撑≥5 且面积合格"的候选场地（可能是多个相邻场地）
        if sup >= 5:
            c0 = tuple(int(round(v)) for v in corners[0])
            dup = False
            for (ch, _) in candidates:
                if math.hypot(ch[0]-c0[0], ch[1]-c0[1]) < 40:
                    dup = True; break
            if not dup:
                candidates.append((c0, Hm))
    if best is None or best_sup < 1 or (sum(1 for d in best_inl if d < tol)) < 2:
        return None, f"模板支撑线 {best_sup} 不足(需≥5)", 0.0
    # ---- 选场：对局场地包含球网（模板网线 y=6.70 白线支撑最强）----
    def net_support(Hinv):
        p1 = cv2.perspectiveTransform(np.float32([[(0.0, 6.70)]]), Hinv)[0][0]
        p2 = cv2.perspectiveTransform(np.float32([[(W_, 6.70)]]), Hinv)[0][0]
        dx, dy = p2[0]-p1[0], p2[1]-p1[1]
        ln = math.hypot(dx, dy)
        if ln < 20: return 0.0
        nx, ny = -dy/ln, dx/ln
        c = -(nx*p1[0]+ny*p1[1])
        d = np.abs(nx*yy2 + ny*xx2 + c)
        hit = np.nonzero(d < 6)[0]
        if len(hit) < 8: return 0.0
        tx, ty = dx/ln, dy/ln
        along = tx*xx2[hit] + ty*yy2[hit]
        return float(np.max(along) - np.min(along))/ln
    yy2, xx2 = np.nonzero(white)
    if candidates:
        cand2 = []
        for (c0, Hm) in candidates:
            Hinv = np.linalg.inv(Hm)
            ns = net_support(Hinv)
            cand2.append((ns, Hm))
        cand2.sort(key=lambda t: -t[0])
        bestNet, bestH = cand2[0]
        # 网线支撑显著高 → 选网场；否则退回原 best
        if bestNet > 0.35:
            best = bestH
            best_sup = line_support_mask(np.linalg.inv(bestH), white)
    # ---- 迭代精配准：反投影交点→筛选落在模板线上的点→重新拟合 H ----
    for _ in range(4):
        Hm = best
        Hinv = np.linalg.inv(Hm)
        good_src, good_dst = [], []
        for (kx, ky) in kps:
            t = cv2.perspectiveTransform(np.float32([[[kx, ky]]]), Hm)[0][0]
            tx, ty = t[0], t[1]
            # 模板坐标下离某条场地线 <0.45m（米）
            hline = [0.0, 0.76, 4.72, 8.68, 12.64, 13.40]
            vline = [0.0, 0.46, 3.05, 5.64, 6.10]
            dh = min(abs(ty - yy) for yy in hline)
            dv = min(abs(tx - xx) for xx in vline)
            if dh < 0.45 or dv < 0.45:
                good_src.append((kx, ky))
                good_dst.append((tx, ty))
        if len(good_src) < 5: break
        H2, mask = cv2.findHomography(np.float32(good_src), np.float32(good_dst),
                                      method=cv2.RANSAC, ransacReprojThreshold=0.35)
        if H2 is None: break
        sup2 = line_support_mask(np.linalg.inv(H2), white)
        if sup2 > best_sup:
            best = H2
            best_sup = sup2
    # 用最终 H 反投影 4 角
    Hinv = np.linalg.inv(best)
    best_corners = [cv2.perspectiveTransform(np.float32([[t]]), Hinv)[0][0] for t in tpl]
    n_corner = 0
    for c in best_corners:
        dd = [math.hypot(c[0]-k[0], c[1]-k[1]) for k in kps]
        if min(dd) < tol: n_corner += 1
    if best_sup < 5 or n_corner < 2:
        return None, f"模板支撑线 {best_sup} 精修后不足", 0.0
    # 置信度：支撑线/11 * 0.6 + 角点匹配/4 * 0.4
    conf = 0.6*(best_sup/11) + 0.4*(n_corner/4)
    return best_corners, None, conf

def main():
    d = os.path.dirname(os.path.abspath(__file__))
    files = sorted(f for f in os.listdir(d) if f.endswith('.jpg') and not f.endswith('_corners.jpg'))
    ok = 0
    print(f"{'文件':28s} {'结果':6s} {'置信度':8s} 信息")
    print("-"*72)
    for f in files:
        img, sc = load(os.path.join(d, f))
        try:
            corners, err, conf = run(img)
        except Exception as e:
            print(f"{f:28s} {'ERR':6s} {'--':8s} {e}"); continue
        if corners:
            ok += 1
            print(f"{f:28s} {'OK':6s} {conf*100:5.0f}% 4角={[(int(c[0]),int(c[1])) for c in corners]}")
            vis = img.copy()
            for c in corners:
                cv2.circle(vis, (int(c[0]), int(c[1])), 10, (0, 0, 255), -1)
                cv2.putText(vis, f"{conf:.2f}", (int(c[0])+12, int(c[1])-12),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)
            cv2.imwrite(os.path.join(d, f.replace('.jpg', '_corners.jpg')), vis)
        else:
            print(f"{f:28s} {'FAIL':6s} {'--':8s} {err}")
    print("-"*72)
    print(f"识别率: {ok}/{len(files)}")

if __name__ == '__main__':
    main()
