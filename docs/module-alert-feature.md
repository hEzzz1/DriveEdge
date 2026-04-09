# module-alert 功能文档

## 1. 模块职责
`module-alert` 负责本地告警执行与节流：
1. 接收上游事件 `EdgeEvent`
2. 按风险级别映射告警通道（声音/震动/UI 提示）
3. 对同类风险进行窗口节流
4. 输出执行结果 `AlertExecutionRecord`

## 2. 输入输出
输入：`EdgeEvent`  
输出：`AlertExecutionRecord`

### 2.1 输入结构 `EdgeEvent`
路径：[EdgeEvent.kt](/Users/m1ngyangg/Documents/DriveEdge/module-event-center/src/main/kotlin/com/driveedge/event/center/EdgeEvent.kt)

关键字段：
1. `eventId`
2. `vehicleId`
3. `riskLevel`
4. `dominantRiskType`
5. `triggerReasons`
6. `windowEndMs`
7. `fatigueScore/distractionScore`

### 2.2 输出结构 `AlertExecutionRecord`
路径：[AlertExecutionRecord.kt](/Users/m1ngyangg/Documents/DriveEdge/module-alert/src/main/kotlin/com/driveedge/alert/AlertExecutionRecord.kt)

关键字段：
1. `status`：`EXECUTED/THROTTLED/SKIPPED_NO_CHANNEL`
2. `attemptedChannels`：本次尝试通道集合
3. `succeededChannels`：执行成功通道集合
4. `failedChannels`：执行失败通道集合
5. `throttleKey`：节流键
6. `throttleWindowMs`：节流窗口毫秒数
7. `message`：告警文案
8. `executedAtMs`：记录生成时间

## 3. 核心类型与入口
1. 入口：[AlertCenter.kt](/Users/m1ngyangg/Documents/DriveEdge/module-alert/src/main/kotlin/com/driveedge/alert/AlertCenter.kt)
2. 策略配置：[AlertPolicy.kt](/Users/m1ngyangg/Documents/DriveEdge/module-alert/src/main/kotlin/com/driveedge/alert/AlertPolicy.kt)
3. 执行动作注入：[AlertAction.kt](/Users/m1ngyangg/Documents/DriveEdge/module-alert/src/main/kotlin/com/driveedge/alert/AlertAction.kt)
4. 节流器：[AlertThrottler.kt](/Users/m1ngyangg/Documents/DriveEdge/module-alert/src/main/kotlin/com/driveedge/alert/AlertThrottler.kt)

主入口方法：
1. `AlertCenter.process(event: EdgeEvent): AlertExecutionRecord`

## 4. 执行流程
1. 根据 `event.riskLevel` 读取告警策略 `AlertPolicy`
2. 生成节流键：`vehicleId|riskLevel|dominantRiskType|sorted(triggerReasons)`
3. 若策略通道为空，返回 `SKIPPED_NO_CHANNEL`
4. 若命中节流窗口，返回 `THROTTLED`
5. 逐个执行通道动作，收集成功/失败集合
6. 返回 `EXECUTED` 记录（含通道执行结果）

## 5. 默认告警策略
路径：[AlertPolicy.kt](/Users/m1ngyangg/Documents/DriveEdge/module-alert/src/main/kotlin/com/driveedge/alert/AlertPolicy.kt)

1. `NONE`：无通道，`0ms`
2. `LOW`：`UI_PROMPT`，`8000ms`
3. `MEDIUM`：`SOUND + UI_PROMPT`，`5000ms`
4. `HIGH`：`SOUND + VIBRATION + UI_PROMPT`，`3000ms`

## 6. 可扩展点
1. 自定义策略：传入 `AlertCenterConfig(policyByRiskLevel = ...)`
2. 自定义执行器：实现 `AlertAction` 注入 `AlertActionSet`
3. 自定义文案：通过 `messageFormatter` 统一生成提示文案

## 7. 测试覆盖
路径：[AlertCenterTest.kt](/Users/m1ngyangg/Documents/DriveEdge/module-alert/src/test/kotlin/com/driveedge/alert/AlertCenterTest.kt)

已覆盖场景：
1. 告警通道正常执行
2. 节流窗口内重复事件被抑制
3. 超过窗口后可再次告警
4. 无通道策略跳过执行
5. 部分通道执行失败记录
