# module-temporal-engine 功能文档

## 1. 模块职责
`module-temporal-engine` 负责时序滑窗聚合与行为特征计算：
1. 维护 `3~10s` 滑动窗口（默认 `3s`）
2. 按帧时间戳聚合 `DetectionResult[]`
3. 计算 `perclos`、`blinkRate`、`yawnCount`、`headPose`

## 2. 输入输出
输入：`List<DetectionResult>`  
输出：`FeatureWindow`

### 2.1 `DetectionResult`
路径：[DetectionResult.kt](/Users/m1ngyangg/Documents/DriveEdge/module-infer-yolo/src/main/kotlin/com/driveedge/infer/yolo/DetectionResult.kt)

字段：
1. `classId`
2. `label`
3. `confidence`
4. `box`
5. `frameTimestampMs`

### 2.2 `FeatureWindow`
路径：[FeatureWindow.kt](/Users/m1ngyangg/Documents/DriveEdge/module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/FeatureWindow.kt)

字段：
1. `windowStartMs`：窗口起始时间戳
2. `windowEndMs`：窗口结束时间戳
3. `windowDurationMs`：窗口有效时长
4. `perclos`：闭眼占比 `[0,1]`
5. `blinkRate`：眨眼率（次/分钟）
6. `yawnCount`：哈欠次数（整数）
7. `headPose`：主导头姿态

## 3. 核心类型与入口
1. 配置：[TemporalEngineConfig.kt](/Users/m1ngyangg/Documents/DriveEdge/module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/TemporalEngineConfig.kt)
2. 特征提取：[TemporalFeatureExtractor.kt](/Users/m1ngyangg/Documents/DriveEdge/module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/TemporalFeatureExtractor.kt)
3. 滑窗引擎：[TemporalEngine.kt](/Users/m1ngyangg/Documents/DriveEdge/module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/TemporalEngine.kt)

主入口：
1. `TemporalEngine.update(detections)`：追加新检测结果并输出当前窗口特征
2. `TemporalFeatureExtractor.extract(detections)`：对给定结果集合直接计算特征

## 4. 特征计算规则
### 4.1 `perclos`
1. 单帧眼睛状态由 `eye_closed` 与 `eye_open` 置信度比较得到。
2. `UNKNOWN` 眼睛状态帧不计入分母。
3. 公式：`closedFrameCount / validEyeFrameCount`。

### 4.2 `blinkRate`
1. 先构建眼睛状态连续片段（OPEN/CLOSED/UNKNOWN）。
2. 识别 `OPEN -> CLOSED -> OPEN` 结构为一次眨眼候选。
3. 闭眼片段持续时长需落在 `[blinkMinDurationMs, blinkMaxDurationMs]`（默认 `80~600ms`）。
4. 结果换算为“次/分钟”。

### 4.3 `yawnCount`
1. 由 `yawnLabels` 判断单帧是否哈欠（默认包含 `yawn` 与 `open_mouth`）。
2. 连续哈欠帧组成一个哈欠片段。
3. 片段持续时长 `>= yawnMinDurationMs`（默认 `800ms`）计为一次。

### 4.4 `headPose`
1. 单帧从 `head_down/head_left/head_right/head_forward` 中选置信度最高姿态。
2. 窗口内统计各姿态出现次数。
3. 出现次数最多者作为 `headPose`，无有效姿态时为 `UNKNOWN`。

## 5. 滑窗策略
1. 维护内部缓冲，按 `frameTimestampMs` 排序。
2. 以最新时间戳为基准裁剪窗口：仅保留 `latest - windowSizeMs` 之后的数据。
3. `windowSizeMs` 约束为 `3000~10000ms`。

## 6. 最小使用示例
```kotlin
val engine = TemporalEngine(
  config = TemporalEngineConfig(windowSizeMs = 3_000L),
)

val detections: List<DetectionResult> = inferResults
val featureWindow: FeatureWindow? = engine.update(detections)
```

说明：
1. 当内部缓冲为空且本次输入为空时，`update` 返回 `null`。
2. 只要窗口内有数据，就会返回最新 `FeatureWindow`。
