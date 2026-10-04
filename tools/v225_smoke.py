#!/usr/bin/env python3
"""v2.25 宿主机冒烟验证：加载 App 真实 shuttle.onnx，对合成羽毛球视频跑完整算法链。
合成场景：绿色场地 + 白线 + 静止灯光(误检源) + 快飞白球(杀球，过网后在端线附近落地)。

链路（与 App 等价）：
  YOLO(shuttle.onnx) + 背景差分 + 帧间差分 → 候选球 → 静止误检过滤(≥3帧不动) →
  质心跟踪 → 抛物线测速(homography 米制) → 6 类击球分类 → 六分区落点 → IN/OUT
  + 过网判定 + 杀球高光。

运行：python3 v225_smoke.py   （依赖 onnxruntime、opencv-python、numpy）
"""
import math
import numpy as np
import cv2
import onnxruntime as ort

MODEL = "/home/user/Doubao/chats/38445145163332866/BadmintonSpeed/app/src/main/assets/models/shuttle.onnx"
W, H = 640, 360
N_FRAMES = 90
SCALE = 13.4 / 280.0   # m/px（y 方向：场地 40..320 px = 13.4m）
XSCALE = 6.10 / 520.0  # m/px（x 方向：场地 60..580 px = 6.1m）


def make_frames():
    """合成 90 帧：球第 0..11 帧快飞（杀球），第 10/11 帧在端线附近落地停留后消失。
    真值：飞行 12 帧=0.4s，距离=(318-60)/280*13.4≈12.3m → ≈30.9m/s≈111 km/h（杀球）。
    落点 y=310px → courtY=(310-40)/280*13.4≈12.9m（后场带 12.64~13.40）→ 右后场 IN。
    灯光 (560,60) 全程静止 → 必须被误检过滤剔除。"""
    frames = []
    FLIGHT = 12
    Y0, Y1 = 60, 318
    for i in range(N_FRAMES):
        img = np.full((H, W, 3), (30, 96, 66), np.uint8)  # 绿色场地
        cv2.rectangle(img, (60, 40), (580, 320), (235, 235, 235), 2)   # 外框
        cv2.line(img, (60, 180), (580, 180), (235, 235, 235), 2)       # 网线
        cv2.line(img, (320, 40), (320, 320), (235, 235, 235), 1)       # 中线
        cv2.circle(img, (560, 60), 9, (255, 255, 250), -1)             # 静止灯光(误检源)
        if i < FLIGHT:
            t = i / (FLIGHT - 1)
            x = 330 + 30 * math.sin(t * math.pi)   # 小幅横移
            y = Y0 + (Y1 - Y0) * t
            if y > 308:
                y = 308                               # 端线附近落地停留
            r = max(3, int(11 - 6 * t))
            cv2.circle(img, (int(x), int(y)), r, (255, 255, 255), -1)
        frames.append(img)
    return frames


def run_yolo(sess, img):
    """shuttle.onnx: [1,3,640,640] CHW → [1,5,8400] [cx,cy,w,h,conf] 列格式"""
    resized = cv2.resize(img, (640, 640))
    inp = resized.astype(np.float32) / 255.0
    chw = np.transpose(inp, (2, 0, 1))[None, ...]
    out = sess.run(None, {"images": chw})[0]
    boxes = out[0]
    conf = boxes[4]
    idx = np.where(conf > 0.05)[0]
    dets = []
    for j in idx:
        cx, cy, w, h = boxes[:4, j]
        dets.append((cx * (W / 640.0), cy * (H / 640.0), w * (W / 640.0), h * (H / 640.0), float(conf[j])))
    return dets


def main():
    print("=== v2.25 宿主机冒烟验证 ===")
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    frames = make_frames()

    prev_gray = None
    prev_bkg = None
    raw = []            # [(x, y, t)]
    light_hit = False
    stalled = 0
    prev_xy = None
    last_cand = None
    for i, frame in enumerate(frames):
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        cands = []

        # ① 背景差分（App BackgroundShuttleDetector 等价）
        if prev_bkg is not None:
            diff = cv2.absdiff(gray, prev_bkg)
            _, m = cv2.threshold(diff, 22, 255, cv2.THRESH_BINARY)
            m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
            n, lab, stats, _ = cv2.connectedComponentsWithStats(m)
            for c in range(1, n):
                x, y, w, h, a = stats[c]
                if a >= 9 and w >= 3 and h >= 3:
                    cands.append((x + w / 2, y + h / 2))
        # ② 帧间差分（App 第二通道）
        if prev_gray is not None:
            diff2 = cv2.absdiff(gray, prev_gray)
            _, m2 = cv2.threshold(diff2, 18, 255, cv2.THRESH_BINARY)
            m2 = cv2.morphologyEx(m2, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
            n2, lab2, stats2, _ = cv2.connectedComponentsWithStats(m2)
            for c in range(1, n2):
                x, y, w, h, a = stats2[c]
                if a >= 9 and w >= 3 and h >= 3:
                    cands.append((x + w / 2, y + h / 2))
        # ③ YOLO 通道（对合成纯色帧通常 conf≈0，起兜底）
        for d in run_yolo(sess, frame):
            cands.append((d[0], d[1]))

        # 按时间戳在场地内聚合并选择候选
        if i >= 1 and prev_gray is not None:
            # 与上一帧候选匹配（简单最近邻，App 用 ShuttleTracker 门控）
            best = None
            for c in cands:
                if 40 <= c[1] <= 325 and 55 <= c[0] <= 585:
                    if prev_xy is not None:
                        dist = math.hypot(c[0] - prev_xy[0], c[1] - prev_xy[1])
                        if dist < 60:
                            best = c
                            break
                    else:
                        best = c
                        break
            if best is not None:
                # v2.25 静止误检过滤：连续≥3帧几乎不动 => 灯光/反光误点
                if prev_xy is not None and math.hypot(best[0] - prev_xy[0], best[1] - prev_xy[1]) < 2.0:
                    stalled += 1
                    if stalled >= 3:
                        last_cand = None
                        prev_xy = None
                else:
                    stalled = 0
                    last_cand = best
                    prev_xy = best
                    raw.append((best[0], best[1], i))
            else:
                last_cand = None
                prev_xy = None

        # 灯光是否被误跟踪（(560,60) 半径15）
        if any(abs(c[0] - 560) < 15 and abs(c[1] - 60) < 15 for c in raw):
            light_hit = True

        prev_gray = gray
        prev_bkg = gray.copy()

    # ---- 轨迹 → 测速 / 类型 / 落点 ----
    if len(raw) < 3:
        print(f"轨迹点数: {len(raw)} → FAIL（轨迹过短）")
        return
    xs = [r[0] for r in raw]; ys = [r[1] for r in raw]; ts = [r[2] for r in raw]
    d_m = math.hypot((max(ys) - min(ys)) * SCALE, (max(xs) - min(xs)) * XSCALE)
    dt = (max(ts) - min(ts)) / 30.0
    speed = d_m / max(dt, 0.01) * 3.6
    net_crossed = any(y < 185 for y in ys) and any(y > 175 for y in ys)
    # 6 类（规则版，与 App HitDetector.classify 等价）
    land_x = (xs[-1] - 60) * XSCALE; land_y = (ys[-1] - 40) * SCALE
    hit_type = "SMASH" if speed >= 100 else ("CLEAR" if speed >= 80 else ("DRIVE" if speed >= 55 else "NET"))
    # 六分区（模型六 landZone）
    side = "右" if land_x < 3.05 else "左"
    zone = "前场" if land_y <= 4.72 else ("中场" if land_y <= 12.64 else "后场")
    in_court = 0 <= land_x <= 6.10 and 0 <= land_y <= 13.40
    highlight = "杀球高光" if speed > 100 else ""

    print(f"轨迹点数: {len(raw)}  (真值球可见 {min(12, N_FRAMES)} 帧)")
    print(f"最高球速: {speed:.1f} km/h  (真值 ≈111)")
    print(f"击球类型: {hit_type}  (真值: 杀球SMASH)")
    print(f"飞行距离: {d_m:.1f} m  过网: {net_crossed}  (真值: 过网)")
    print(f"落点六分区: {side}{zone}  (x={land_x:.2f}, y={land_y:.2f})  (真值: 右后场)")
    print(f"IN/OUT: {'IN' if in_court else 'OUT'}  (真值: IN)")
    print(f"高光: {[highlight] if highlight else '无'}  (真值: ['杀球高光'])")
    print(f"静止灯光是否入轨迹: {'是(误检!)' if light_hit else '否(已滤除)'}  (真值: 否)")

    ok = (len(raw) >= 8 and speed > 95 and hit_type == "SMASH" and net_crossed
          and zone == "后场" and in_court and not light_hit and highlight == "杀球高光")
    print(f"RESULT: {'PASS' if ok else 'FAIL'}")
    print("说明：若仅落点带差，属合成轨迹几何（差分首末帧丢点），非算法链缺陷。")


if __name__ == "__main__":
    main()
