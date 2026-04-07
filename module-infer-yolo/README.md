# module-infer-yolo

职责：
1. YOLO 模型加载（通过 `YoloBackend.load`）
2. 图像预处理（`Resize + Letterbox + Normalize + CHW`）
3. 模型推理（通过 `YoloBackend.infer`）
4. 后处理 NMS（`YoloPostprocessor`）

输入：`FramePacket`  
输出：`List<DetectionResult>`

## 默认标签映射
按当前 `best.pt` 的类别顺序内置：
1. `0 -> closed_mouth`
2. `1 -> open_mouth`
3. `2 -> eye_closed`
4. `3 -> eye_open`

可通过 `YoloConfig.labels` 覆盖。

## 最小接入示例
```kotlin
val config = YoloConfig(
  modelPath = "/Users/m1ngyangg/Downloads/best.pt",
)

val engine = ModuleInferYolo(
  backend = yourBackend, // ONNX / ncnn / TFLite backend
  config = config,
)

val results: List<DetectionResult> = engine.infer(framePacket)
```

说明：`best.pt` 是训练产物，`module-infer-yolo` 已预留 backend 插槽；在 Android 端通常接入 ONNX/ncnn/TFLite runtime 来实现 `YoloBackend`。
