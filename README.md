# DriveEdge

## 1. 项目说明
DriveEdge 是疲劳驾驶与分心检测边缘端项目。当前仓库的主落地实现是 `edge-app` 安卓应用，已经收敛为“纯本地检测链路”，不再默认启用云端推理。

## 2. 当前实现状态
### 2.1 当前包含
1. 安卓端前摄视频采集。
2. 本地人脸检测（`YOLOv8Face + ONNX Runtime`）。
3. 本地简版疲劳分析（`MediaPipe Face Landmarker`）。
4. 本地页面状态提示、Toast 提示、声音告警。
5. 本地录制 MP4。
6. 本地质检图片落盘。

### 2.2 当前不包含
1. 云端推理主链。
2. 事件上传、离线补传、云端风险引擎。
3. Room 事件队列与 WorkManager 上报闭环。

## 3. 主要目录
1. `edge-app/`
   当前安卓应用主工程。
2. `docs/`
   项目文档目录。
3. `module-*`
   历史模块化设计与功能说明，部分内容已不再对应当前运行主链。

## 4. 当前主链文档
1. [当前安卓本地疲劳检测实现说明](docs/current-android-local-fatigue.md)
2. [边缘层设计文档](docs/edge-layer-design.md)
3. [Android 相机链路联调文档（历史版本）](docs/edge-app-camera-service-integration.md)
4. [module-infer-yolo 功能文档](docs/module-infer-yolo-feature.md)
5. [module-alert 功能文档](docs/module-alert-feature.md)
6. [module-risk-engine 功能文档](docs/module-risk-engine-feature.md)
7. [module-temporal-engine 功能文档](docs/module-temporal-engine-feature.md)
8. [module-storage 功能文档](docs/module-storage-feature.md)
9. [边缘层开发环境部署](docs/edge-dev-env-deploy.md)

## 5. 当前主链概览
### 5.1 本地检测链路
1. `Camera2 + ImageReader(YUV_420_888)` 采集视频帧。
2. `NV21 -> JPEG` 并进行方向归一化。
3. `YOLOv8Face` 执行本地人脸检测。
4. `MediaPipe Face Landmarker` 执行闭眼/张嘴分析。
5. 触发疲劳警告时进行 UI 提示、Toast 和声音提醒。
6. 质检目录保存 `probe/raw/model/overlay/local_detected` 等图片。

### 5.2 当前模型资产
1. `edge-app/src/main/assets/models/yolov8face.onnx`
2. `edge-app/src/main/assets/models/face_landmarker.task`

## 6. 构建命令
在仓库根目录执行：

```bash
./gradlew :edge-app:assembleHostlocalDebug
./gradlew :edge-app:assembleSimulatorDebug
```

APK 输出目录：
1. `edge-app/build/outputs/apk/hostlocal/debug/`
2. `edge-app/build/outputs/apk/simulator/debug/`

## 7. 说明
1. 当前代码和文档以“本地疲劳检测链路”为准。
2. `docs/` 中部分旧文档仍保留历史内容，主要用于回溯，不代表当前运行主路径。
