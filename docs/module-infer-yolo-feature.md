# module-infer-yolo 功能文档

## 1. 模块职责
`module-infer-yolo` 负责 YOLO 推理链路的核心能力：
1. 模型加载（`YoloBackend.load`）
2. 预处理（Resize + Letterbox + Normalize + CHW）
3. 推理执行（`YoloBackend.infer`）
4. 后处理（置信度过滤 + NMS + 坐标映射回原图）

## 2. 输入输出
输入：`FramePacket`  
输出：`List<DetectionResult>`

### 2.1 `FramePacket`
路径：[FramePacket.kt](/Users/m1ngyangg/Documents/DriveEdge/module-infer-yolo/src/main/kotlin/com/driveedge/infer/yolo/FramePacket.kt)

字段：
1. `width`：原图宽
2. `height`：原图高
3. `data`：RGB24 图像字节（`width * height * 3`）
4. `timestampMs`：帧时间戳
5. `pixelFormat`：当前支持 `RGB24`

### 2.2 `DetectionResult`
路径：[DetectionResult.kt](/Users/m1ngyangg/Documents/DriveEdge/module-infer-yolo/src/main/kotlin/com/driveedge/infer/yolo/DetectionResult.kt)

字段：
1. `classId`
2. `label`
3. `confidence`
4. `box(left, top, right, bottom)`（原图坐标系）
5. `frameTimestampMs`

## 3. 类别映射（best.pt）
默认映射按你训练模型约定：
1. `0 -> closed_mouth`
2. `1 -> open_mouth`
3. `2 -> eye_closed`
4. `3 -> eye_open`

配置路径：[YoloConfig.kt](/Users/m1ngyangg/Documents/DriveEdge/module-infer-yolo/src/main/kotlin/com/driveedge/infer/yolo/YoloConfig.kt)  
可通过 `YoloConfig.labels` 覆盖。

## 4. 处理流程
主入口路径：[ModuleInferYolo.kt](/Users/m1ngyangg/Documents/DriveEdge/module-infer-yolo/src/main/kotlin/com/driveedge/infer/yolo/ModuleInferYolo.kt)

执行顺序：
1. `backend.load(config)`：初始化模型
2. `preprocess(frame, config)`：生成模型输入张量
3. `backend.infer(preprocessed)`：执行推理得到 `RawDetection`
4. `postprocess(raw, preprocessed, config)`：
   - 置信度阈值过滤
   - NMS（支持 class-aware / class-agnostic）
   - 模型坐标还原到原图坐标
   - 组装 `DetectionResult`

## 5. 关键配置项
配置结构：[YoloConfig.kt](/Users/m1ngyangg/Documents/DriveEdge/module-infer-yolo/src/main/kotlin/com/driveedge/infer/yolo/YoloConfig.kt)

常用参数：
1. `modelPath`：模型路径（可填写 `/Users/m1ngyangg/Downloads/best.pt`）
2. `inputWidth/inputHeight`：模型输入尺寸（默认 `640x640`）
3. `confidenceThreshold`：置信度阈值（默认 `0.25`）
4. `iouThreshold`：NMS IoU 阈值（默认 `0.45`）
5. `maxDetections`：最大输出框数（默认 `100`）
6. `classAgnosticNms`：是否类别无关 NMS（默认 `false`）
7. `normalizeScale`：归一化系数（默认 `1/255`）

## 6. Backend 扩展点
接口路径：[YoloBackend.kt](/Users/m1ngyangg/Documents/DriveEdge/module-infer-yolo/src/main/kotlin/com/driveedge/infer/yolo/YoloBackend.kt)

你只需要实现：
1. `load(config)`：加载模型和 runtime
2. `infer(frame)`：返回 `List<RawDetection>`

当前默认提供：
1. `NoOpYoloBackend`：空实现，用于工程联调骨架

## 7. 使用示例
```kotlin
val config = YoloConfig(
  modelPath = "/Users/m1ngyangg/Downloads/best.pt",
)

val yolo = ModuleInferYolo(
  backend = NoOpYoloBackend(), // 后续替换为 ONNX/ncnn/TFLite backend
  config = config,
)

val detections: List<DetectionResult> = yolo.infer(framePacket)
```

## 8. 当前边界与后续
1. 模块已完成推理链路框架与可测试核心算法。
2. 真实端侧推理仍需落地具体 backend（建议 ONNX Runtime / ncnn / TFLite）。
3. 若需要在 Android 端直接跑 `best.pt`，需补充对应 PyTorch Mobile 类 backend（通常不作为首选部署方案）。
