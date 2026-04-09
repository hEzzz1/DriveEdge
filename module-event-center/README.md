# module-event-center

职责：
1. 基于 `RiskEventCandidate` 生成事件 `eventId`
2. 对重复风险触发做时间窗口去抖
3. 将事件 `EdgeEvent` 落盘保存

输入：`RiskEventCandidate`  
输出：`EdgeEvent`

## 核心类型
- `EventCenter`：事件中心入口，负责触发判定、去抖、落盘
- `EventCenterConfig`：车辆信息、算法版本、去抖窗口配置
- `EdgeEvent`：边缘侧事件标准结构（默认状态 `PENDING`）
- `EventIdGenerator`：`evt_{vehicleId}_{yyyyMMddHHmmss}_{seq}` 生成器
- `FileEdgeEventStore`：本地文件追加写入实现

## 最小用法
```kotlin
val eventCenter = EventCenter(
  config = EventCenterConfig(
    vehicleId = "VEH-1001",
    algorithmVersion = "yolo-v8n-int8-20260407",
  ),
  eventStore = FileEdgeEventStore(Path.of("logs/edge-events.log")),
)

val edgeEvent: EdgeEvent? = eventCenter.process(candidate)
```

说明：
1. `candidate.shouldTrigger = false` 时不产出事件
2. 默认去抖窗口为 `5000ms`
3. 去抖命中后返回 `null`，不会重复落盘
