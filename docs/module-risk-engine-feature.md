# module-risk-engine 功能文档

## 1. 模块职责
`module-risk-engine` 负责风险分数计算与规则判定：
1. 计算 `fatigueScore`
2. 计算 `distractionScore`
3. 根据触发条件输出 `RiskEventCandidate`

## 2. 输入输出
输入：`FeatureWindow`  
输出：`RiskEventCandidate`

### 2.1 `FeatureWindow`
路径：[FeatureWindow.kt](/Users/m1ngyangg/Documents/DriveEdge/module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/FeatureWindow.kt)

字段：
1. `windowStartMs`
2. `windowEndMs`
3. `windowDurationMs`
4. `perclos`
5. `blinkRate`
6. `yawnCount`
7. `headPose`

### 2.2 `RiskEventCandidate`
路径：[RiskEventCandidate.kt](/Users/m1ngyangg/Documents/DriveEdge/module-risk-engine/src/main/kotlin/com/driveedge/risk/engine/RiskEventCandidate.kt)

字段：
1. `fatigueScore`：疲劳分数 `[0,1]`
2. `distractionScore`：分心分数 `[0,1]`
3. `riskLevel`：`NONE/LOW/MEDIUM/HIGH`
4. `dominantRiskType`：`FATIGUE/DISTRACTION`
5. `fatigueTriggered`：是否命中疲劳触发规则
6. `distractionTriggered`：是否命中分心触发规则
7. `shouldTrigger`：是否建议触发事件
8. `triggerReasons`：命中的规则原因集合

## 3. 核心类型与入口
1. 配置：[RiskEngineConfig.kt](/Users/m1ngyangg/Documents/DriveEdge/module-risk-engine/src/main/kotlin/com/driveedge/risk/engine/RiskEngineConfig.kt)
2. 引擎：[RiskEngine.kt](/Users/m1ngyangg/Documents/DriveEdge/module-risk-engine/src/main/kotlin/com/driveedge/risk/engine/RiskEngine.kt)

主入口：
1. `RiskEngine.evaluate(featureWindow)`：输出单窗风险事件候选

## 4. 默认规则
### 4.1 疲劳触发
满足任一条件：
1. `perclos >= 0.40` 且 `windowDurationMs >= 3000`
2. `yawnCount >= 2` 且 `windowDurationMs <= 30000`

### 4.2 分心触发
满足条件：
1. `headPose in (DOWN, LEFT, RIGHT)` 且 `windowDurationMs >= 2000`

### 4.3 风险等级
以 `max(fatigueScore, distractionScore)` 映射等级：
1. `>= 0.85`：`HIGH`
2. `>= 0.75`：`MEDIUM`
3. `>= 0.60`：`LOW`
4. 其他：`NONE`
