# module-storage

职责：
1. 管理本地事件表 `edge_event` 与配置表 `device_config`
2. 接收 `EdgeEvent` 并初始化上传状态
3. 根据上传结果驱动状态流转（`PENDING/SENDING/RETRY_WAIT/SUCCESS/FAILED_FINAL`）
4. 输出待上传队列（支持批量 claim）

输入：`EdgeEvent`、上传结果 `UploadAttemptResult`  
输出：待上传队列 `List<UploadQueueItem>`

## 核心类型
- `StorageCenter`：存储模块入口，负责入库、查询、状态流转
- `EdgeEventRow`：`edge_event` 行模型
- `DeviceConfigRow`：`device_config` 行模型
- `UploadAttemptResult`：上传回写输入
- `UploadQueueItem`：待上传输出项
- `InMemoryRoomStore`：内存版 DAO 实现（便于单测，后续可替换为 Room DAO）

## 最小用法
```kotlin
val storage = StorageCenter()

storage.onEdgeEvent(edgeEvent)

val batch: List<UploadQueueItem> = storage.claimUploadBatch(limit = 20)

batch.forEach { item ->
  // uploader 上传后回写结果
  storage.onUploadResult(
    UploadAttemptResult(
      eventId = item.event.eventId,
      code = 0,
      serverTraceId = "trace-10001",
    ),
  )
}
```

说明：
1. 新事件默认初始化为 `PENDING`
2. 成功码：`0/40002 -> SUCCESS`
3. 不可重试码：`40001/40101 -> FAILED_FINAL`
4. 其他错误码按可重试处理，进入 `RETRY_WAIT` 并指数退避
