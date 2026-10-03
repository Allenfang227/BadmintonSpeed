#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
训练集标注/修正工具 (annotate.py)
================================================
显示一张图，按顺序点击 12 个关键点（顺序 = 下方 POINTS 提示），
已有点显示为绿圈，当前点红色，可撤销（右键/Backspace），完成自动保存 json。

用法：
  python3 annotate.py samples/sample_08.jpg              # 新标注
  python3 annotate.py samples/sample_08.jpg out_labels/sample_08.json   # 修正已有
依赖：pip install opencv-python numpy

点顺序与 BWF 模板（米制坐标见 train_keypoints.py）：
  tl_out 左上外角 → tr_out 右上外角 → br_out 右下外角 → bl_out 左下外角
  → net_l 左网柱底 → net_r 右网柱底 → servl_tl → servl_tr → servl_bl → servl_br
  → mid_t → mid_b
角出画面（不可见）的：跳过不点（按 Esc 跳过当前点）。
每张图至少点 4 个点即可训练。
"""
import argparse
import json
import os
import sys

import cv2

POINTS = ["tl_out", "tr_out", "br_out", "bl_out",
          "net_l", "net_r", "servl_tl", "servl_tr",
          "servl_bl", "servl_br", "mid_t", "mid_b"]
R = 12  # 画布缩放：长边


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image", help="图片路径")
    ap.add_argument("out_json", nargs="?", help="输出 json（默认 ./labels/<图名>.json）")
    args = ap.parse_args()

    img = cv2.imread(args.image)
    if img is None:
        sys.exit("无法读取图片")
    h, w = img.shape[:2]
    s = R / max(h, w)
    disp = cv2.resize(img, (int(w * s), int(h * s)))
    SH, SW = disp.shape[:2]

    out = args.out_json
    if not out:
        os.makedirs("labels", exist_ok=True)
        out = f"labels/{os.path.basename(args.image).rsplit('.', 1)[0]}.json"

    pts = {}  # name -> (x, y) 原图坐标
    existing = {}
    if os.path.exists(out):
        with open(out) as f:
            existing = json.load(f).get("points", {})
        for k, v in existing.items():
            pts[k] = (v["x"], v["y"])

    idx = 0  # 当前要点的点序号
    skip = set()  # 跳过的点（不可见）

    def draw():
        d = disp.copy()
        for k, (x, y) in pts.items():
            cv2.circle(d, (int(x * s), int(y * s)), 8, (0, 255, 0), 2)
            cv2.putText(d, k, (int(x * s) + 10, int(y * s)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)
        if idx < len(POINTS):
            name = POINTS[idx]
            tip = f"第{idx + 1}/{len(POINTS)}: {name} (点击标注; 右键=跳过; Esc=退出)"
            cv2.putText(d, tip, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
            if name in pts:
                x, y = pts[name]
                cv2.circle(d, (int(x * s), int(y * s)), 8, (0, 0, 255), 2)
        cv2.imshow("annotate", d)

    def on_click(event, x, y, flags, param):
        nonlocal idx
        if event == cv2.EVENT_LBUTTONDOWN:
            name = POINTS[idx]
            pts[name] = (int(x / s), int(y / s))
            idx += 1
            draw()
        elif event == cv2.EVENT_RBUTTONDOWN:
            idx += 1  # 跳过当前点（不可见）
            draw()

    cv2.imshow("annotate", disp)
    cv2.setMouseCallback("annotate", on_click)
    draw()
    print(f"标注 {args.image} → {out}；已有点 {len(pts)} 个；右键跳过不可见点，Esc 退出保存")
    while True:
        k = cv2.waitKey(30) & 0xFF
        if k == 27:  # Esc
            break
        if idx >= len(POINTS):
            print("12 点已点完（含跳过），按 Esc 保存")
    cv2.destroyAllWindows()

    ann = {"image": os.path.basename(args.image), "W": w, "H": h, "points": {
        k: {"x": v[0], "y": v[1]} for k, v in pts.items()
    }}
    with open(out, "w") as f:
        json.dump(ann, f, indent=1, ensure_ascii=False)
    print(f"已保存 {out}：可见关键点 {len(pts)}/12（≥4 即可训练）")


if __name__ == "__main__":
    main()
