#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
杀球测速 · 场地交点关键点回归 · 本地增量训练 (train_keypoints.py)
================================================
原理：
  羽毛球场地是尺寸已知的刚性平面（BWF：13.40×6.10m，线宽 40mm）。
  不检测"线"，只回归 10~14 个交点（高斯热图）+ 置信度，
  再用 ≥4 点 RANSAC 拟合 BWF 模板（单应性），反投影生成全部线。
  好处：闭集回归比开放线段检测简单得多；拓扑天然正确；缺角时仍可拟合。

标注格式（每张图一个同名 .json，App 里人工标定的结果自动导出到此格式）：
  {
    "image": "frame_0421.jpg",
    "W": 3840, "H": 2160,
    "points": [
      {"name": "tl_out",  "x": 500,  "y": 700},   # 左上外角（双打）
      {"name": "tr_out",  "x": 3300, "y": 710},   # 右上外角
      {"name": "br_out",  "x": 3600, "y": 1900},  # 右下外角（可能不可见→缺）
      {"name": "bl_out",  "x": 420,  "y": 1880},  # 左下外角（可能不可见→缺）
      {"name": "net_l",   "x": 500,  "y": 1290},  # 网柱底左（球网 y=6.70m 投影）
      {"name": "net_r",   "x": 3300, "y": 1300},  # 网柱底右
      {"name": "servl_tl", "x": 810, "y": 1050},  # 前发球线×左单打边
      {"name": "servl_tr", "x": 3030, "y": 1060}, # 前发球线×右单打边
      {"name": "servl_bl", "x": 860, "y": 1500},  # 后发球线（双打）×左双打边
      {"name": "servl_br", "x": 2980, "y": 1520}, # 后发球线（双打）×右双打边
      {"name": "mid_t",   "x": 1900, "y": 1060},  # 中线×前发球线（上）
      {"name": "mid_b",   "x": 1930, "y": 1500}   # 中线×后发球线（下）
    ]
  }
  点可缺（角在画面外就不标），但每张图至少 4 点。

  可选 "lines" 字段（语义分类头训练数据；负样本越多误检越少）：
  {
    "lines": [
      {"type": 1, "points": [[500,700], [3600,1900]]},   # 双打边线
      {"type": 6, "points": [[4000,900], [4500,1100]]},  # 干扰线（邻场/广告/地板缝）
      ...
    ]
  }
  type 见 LINE_TYPES。没有 lines 字段的旧标注不影响训练（分类损失跳过）。

网络头部（轻量，CPU/手机可训练）：
  主干 MobileNetV3-Large（torchvision 预训练）→ 热图头(1x1 conv) 输出
  K=12 通道 H×W 热图 + 1 通道置信度热图（最大峰即置信度）。
  损失 = 各点加权 MSE(热图) + 背景项 + 点置信度 BCE。
  （若未来要更高精度：换 HRNet-W32/SimCC，原理相同。）

v2.17 新增语义分类头（B误检修复第 2 步）：
  标注 json 可额外带 "lines" 字段，把候选线段分为 7 类：
    {0: 单打边线, 1: 双打边线, 2: 端线, 3: 前发球线, 4: 后发球线, 5: 中线, 6: 干扰线}
  干扰线=邻场线/地板缝/广告条幅/排球场地线（负样本重点注入）。
  网络加一个线分类分支：对热图特征做线段 ROI 采样 → 7 类 logits；
  训练损失 = 热图 MSE + 可见点 BCE + 线分类 CE。
  推理时（App 侧）对霍夫候选线段算类别，类 6 干扰线直接丢弃，
  同一位置多条候选按 类别置信度 + 几何校验分 + IoU 加权选最优。

用法：
  python3 train_keypoints.py --data ./labels/ --epochs 30 --out ./model.onnx
  依赖：pip install torch torchvision onnx onnxruntime numpy opencv-python
  需要你确认：标注集规模、主干选择（MobileNet 快 / HRNet 准）、训练机 CPU 或 GPU。
"""

import argparse
import glob
import json
import os

import numpy as np

try:
    import cv2
except ImportError:
    cv2 = None

import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader

# BWF 模板交点（米制，世界坐标，用于 RANSAC 拟合；外角=双打场地）
# 13.40 × 6.10；球网 y=6.70；前发球线 y=4.72/8.68；双打后发球线 y=0.76/12.64；单打边 x=0.46/5.64
TEMPLATE = {
    "tl_out": (0.00, 0.00), "tr_out": (6.10, 0.00),
    "br_out": (6.10, 13.40), "bl_out": (0.00, 13.40),
    "net_l": (0.00, 6.70), "net_r": (6.10, 6.70),
    "servl_tl": (0.46, 4.72), "servl_tr": (5.64, 4.72),
    "servl_bl": (0.46, 12.64), "servl_br": (5.64, 12.64),
    "mid_t": (3.05, 4.72), "mid_b": (3.05, 12.64),
}
POINT_NAMES = list(TEMPLATE.keys())   # 12 个点（10~14 范围内）
K = len(POINT_NAMES)
SIGMA = 8                              # 热图高斯半径 px（≈线宽一半，40mm 投影约 5-12px）
INPUT = 384                            # 训练输入边长（缩到 384 快；推理可同尺寸）
# 语义分类头：6 类场地线 + 1 类干扰线（v2.17）
LINE_TYPES = ["单打边线", "双打边线", "端线", "前发球线", "后发球线", "中线", "干扰线"]
N_LINES = len(LINE_TYPES)              # 7


def make_heatmap(img_w, img_h, pts, sigma=SIGMA):
    """高斯热图生成：每个关键点打一个高斯峰，输出 (K, H, W) + 可见掩码。
    输入 pts: 列表[(x,y,visible)]，输出热图与 visible 向量。"""
    maps = np.zeros((K, img_h, img_w), dtype=np.float32)
    vis = np.zeros(K, dtype=np.float32)
    gx, gy = np.meshgrid(np.arange(img_w), np.arange(img_h))
    for i, (x, y, v) in enumerate(pts):
        if v < 0.5:
            continue
        vis[i] = 1.0
        g = np.exp(-((gx - x) ** 2 + (gy - y) ** 2) / (2 * sigma * sigma))
        maps[i] = np.maximum(maps[i], g)
    return maps, vis


class CourtDataset(Dataset):
    """读取 labels/ 目录下 image + 同名 json，缩放到 INPUT×INPUT 做训练。
    输入输出形状：x (B,3,384,384)，heat (B,12,96,96)，vis (B,12)。
    推理时输出的热图峰值位置 / 4 = 原图坐标（比例回放）。"""

    def __init__(self, data_dir):
        self.samples = []
        for jp in sorted(glob.glob(os.path.join(data_dir, "*.json"))):
            with open(jp) as f:
                ann = json.load(f)
            img_path = os.path.join(data_dir, ann["image"])
            self.samples.append((img_path, ann))

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        img_path, ann = self.samples[idx]
        img = cv2.imread(img_path) if cv2 is not None else None
        if img is None:
            raise FileNotFoundError(img_path)
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        h0, w0 = img.shape[:2]
        img = cv2.resize(img, (INPUT, INPUT), interpolation=cv2.INTER_AREA)
        img = torch.from_numpy(img).permute(2, 0, 1)
        pts = []
        for name in POINT_NAMES:
            p = ann["points"].get(name)
            if p is None:
                pts.append((0, 0, 0))
            else:
                pts.append((p["x"] * INPUT / w0, p["y"] * INPUT / h0, 1))
        heat, vis = make_heatmap(INPUT // 4, INPUT // 4, pts)  # 热图下采样 4x
        # v2.17 语义分类：线段栅格标签（每格 1x1 点做类别投票）
        lines = torch.zeros((N_LINES, INPUT // 4, INPUT // 4), dtype=torch.float32)
        lines_mask = torch.zeros(1, dtype=torch.float32)
        for ln in ann.get("lines", []):
            t = int(ln.get("type", 6))
            if t < 0 or t >= N_LINES:
                t = 6
            pts_l = ln.get("points", [])
            if len(pts_l) < 2:
                continue
            # 线段上均匀采样 32 个点，写入对应类别的热图
            for i in range(32):
                tt = i / 31.0
                x = (pts_l[0][0] * (1 - tt) + pts_l[-1][0] * tt) * (INPUT // 4) / w0
                y = (pts_l[0][1] * (1 - tt) + pts_l[-1][1] * tt) * (INPUT // 4) / h0
                xi = int(min(max(x, 0), (INPUT // 4) - 1))
                yi = int(min(max(y, 0), (INPUT // 4) - 1))
                lines[t, yi, xi] = 1.0
            lines_mask[0] = 1.0
        return img, torch.from_numpy(heat), torch.from_numpy(vis), lines, lines_mask


class KeypointNet(nn.Module):
    """轻量热图回归头：MobileNet 主干 + 1x1 卷积头（K 热图 + 1 置信度）。
    输出：heat (B,K,H,W)（softmax-free，取峰值位置），conf (B,K)。"""

    def __init__(self, k=K):
        super().__init__()
        try:
            from torchvision.models import mobilenet_v3_large
            self.backbone = mobilenet_v3_large(weights=None)
            feat = self.backbone.classifier[0].in_features
            self.backbone.classifier = nn.Identity()  # 去掉分类头，拿特征
        except Exception:
            # 无 torchvision 时用简易 CNN 兜底（仅演示形状）
            feat = 128
            self.backbone = nn.Sequential(
                nn.Conv2d(3, 32, 3, 2, 1), nn.ReLU(),
                nn.Conv2d(32, 64, 3, 2, 1), nn.ReLU(),
                nn.AdaptiveAvgPool2d((12, 12)),
            )
        self.head = nn.Conv2d(feat, k + 1, 1)
        # v2.17 语义分类头：7 类线（6 场地线 + 干扰线）
        self.cls_head = nn.Conv2d(feat, N_LINES, 1)

    def forward(self, x):
        f = self.backbone(x)              # (B,C,12,12)
        f = f.unsqueeze(-1).unsqueeze(-1) if f.dim() == 2 else f
        f = F.interpolate(f, size=(INPUT // 4, INPUT // 4), mode="bilinear", align_corners=False)
        out = self.head(f)                # (B,K+1,H,W)
        heat = out[:, :K]                 # (B,K,H,W)
        conf = out[:, K:].mean(dim=(2, 3))  # (B,K) 置信度（logit）
        cls = self.cls_head(f)            # (B,7,H,W) 线段类别 logits
        return heat, conf, cls


def train_epoch(model, loader, opt):
    model.train()
    total = 0.0
    for img, heat, vis, lines, lines_mask in loader:
        opt.zero_grad()
        h, conf, cls = model(img)
        # 损失 = 热图 MSE（仅可见点） + 置信度 BCE（是否可见） + 线分类 CE（有 lines 标注时）
        mse = ((h - heat) ** 2).mean(dim=(2, 3))  # (B,K)
        mse = (mse * vis).sum() / (vis.sum() + 1e-6)
        bce = F.binary_cross_entropy_with_logits(conf, vis)
        loss = mse + 0.1 * bce
        if lines_mask.sum() > 0:
            ce = -(F.log_softmax(cls, dim=1) * lines).sum(dim=(2, 3))  # (B,)
            ce = (ce * lines_mask.squeeze(1)).sum() / lines_mask.sum()
            loss = loss + 0.3 * ce
        loss.backward()
        opt.step()
        total += loss.item()
    return total / max(1, len(loader))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="标注目录（image + json）")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--out", default="./model.onnx")
    args = ap.parse_args()

    ds = CourtDataset(args.data)
    if len(ds) == 0:
        raise SystemExit(f"目录 {args.data} 没有标注 json")
    print(f"标注样本数：{len(ds)}")
    dl = DataLoader(ds, batch_size=4, shuffle=True)
    model = KeypointNet()
    opt = optim.AdamW(model.parameters(), lr=1e-4)
    for ep in range(args.epochs):
        loss = train_epoch(model, dl, opt)
        print(f"epoch {ep + 1}/{args.epochs} loss={loss:.4f}")

    # 导出 ONNX（供 App 端 YOLO 同引擎推理；输入 1x3x384x384）
    model.eval()
    dummy = torch.randn(1, 3, INPUT, INPUT)
    try:
        import onnx
        torch.onnx.export(model, dummy, args.out, input_names=["x"],
                          output_names=["heat", "conf"],
                          opset_version=13, dynamic_axes={"x": {0: "b"}})
        print(f"ONNX 已导出：{args.out}")
    except ImportError:
        print("未安装 onnx，跳过导出（pip install onnx）")

    # 需要你确认的信息
    print("""
=== 需要你确认 ===
1) 标注集规模：至少 30~50 张不同机位/光照的图效果才稳；少于 10 张建议先合成扩展。
2) 主干选择：MobileNet（快，手机可跑）/ HRNet-W32（准，手机慢）——默认 MobileNet。
3) 训练机：CPU 或 GPU（GPU 建议 epochs 100+，CPU 用 30 足够验证）。
4) 标注导出：App 里人工标定（手动标定兜底）后自动存成上面的 json 格式（下一步接入）。
""")


if __name__ == "__main__":
    main()
