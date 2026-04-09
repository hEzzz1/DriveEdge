# module-risk-engine

职责：
1. 基于 `FeatureWindow` 计算 `fatigueScore`、`distractionScore`
2. 按阈值规则判定触发条件（疲劳/分心）
3. 产出风险等级与事件候选 `RiskEventCandidate`

输入：`FeatureWindow`  
输出：`RiskEventCandidate`

## 核心类型
- `RiskEngine`：风险评分与规则判定入口
- `RiskEngineConfig`：分数权重与阈值配置
- `RiskEventCandidate`：分数、等级、触发标记与触发原因

## 最小用法
```kotlin
val engine = RiskEngine(
  config = RiskEngineConfig(),
)

val candidate: RiskEventCandidate = engine.evaluate(featureWindow)
```

说明：
1. `fatigueScore/distractionScore` 范围统一为 `[0,1]`
2. 风险等级阈值默认：低 `0.60` / 中 `0.75` / 高 `0.85`
3. 触发原因默认包含：
   - 疲劳：`perclos >= 0.40` 且持续 `>= 3s`
   - 疲劳：`yawnCount >= 2` 且窗口 `<= 30s`
   - 分心：`head_down/head_left/head_right` 持续 `>= 2s`
