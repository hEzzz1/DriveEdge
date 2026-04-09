# module-storage 功能文档

## 1. 模块职责
`module-storage` 负责本地事件与配置存储及上传状态流转：
1. 管理 `edge_event` 与 `device_config` 两类数据
2. 接收 `EdgeEvent` 并初始化上传状态
3. 基于上传结果驱动状态机（`PENDING/SENDING/RETRY_WAIT/SUCCESS/FAILED_FINAL`）
4. 对外输出待上传队列（可查询、可 claim）

## 2. 输入输出
输入：`EdgeEvent`、上传结果 `UploadAttemptResult`  
输出：待上传队列 `List<UploadQueueItem>`

### 2.1 输入结构 `EdgeEvent`
路径：[EdgeEvent.kt](/Users/m1ngyangg/Documents/DriveEdge/module-event-center/src/main/kotlin/com/driveedge/event/center/EdgeEvent.kt)

关键字段：
1. `eventId`
2. `fleetId/vehicleId/driverId`
3. `eventTimeUtc`
4. `fatigueScore/distractionScore`
5. `riskLevel/dominantRiskType/triggerReasons`
6. `algorithmVer`
7. `uploadStatus`（入库时会统一初始化为 `PENDING`）

### 2.2 上传结果结构 `UploadAttemptResult`
路径：[StorageModels.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/main/kotlin/com/driveedge/storage/StorageModels.kt)

关键字段：
1. `eventId`
2. `code`
3. `errorMessage`
4. `serverTraceId`

### 2.3 输出结构 `UploadQueueItem`
路径：[StorageModels.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/main/kotlin/com/driveedge/storage/StorageModels.kt)

关键字段：
1. `event`
2. `retryCount`
3. `lastErrorCode/lastErrorMessage`
4. `nextRetryAtMs`

## 3. 核心类型与入口
1. 入口：[StorageCenter.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/main/kotlin/com/driveedge/storage/StorageCenter.kt)
2. 存储模型：[StorageModels.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/main/kotlin/com/driveedge/storage/StorageModels.kt)
3. DAO 抽象：[StorageDao.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/main/kotlin/com/driveedge/storage/StorageDao.kt)
4. 内存实现：[InMemoryRoomStore.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/main/kotlin/com/driveedge/storage/InMemoryRoomStore.kt)
5. 重试配置：[StorageConfig.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/main/kotlin/com/driveedge/storage/StorageConfig.kt)

主入口方法：
1. `onEdgeEvent(event)`：事件入库并初始化状态
2. `pendingUploadQueue(limit, nowMs)`：查看可上传队列
3. `claimUploadBatch(limit, nowMs)`：批量 claim 并标记为 `SENDING`
4. `onUploadResult(result, nowMs)`：上传回写并做状态迁移

## 4. 状态机规则
1. 成功：`0/40002 -> SUCCESS`
2. 可重试：其他错误码 -> `RETRY_WAIT`，`retryCount + 1`
3. 不可重试：`40001/40101 -> FAILED_FINAL`
4. 超过最大重试：`FAILED_FINAL`

默认退避序列：
1. `5s -> 15s -> 30s -> 60s -> 120s`
2. 之后固定为 `120s`

## 5. 测试覆盖
路径：[StorageCenterTest.kt](/Users/m1ngyangg/Documents/DriveEdge/module-storage/src/test/kotlin/com/driveedge/storage/StorageCenterTest.kt)

已覆盖场景：
1. 新事件入库后进入待上传队列
2. claim 后状态变为 `SENDING`
3. 成功回写进入 `SUCCESS`
4. 可重试错误进入 `RETRY_WAIT` 并按退避回到队列
5. 不可重试错误进入 `FAILED_FINAL`
6. 超最大重试进入 `FAILED_FINAL`
