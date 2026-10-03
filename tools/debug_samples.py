#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调试：输出中间过程图，定位每张样本标定失败的原因"""
import glob, os
import cv2, numpy as np
import analyze_samples as A

os.makedirs("debug_out", exist_ok=True)
images = sorted(glob.glob("samples/*.jpg"))
for img_path in images:
    img = cv2.imread(img_path)
    if img is None or min(img.shape[:2]) < 800:
        continue
    name = os.path.basename(img_path).rsplit(".", 1)[0]
    h, w = img.shape[:2]
    s = min(1.0, A.INPUT / max(h, w))
    small = cv2.resize(img, (int(w * s), int(h * s)))
    sh, sw = small.shape[:2]
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    white = A.white_mask(gray, hsv[:, :, 1])

    vis = small.copy()
    vis[white] = (0, 0, 255)  # 掩码红色叠加

    hpts, vpts = A.scan_segments(white, sw, sh, int(sw * 0.04))
    hlines = A.fit_lines(hpts)
    vlines = A.fit_lines(vpts)
    for l in hlines + vlines:
        nx, ny, c, mx, my = l
        # 画线：中点 ± 300px 沿法线方向
        x1 = mx - 300 * ny; y1 = my + 300 * nx
        x2 = mx + 300 * ny; y2 = my - 300 * nx
        cv2.line(vis, (int(x1), int(y1)), (int(x2), int(y2)), (0, 255, 255), 2)

    reg = A.regress_court(small)
    if reg:
        corners, H = reg
        for (x, y) in corners:
            cv2.circle(vis, (int(x), int(y)), 8, (0, 165, 255), -1)
        cv2.putText(vis, "OK", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
    else:
        cv2.putText(vis, "FAIL", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 255), 2)
    cv2.putText(vis, f"h={len(hlines)} v={len(vlines)} wh={white.mean():.3f}", (10, 60),
                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
    out = f"debug_out/{name}_debug.jpg"
    cv2.imwrite(out, vis)
    print(f"[{name}] hlines={len(hlines)} vlines={len(vlines)} -> {'OK' if reg else 'FAIL'} -> {out}")
