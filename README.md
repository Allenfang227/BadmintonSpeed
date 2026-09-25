# 🏸 BadmintonSpeed · 杀球测速 APP

基于 **OpenCV 4.10** + **原生 Android** 开发的羽毛球杀球测速应用。所有图像识别、矩阵运算、轨迹重建均在 **本地运行**，不上传任何视频到云端。

## ✨ 功能

### 核心测速流程
1. **上传视频测速** — 上传本地打球视频，自动分析杀球速度
2. **前置校验** — 检测鱼眼/广角畸变、推荐 30FPS、检测补帧/抽帧
3. **多模块并行检测**：
   - 🏟️ 场地基准检测：Canny 边缘 → 霍夫直线 → RANSAC 拟合 → 单应矩阵
   - 🏸 羽毛球检测：背景差分（MOG2）+ 圆形度 + 白色亮度过滤
   - 🧍 人员检测：HOG 特征 + SVM 分类 + NMS
   - 🎯 击球点检测：光流追踪球拍 + 速度极值检测
   - ⚡ 球速计算：连续帧追踪 + 单应矩阵空间重建 + km/h 换算

### 结果输出
- 最高速度 / 平均速度 (km/h)
- 击球类型（杀球 / 点杀 / 高远 / 吊球 / 放网）
- 界内 / 界外判定
- 3D 羽毛球轨迹重建

### 侧边导航栏
- 测速模式 / 历史记录 / 画面设置 / 使用教程 / 我的 / 关于 / 退出注销

### 画面设置
- 虚拟场地网格（黄色）
- 出拍速度 / 实时速度 / 击球类型 / 界内界外 开关

### UI 规范
- 深色黑底 `#0A110D` + 荧光绿 `#39FF14`
- 卡片圆角 + 模块分块
- 右侧算法状态面板 + 绿色进度条

## 🛠️ 技术栈

| 模块 | 技术 |
|------|------|
| 平台 | Android (minSdk 24, targetSdk 34) |
| 语言 | Java |
| 图像处理 | OpenCV 4.10 Android SDK |
| 数据库 | SQLite (历史记录) |
| 构建 | Gradle 8.14 + AGP 8.7 |

## 📦 下载 APK

👉 前往 [Releases](https://github.com/Allenfang227/BadmintonSpeed/releases) 页面下载最新版 APK，直接安装即可使用。

## 🏗️ 本地构建

```bash
# 1. 安装 OpenCV Android SDK 并放置到本地
#    下载: https://github.com/opencv/opencv/releases (opencv-4.10.0-android-sdk.zip)
#    解压后将 sdk/java/src/org 复制到 app/src/main/java/org
#    将 sdk/native/libs/*/libopencv_java4.so 复制到 app/src/main/jniLibs/*/

# 2. 配置 Android SDK
export ANDROID_HOME=/path/to/android-sdk

# 3. 构建
gradle assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 📁 项目结构

```
app/src/main/java/com/badminton/speed/
├── MainActivity.java              # 主入口 + DrawerLayout 侧边栏
├── OpenCVInit.java                # OpenCV 加载
├── core/
│   ├── VideoPreValidator.java     # 视频前置校验
│   ├── CourtDetector.java         # 场地基准检测
│   ├── ShuttleDetector.java       # 羽毛球检测
│   ├── PlayerDetector.java        # 人员检测
│   ├── HitDetector.java           # 击球点检测
│   ├── SpeedCalculator.java       # 球速计算 + 3D 重建
│   └── DetectPipeline.java        # 流水线调度
├── ui/
│   ├── SpeedModeFragment.java     # 测速模式选择
│   ├── UploadFragment.java        # 上传视频测速（核心）
│   ├── HistoryFragment.java       # 历史记录
│   ├── SettingsFragment.java      # 画面设置
│   ├── TutorialFragment.java      # 使用教程
│   ├── MineFragment.java          # 我的
│   ├── AboutFragment.java         # 关于
│   └── ...                        # Adapter 等
└── data/
    ├── DetectResult.java          # 结果数据模型
    └── HistoryDB.java             # SQLite 存储
```

## 🔒 隐私说明

所有视频处理、图像识别、矩阵运算均在 **设备本地完成**，不会上传任何视频数据到云端。

## 📝 License

Apache License 2.0
