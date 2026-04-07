# module-temporal-engine

职责：
1. 维护 3~10 秒滑窗（默认 `3s`）
2. 聚合 `DetectionResult` 序列
3. 计算 `perclos`、`blinkRate`、`yawnCount`、`headPose`

输入：`List<DetectionResult>`  
输出：`FeatureWindow`

## 核心类型
- `FeatureWindow`：窗口起止时间、时长和四个核心特征
- `HeadPose`：`FORWARD/DOWN/LEFT/RIGHT/UNKNOWN`
- `TemporalEngine`：维护内部缓冲并按窗口更新

## 最小用法
```kotlin
val engine = TemporalEngine(
  config = TemporalEngineConfig(windowSizeMs = 3_000L),
)

val featureWindow: FeatureWindow? = engine.update(detections)
```

说明：
1. `perclos`：闭眼帧占比（仅在存在 `eye_open/eye_closed` 标签帧中统计）
2. `blinkRate`：按窗口内闭眼-睁眼过渡估算，单位为“次/分钟”
3. `yawnCount`：按连续哈欠片段计数（默认最短持续 `800ms`）
4. `headPose`：窗口内占比最高的头姿态
