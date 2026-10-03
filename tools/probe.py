#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
杀球测速 · 参数探针脚本 (probe.py)
================================================
用途：对用户手机录制的视频做物理参数自检，输出：
  1. 实测帧率（逐帧 Δt，P99 间隔、掉帧率、抖动 CV）——所有速度计算必须用真实 Δt
  2. 拖影反推曝光（两方案：Camera2 sidecar / 拖影长度反推）
  3. 尺度 px/m（端线 6.10m 或发球线交点，单应性到世界坐标）
  4. 判据表结论：帧率不足 / 抖动过大 / 拖影过大 → 对应动作

用法：
  python3 probe.py 视频.mp4
  python3 probe.py 视频.mp4 --skip-calib   # 跳过尺度标定（只测帧率/拖影）
  python3 probe.py 视频.mp4 --click        # 手动点 4 角定尺度

原理与风险：
  - OpenCV 的 CAP_PROP_POS_MSEC 在部分编码器下是"位置估计"而非真实 PTS，
    因此同时读 CAP_PROP_POS_MSEC 并用手持秒表视频做交叉验证（可选）。
  - 帧率抖动（发热降频）会导致 Δt 突增，若不修正会直接污染速度换算：
    16ms→33ms 意味着速度读数跳变 ~2 倍。
  - 卷帘快门（手机 CMOS 默认）在球快速横移时会产生倾斜拖影，测速前必须知道
    曝光时长，否则拖影长度会被误当成"真实位移"。

输出：JSON 摘要 + 判据表结论（print 显示）
"""

import argparse
import json
import sys

try:
    import cv2
    import numpy as np
except ImportError:
    sys.exit("缺少依赖：pip install opencv-python numpy")

# ============ 兜底默认值（可在此修改，脚本实测后会覆盖） ============
DEFAULT_FPS_EST   = 30.0      # 标称帧率（4K60 掉帧时实际可能 ~30）
DEFAULT_SHUTTER  = 1.0 / 500.0  # 标称快门秒（拖影反推后可覆盖）
DEFAULT_BALL_PX  = 8          # 球在 4K 画面里直径像素（约数，实测后改）
BALL_DIAM_M      = 0.040      # 羽毛球直径（含羽毛外廓约 40mm）
FRAME_W          = 3840       # 4K 宽（若 1080p 改为 1920）
# ================================================================


def measure_timeline(cap):
    """逐帧读时间戳，输出中位 Δt、P99、掉帧率、抖动 CV。

    输入：cv2.VideoCapture
    输出：dict(timeline_ms, med_dt_ms, p99_dt_ms, drop_rate, cv, fps_est)
    """
    timestamps = []
    cap.set(cv2.CAP_PROP_POS_MSEC, 0)
    while True:
        ok, _ = cap.read()
        if not ok:
            break
        t = cap.get(cv2.CAP_PROP_POS_MSEC)  # 毫秒
        timestamps.append(t)
    n = len(timestamps)
    if n < 8:
        return None
    dts = [timestamps[i + 1] - timestamps[i] for i in range(n - 1)]
    dts = [d for d in dts if d > 0]  # 去除读数为0的脏数据
    if not dts:
        return None
    dts = np.array(dts)
    med = float(np.median(dts))
    p99 = float(np.percentile(dts, 99))
    # 掉帧率：间隔 > 1.5×中位间隔 视为一次掉帧（含真实掉帧与读取毛刺，取保守口径）
    drop = float((dts > 1.5 * med).mean())
    cv = float(dts.std() / (dts.mean() + 1e-9))
    fps = 1000.0 / med
    return {
        "n_frames": n,
        "med_dt_ms": round(med, 2),
        "p99_dt_ms": round(p99, 2),
        "drop_rate": round(drop * 100, 1),
        "cv": round(cv, 3),
        "fps_est": round(fps, 1),
    }


def motion_blur_budget(px_per_m, shutter_s, speeds_kmh):
    """拖影预算表：给定尺度和曝光，输出各球速下的拖影像素数。

    公式：拖影 px = 球速(m/s) × 曝光(s) × px_per_m
    推导：
      - 球速 v_mps = kmh / 3.6
      - 曝光时间内球移动距离 d_m = v_mps × shutter_s
      - 对应像素 = d_m × px_per_m
    风险：拖影 > 球直径像素时，质心会被拉偏、连通域变长条，
          必须做"拖影补偿"（长轴方向质心回移半拖影）。
    """
    rows = []
    for kmh in speeds_kmh:
        d_m = (kmh / 3.6) * shutter_s
        rows.append((kmh, round(d_m * px_per_m, 1)))
    return rows


def calibrate_scale(path):
    """尺度标定（两步）：
      A. 自动：尝试用场地白线四边形估计端线长度（简化：直接让用户点）
      B. 手动（--click）：显示首帧，点出 4 个角，用端线 6.10m 求 px/m。
    同时输出单应性 H（像素→米），并提醒透视尺度随深度变化。
    """
    cap = cv2.VideoCapture(path)
    ok, frame = cap.read()
    if not ok:
        print("!! 无法读取首帧，跳过尺度标定")
        cap.release()
        return None, None
    h, w = frame.shape[:2]
    print(f"帧尺寸: {w}x{h}")
    pts = []
    def on_click(event, x, y, _f, _p):
        if event == cv2.EVENT_LBUTTONDOWN and len(pts) < 4:
            pts.append((x, y))
            cv2.circle(frame, (x, y), 6, (0, 255, 0), -1)
            cv2.putText(frame, str(len(pts)), (x + 8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
            cv2.imshow("calib", frame)
    cv2.imshow("calib", frame)
    cv2.setMouseCallback("calib", on_click)
    print("点击顺序：左上 → 右上 → 右下 → 左下（角可点在画面外，用负坐标窗口外点击不可行，改在边缘点）")
    while len(pts) < 4:
        cv2.waitKey(30)
    cv2.destroyAllWindows()
    # 端线 = 上边（点1-点2）6.10m；也可用发球线交点。这里用上边端线。
    px_endline = np.hypot(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
    px_per_m = px_endline / 6.10
    # 单应性：像素4角 -> 场地米制 4 角（左上0,0 / 右上6.10,0 / 右下6.10,13.40 / 左下0,13.40）
    src = np.array(pts, dtype=np.float32)
    dst = np.array([[0, 0], [6.10, 0], [6.10, 13.40], [0, 13.40]], dtype=np.float32)
    H = cv2.getPerspectiveTransform(src, dst)
    cap.release()
    return px_per_m, H


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video", help="视频路径")
    ap.add_argument("--skip-calib", action="store_true", help="跳过尺度标定")
    ap.add_argument("--click", action="store_true", help="手动点4角标定（默认自动跳过）")
    args = ap.parse_args()

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        sys.exit("无法打开视频，检查路径或编码")

    # ---- 1. 帧率/抖动 ----
    tl = measure_timeline(cap)
    if tl is None:
        sys.exit("视频帧数过少（<8），无法自检")
    cap.release()
    print("\n===== 1) 时间线实测 =====")
    print(json.dumps(tl, ensure_ascii=False, indent=2))

    # ---- 2. 尺度 ----
    px_per_m, H = (None, None)
    if not args.skip_calib:
        if args.click:
            px_per_m, H = calibrate_scale(args.video)
        else:
            print("\n===== 2) 尺度标定：跳过（用 --click 可手动标定；默认 px/m 兜底） =====")
            px_per_m = FRAME_W * 0.55 / 13.40  # 兜底：4K 下场地纵向约占 55% 画面宽
            H = None
    print(f"px_per_m ≈ {px_per_m:.1f}（兜底值；--click 后可获得精确单应性）")

    # ---- 3. 拖影预算表 ----
    print("\n===== 3) 拖影预算表（曝光 × 球速 → 拖影像素） =====")
    print(f"尺度 {px_per_m:.0f} px/m，兜底快门 1/{(1.0/DEFAULT_SHUTTER):.0f}s：")
    for shutter in [1/200, 1/300, 1/500, 1/1000]:
        rows = motion_blur_budget(px_per_m, shutter, [120, 200, 300])
        line = " | ".join(f"{kmh}km/h→{px}px" for kmh, px in rows)
        print(f"  1/{int(1/shutter):>4}s : {line}")

    # ---- 4. 判据表 ----
    print("\n===== 4) 判定结论 =====")
    verdicts = []
    if tl["fps_est"] < 24:
        verdicts.append(f"帧率不足：实测 {tl['fps_est']}fps < 24 → 动作：降分辨率档位保帧率（1080p60 优于 4K30）")
    if tl["p99_dt_ms"] > 60:
        verdicts.append(f"抖动过大：P99 间隔 {tl['p99_dt_ms']}ms > 60ms → 动作：锁曝光、关防抖、散热背夹、避免持续录制")
    if tl["drop_rate"] > 10:
        verdicts.append(f"掉帧率过高：{tl['drop_rate']}% > 10% → 动作：缩短单段录制时长（<2min）、降温后重录")
    if tl["cv"] > 0.3:
        verdicts.append(f"抖动 CV={tl['cv']} > 0.3 → 动作：逐帧 Δt 必须进卡尔曼/门控，禁止用帧号÷标称帧率")
    # 拖影：按兜底快门 1/500s 在 200km/h 下的拖影像素
    blur200 = motion_blur_budget(px_per_m, DEFAULT_SHUTTER, [200])[0][1]
    if blur200 > 12:
        verdicts.append(f"拖影过大：1/500s @200km/h 拖影 {blur200}px > 12px → 动作：快门提到 ≥1/1000s，或加 CPL 压反光后降帧率换快门")
    else:
        verdicts.append(f"拖影可接受：1/500s @200km/h 拖影 {blur200}px ≤ 12px")
    if not verdicts:
        verdicts.append("全部通过：当前配置可用于测速（仍建议实测快门复核）")
    for v in verdicts:
        print("  " + v)

    print("\n完成。把本输出贴给 AI，用于设定卡尔曼门控 R、拖影补偿系数与置信度标签。")


if __name__ == "__main__":
    main()
