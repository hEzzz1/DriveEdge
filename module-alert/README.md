# module-alert

职责：
1. 执行本地告警（声音/震动/UI 提示）
2. 按风险事件做告警节流，避免短时间重复打扰
3. 输出告警执行记录，供后续日志与运维分析

输入：`EdgeEvent`  
输出：`AlertExecutionRecord`

## 核心类型
- `AlertCenter`：模块入口，负责策略匹配、节流判定和告警执行
- `AlertCenterConfig`：按 `RiskLevel` 配置告警通道和节流窗口
- `AlertActionSet`：`sound/vibration/uiPrompt` 三类动作注入点
- `AlertThrottler`：按节流键和窗口时间判定是否允许告警
- `AlertExecutionRecord`：记录本次告警执行结果

## 最小用法
```kotlin
val alertCenter = AlertCenter(
  config = AlertCenterConfig(),
  actions = AlertActionSet(
    sound = AlertAction { _, _ -> true },
    vibration = AlertAction { _, _ -> true },
    uiPrompt = AlertAction { _, _ -> true },
  ),
)

val record: AlertExecutionRecord = alertCenter.process(edgeEvent)
```

## 默认策略
1. `NONE`：无通道，状态 `SKIPPED_NO_CHANNEL`
2. `LOW`：`UI_PROMPT`，节流 `8000ms`
3. `MEDIUM`：`SOUND + UI_PROMPT`，节流 `5000ms`
4. `HIGH`：`SOUND + VIBRATION + UI_PROMPT`，节流 `3000ms`
