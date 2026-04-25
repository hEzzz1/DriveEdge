# DriveEdge 代码结构地图

## 1. 目的
这份文档用于快速定位代码，避免每次改动都在整个仓库里全局搜索。

适用场景：
1. 要改某个功能，但不确定入口文件。
2. 要确认某条调用链到底经过哪些模块。
3. 要局部搜索，而不是对整个仓库做无差别扫描。

当前仓库以 `edge-app` 的“本地推理 + 事件上报”链路为主。部分模块和文档是历史预留或模块化抽象，不是当前运行主链。

## 2. 仓库总览
根目录主要结构：

```text
DriveEdge/
├── edge-app/                Android 主应用，当前运行主链
├── edge-core/               通用评分实验代码
├── module-event-center/     风险事件编码、去抖、事件 ID
├── module-risk-engine/      风险分级与触发规则
├── module-storage/          本地事件队列与重试状态机
├── module-temporal-engine/  时序特征窗口抽取
├── module-uploader/         HTTP 上报封装
├── module-infer-yolo/       当前基本为空，偏预留模块
├── module-alert/            未接入 settings，偏历史残留
├── docs/                    文档
├── models/                  模型相关目录
└── tools/                   环境或辅助脚本
```

当前 `settings.gradle.kts` 实际纳入构建的模块：
1. `:edge-core`
2. `:edge-app`
3. `:module-infer-yolo`
4. `:module-temporal-engine`
5. `:module-risk-engine`
6. `:module-event-center`
7. `:module-storage`
8. `:module-uploader`

不在当前构建主链中的目录：
1. `module-alert/`
2. `docs/` 下部分历史文档

## 3. 当前主链
当前运行路径：

```text
MainActivity
  -> Camera2 / ImageReader
  -> YUV -> JPEG
  -> LocalOnnxDetector
  -> LocalFatigueAnalyzer
  -> EdgeEventReporter
     -> EventCenter
     -> StorageCenter
     -> EventUploader
     -> POST /api/v1/events
```

说明：
1. 当前主入口是 `edge-app/src/main/java/com/driveedge/app/ui/MainActivity.java`
2. 当前默认是本地推理，不是云推理主链
3. 当前工作区里 `edge-app/src/main/java/com/driveedge/app/fatigue/` 下只有 `LocalOnnxDetector.java` 和 `LocalFatigueAnalyzer.java`，没有 `CloudInferClient.java`

## 4. 模块地图

### 4.1 `edge-app/`
职责：Android 应用壳、相机采集、UI、录制、质检落盘、本地推理接入、事件上报接入。

关键文件：
1. `edge-app/src/main/java/com/driveedge/app/ui/MainActivity.java`
   页面主入口，负责权限、相机、帧处理、录制、UI、调用疲劳分析与事件上报。
   关键方法：
   `onCreate` 第 230 行，`startCaptureInternal` 第 283 行，`openCameraIfReady` 第 345 行，`uploadPreviewFrameTick` 第 821 行，`resetInferSession` 第 1371 行，`toggleRecording` 第 1467 行，`openRecordingsDirectory` 第 1616 行，`openQualityReplayDirectory` 第 1620 行。
2. `edge-app/src/main/java/com/driveedge/app/fatigue/LocalOnnxDetector.java`
   本地 ONNX 人脸检测器。
   关键方法：
   类定义第 28 行，构造函数第 42 行，`inferJpeg` 第 69 行，`inferBitmap` 第 82 行，`decodeOutput` 第 190 行，`nmsPerClass` 第 294 行，`ensureModelFile` 第 368 行，`Result` 第 425 行，`Box` 第 452 行。
3. `edge-app/src/main/java/com/driveedge/app/fatigue/LocalFatigueAnalyzer.java`
   本地疲劳分析器，基于 MediaPipe Face Landmarker。
   关键方法：
   类定义第 21 行，构造函数第 27 行，`analyzeBitmap` 第 44 行，`scoreOf` 第 112 行，`Result` 第 121 行。
4. `edge-app/src/main/java/com/driveedge/app/event/EdgeEventReporter.java`
   疲劳事件接入层，连接事件中心、存储、上传和网络监听。
   关键方法：
   构造函数第 85 行，`reportFatigueResult` 第 143 行，`pumpUploads` 第 182 行，`drainUploads` 第 192 行，`updateStatusForRow` 第 234 行，`StatusListener` 第 411 行，`PrefsQueueStore` 第 415 行。
5. `edge-app/src/main/java/com/driveedge/app/camera/FrameData.java`
   帧结构体。
6. `edge-app/src/main/AndroidManifest.xml`
   权限、Activity、明文 HTTP 设置。
   关键位置：
   `CAMERA` 权限第 12 行，`INTERNET` 第 13 行，`ACCESS_NETWORK_STATE` 第 14 行，`usesCleartextTraffic` 第 23 行，`MainActivity` 声明第 29 行。
7. `edge-app/build.gradle.kts`
   BuildConfig 注入、依赖、默认服务地址。
   关键位置：
   `EDGE_DEVICE_TOKEN` 第 19 行，`EDGE_FLEET_ID` 第 20 行，`EDGE_VEHICLE_ID` 第 21 行，`EDGE_DRIVER_ID` 第 22 行，`EDGE_ALGORITHM_VERSION` 第 23 行，`EDGE_SERVER_BASE_URL` 第 32 行和第 36 行。
8. `edge-app/src/main/res/layout/activity_main.xml`
    主页面布局。
   关键控件：
   `statusView` 第 10 行，`fatigueStatusView` 第 18 行，`previewView` 第 26 行，`startButton` 第 38 行，`stopButton` 第 49 行，`recordButton` 第 63 行，`openRecordingsButton` 第 74 行，`openQualityReplayButton` 第 82 行。
9. `edge-app/src/main/res/values/strings.xml`
   状态文案和按钮文案。
   关键资源：
   `start_capture` 第 4 行，`stop_capture` 第 5 行，`record_start` 第 6 行，`record_stop` 第 7 行，`status_running` 第 17 行，`status_local_running` 第 21 行，`status_fatigue_warning` 第 25 行，`permissions_denied` 第 31 行。

什么时候先看这里：
1. 相机打不开。
2. 预览异常。
3. 推理频率异常。
4. UI 状态文案异常。
5. 录制和质检目录异常。
6. 事件为什么没有被上报。

### 4.2 `module-event-center/`
职责：把风险候选对象编码为 `EdgeEvent`，生成事件 ID，并做去抖。

关键文件：
1. `module-event-center/src/main/kotlin/com/driveedge/event/center/EventCenter.kt`
   从 `RiskEventCandidate` 生成 `EdgeEvent`。
   关键方法：
   类定义第 8 行，`process` 第 17 行，`buildDebounceKey` 第 51 行。
2. `module-event-center/src/main/kotlin/com/driveedge/event/center/EdgeEvent.kt`
   事件实体和 `UploadStatus`。
3. `module-event-center/src/main/kotlin/com/driveedge/event/center/EventCenterConfig.kt`
   车辆、车队、司机、算法版本、去抖窗口配置。
4. `module-event-center/src/main/kotlin/com/driveedge/event/center/EventDebouncer.kt`
   去抖控制。
   关键方法：
   类定义第 3 行，`shouldEmit` 第 9 行。
5. `module-event-center/src/main/kotlin/com/driveedge/event/center/EventIdGenerator.kt`
   事件 ID 生成。
6. `module-event-center/src/main/kotlin/com/driveedge/event/center/EdgeEventStore.kt`
   事件落库接口。

什么时候先看这里：
1. 疲劳结果明明触发了，但没生成事件。
2. 事件 ID 规则要改。
3. 去抖窗口要调。
4. `EdgeEvent` 字段要扩展。

### 4.3 `module-risk-engine/`
职责：把时序特征窗口变成疲劳/分心风险结果。

关键文件：
1. `module-risk-engine/src/main/kotlin/com/driveedge/risk/engine/RiskEngine.kt`
   风险评分、触发条件、风险等级输出。
2. `module-risk-engine/src/main/kotlin/com/driveedge/risk/engine/RiskEngineConfig.kt`
   阈值、权重、持续时长配置。
3. `module-risk-engine/src/main/kotlin/com/driveedge/risk/engine/RiskEventCandidate.kt`
   风险候选对象、风险类型、触发原因。

什么时候先看这里：
1. 风险分级不对。
2. 疲劳/分心阈值要调。
3. 触发原因文案或枚举要扩展。

### 4.4 `module-temporal-engine/`
职责：把逐帧检测结果聚合成时间窗口特征。

关键文件：
1. `module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/TemporalEngine.kt`
   维护窗口缓存并做平滑。
2. `module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/TemporalFeatureExtractor.kt`
   提取 PERCLOS、眨眼、打哈欠、头姿、视线方向等特征。
3. `module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/FeatureWindow.kt`
   窗口特征结构、头姿枚举、视线方向枚举。
4. `module-temporal-engine/src/main/kotlin/com/driveedge/temporal/engine/TemporalEngineConfig.kt`
   窗口长度与平滑配置。

什么时候先看这里：
1. 时序统计有问题。
2. 眨眼、打哈欠、低头统计不准。
3. 平滑窗口要调整。

说明：
1. 当前 `edge-app` 主流程直接用 `LocalFatigueAnalyzer` 做轻量判断。
2. `module-temporal-engine` 和 `module-risk-engine` 更偏模块化抽象层，部分能力被 `EdgeEventReporter` 复用，部分未直接接入页面主链。
3. 当前工作区里已经没有 `CloudInferClient.java`，不要再把云推理客户端当成现有入口。

### 4.5 `module-storage/`
职责：事件本地队列、上传状态回写、重试退避。

关键文件：
1. `module-storage/src/main/kotlin/com/driveedge/storage/StorageCenter.kt`
   事件入库、抢占上传批次、成功失败回写。
   关键方法：
   类定义第 7 行，`onEdgeEvent` 第 19 行，`claimUploadBatch` 第 45 行，`onUploadResult` 第 65 行，`toRetryOrFinal` 第 107 行。
2. `module-storage/src/main/kotlin/com/driveedge/storage/StorageModels.kt`
   `EdgeEventRow`、`UploadQueueItem`、`UploadAttemptResult`。
3. `module-storage/src/main/kotlin/com/driveedge/storage/StorageConfig.kt`
   重试次数和退避策略。
   关键方法：
   `StorageConfig` 第 3 行，`RetryBackoffPolicy` 第 11 行，`delayMsForAttempt` 第 17 行。
4. `module-storage/src/main/kotlin/com/driveedge/storage/StorageDao.kt`
   `EdgeEventDao`、`DeviceConfigDao` 接口。
5. `module-storage/src/main/kotlin/com/driveedge/storage/InMemoryRoomStore.kt`
   内存版 DAO 实现。

什么时候先看这里：
1. 事件明明生成了，但不进入上传队列。
2. 重试时间不对。
3. 事件状态一直停在 `PENDING` 或 `RETRY_WAIT`。

### 4.6 `module-uploader/`
职责：把 `EdgeEvent` 编码成 HTTP 请求并处理响应。

关键文件：
1. `module-uploader/src/main/kotlin/com/driveedge/uploader/EventUploader.kt`
   上报主流程、响应解析。
   关键方法：
   类定义第 5 行，`upload` 第 11 行，`toReceipt` 第 35 行，`EventPayloadMapper.toJson` 第 68 行，`UnifiedResponseParser.parseIntField` 第 114 行，`UnifiedResponseParser.parseStringField` 第 122 行。
2. `module-uploader/src/main/kotlin/com/driveedge/uploader/UploaderConfig.kt`
   `baseUrl`、`endpointPath`、超时。
   关键方法：
   `UploaderConfig` 第 5 行，`endpointUrl` 第 22 行。
3. `module-uploader/src/main/kotlin/com/driveedge/uploader/EventsApiTransport.kt`
   `HttpURLConnection` 发送 POST。
   关键方法：
   `EventsApiTransport` 第 8 行，`postEvent` 第 9 行，`TransportResponse` 第 17 行，`HttpEventsApiTransport` 第 22 行，`override postEvent` 第 25 行。
4. `module-uploader/src/main/kotlin/com/driveedge/uploader/UploadReceipt.kt`
   上报回执结构。

什么时候先看这里：
1. 请求地址不对。
2. 请求头不对。
3. 服务器响应无法解析。
4. 重复事件、鉴权失败、网络错误判断不对。

### 4.7 `edge-core/`
职责：通用评分代码，目前和 Android 主链耦合较弱。

关键文件：
1. `edge-core/src/main/kotlin/com/driveedge/core/score/RiskScoring.kt`
2. `edge-core/src/test/kotlin/com/driveedge/core/score/RiskScoringTest.kt`

什么时候先看这里：
1. 做通用评分逻辑实验。
2. 提炼与 UI 无关的核心算法。

### 4.8 `module-infer-yolo/`
现状：
1. 已在 `settings.gradle.kts` 中纳入构建。
2. 当前源文件基本为空。
3. `module-temporal-engine` 依赖它的检测结果类型。

建议：
1. 如果你要补全独立 YOLO 模块，从这里开始。
2. 如果你只是改当前 Android 端检测逻辑，优先看 `edge-app/.../LocalOnnxDetector.java`。

### 4.9 `module-alert/`
现状：
1. 目录存在。
2. 当前不在 `settings.gradle.kts` 中。
3. 更像历史预留模块，不是当前主链。

## 5. 按功能定位

### 5.1 改网络请求地址
先看：
1. `edge-app/build.gradle.kts`
2. `edge-app/src/main/java/com/driveedge/app/event/EdgeEventReporter.java`
3. `module-uploader/src/main/kotlin/com/driveedge/uploader/UploaderConfig.kt`
4. `module-uploader/src/main/kotlin/com/driveedge/uploader/EventsApiTransport.kt`

当前路径：
1. `BuildConfig.EDGE_SERVER_BASE_URL`
2. `EdgeEventReporter` 传给 `UploaderConfig`
3. `UploaderConfig.endpointUrl()`
4. `HttpEventsApiTransport.postEvent()`

当前事件上报默认接口：
1. 以当前 `edge-app/build.gradle.kts` 为准。
2. 当前 `EDGE_SERVER_BASE_URL` 配置在第 32 行和第 36 行，说明地址按 buildType 分开配置，不要沿用旧修改里的结论。

### 5.2 改云推理请求
当前状态：
1. 这份工作区里没有 `CloudInferClient.java`。
2. 如果后面你重新加回云推理代码，再把它补进这份文档。

### 5.3 改本地疲劳判断
先看：
1. `edge-app/src/main/java/com/driveedge/app/fatigue/LocalFatigueAnalyzer.java`
2. `edge-app/src/main/java/com/driveedge/app/ui/MainActivity.java`
3. `edge-app/src/main/java/com/driveedge/app/event/EdgeEventReporter.java`

### 5.4 改人脸检测
先看：
1. `edge-app/src/main/java/com/driveedge/app/fatigue/LocalOnnxDetector.java`
2. `edge-app/src/main/java/com/driveedge/app/ui/MainActivity.java`
3. `edge-app/src/main/assets/models/yolov8n-face-lindevs.onnx`

### 5.5 改页面按钮、状态文案、交互
先看：
1. `edge-app/src/main/java/com/driveedge/app/ui/MainActivity.java`
2. `edge-app/src/main/res/layout/activity_main.xml`
3. `edge-app/src/main/res/values/strings.xml`
4. `edge-app/src/main/res/values/themes.xml`
5. `edge-app/src/main/res/values/colors.xml`

### 5.6 改录制和质检落盘
先看：
1. `edge-app/src/main/java/com/driveedge/app/ui/MainActivity.java`

重点关注：
1. `toggleRecording`
2. `openRecordingsDirectory`
3. `openQualityReplayDirectory`
4. 与 JPEG dump、quality replay 相关的方法

### 5.7 改事件编码字段
先看：
1. `module-event-center/src/main/kotlin/com/driveedge/event/center/EdgeEvent.kt`
2. `module-event-center/src/main/kotlin/com/driveedge/event/center/EventCenter.kt`
3. `module-uploader/src/main/kotlin/com/driveedge/uploader/EventUploader.kt`

### 5.8 改去抖或重试策略
去抖先看：
1. `module-event-center/src/main/kotlin/com/driveedge/event/center/EventDebouncer.kt`
2. `module-event-center/src/main/kotlin/com/driveedge/event/center/EventCenterConfig.kt`

重试先看：
1. `module-storage/src/main/kotlin/com/driveedge/storage/StorageConfig.kt`
2. `module-storage/src/main/kotlin/com/driveedge/storage/StorageCenter.kt`
3. `edge-app/src/main/java/com/driveedge/app/event/EdgeEventReporter.java`

## 6. 主入口索引

### 6.1 `MainActivity.java`
最常用入口方法：
1. `onCreate` 第 230 行
2. `startCaptureInternal` 第 283 行
3. `openCameraIfReady` 第 345 行
4. `uploadPreviewFrameTick` 第 821 行
5. `toggleRecording` 第 1467 行
6. `openRecordingsDirectory` 第 1616 行
7. `openQualityReplayDirectory` 第 1620 行
8. `resetInferSession` 第 1371 行

用途：
1. 改页面行为先看这里。
2. 改相机采集先看这里。
3. 改本地推理调度先看这里。

### 6.2 `EdgeEventReporter.java`
最常用入口方法：
1. 构造函数：第 85 行，初始化 `EventCenter`、`StorageCenter`、`EventUploader`
2. `reportFatigueResult`：第 143 行
3. `pumpUploads`：第 182 行
4. `drainUploads`：第 192 行

用途：
1. 查事件为什么没发出去。
2. 查网络恢复后为何没重传。
3. 查状态栏为什么显示异常。

## 7. 配置入口

### 7.1 构建配置
文件：
1. `edge-app/build.gradle.kts`

当前注入内容：
1. `EDGE_DEVICE_TOKEN` 第 19 行
2. `EDGE_FLEET_ID` 第 20 行
3. `EDGE_VEHICLE_ID` 第 21 行
4. `EDGE_DRIVER_ID` 第 22 行
5. `EDGE_ALGORITHM_VERSION` 第 23 行
6. `EDGE_SERVER_BASE_URL` 第 32 行和第 36 行

值来源优先级：
1. `local.properties`
2. 环境变量
3. `build.gradle.kts` 默认值

### 7.2 Android 权限和网络
文件：
1. `edge-app/src/main/AndroidManifest.xml`

当前关键信息：
1. 申请了 `CAMERA`，第 12 行
2. 申请了 `INTERNET`，第 13 行
3. 申请了 `ACCESS_NETWORK_STATE`，第 14 行
4. `usesCleartextTraffic=true`，第 23 行

## 8. 推荐的局部搜索方式
不要再直接对整个仓库做宽泛搜索，优先限定目录。

### 8.1 查网络请求
```bash
rg -n "http|https|baseUrl|endpoint|Authorization" edge-app/src module-uploader/src
```

### 8.2 查事件上报
```bash
rg -n "EdgeEventReporter|EventUploader|UploadReceipt|reportFatigueResult" edge-app/src module-uploader/src module-storage/src module-event-center/src
```

### 8.3 查疲劳分析
```bash
rg -n "drowsy|distracted|yawn|blink|fatigue" edge-app/src/main/java/com/driveedge/app/fatigue edge-app/src/main/java/com/driveedge/app/ui
```

### 8.4 查 UI
```bash
rg -n "startButton|stopButton|recordButton|statusView|fatigueStatusView" edge-app/src/main/java edge-app/src/main/res
```

### 8.5 查重试和队列
```bash
rg -n "RETRY_WAIT|FAILED_FINAL|claimUploadBatch|nextRetryAtMs|retry" edge-app/src module-storage/src
```

### 8.6 查 BuildConfig 注入
```bash
rg -n "buildConfigField|EDGE_SERVER_BASE_URL|EDGE_DEVICE_TOKEN" edge-app/build.gradle.kts edge-app/build/generated/source/buildConfig
```

## 9. 修改前的最短排查路径

### 9.1 用户说“请求地址不对”
1. 看 `edge-app/build.gradle.kts`
2. 看 `EdgeEventReporter.java`
3. 看 `UploaderConfig.kt`
4. 看 `EventsApiTransport.kt`

### 9.2 用户说“页面没反应”
1. 看 `MainActivity.java`
2. 看 `activity_main.xml`
3. 看 `strings.xml`

### 9.3 用户说“本地检测没触发”
1. 看 `LocalOnnxDetector.java`
2. 看 `LocalFatigueAnalyzer.java`
3. 看 `MainActivity.java`
4. 看 `EdgeEventReporter.java`

### 9.4 用户说“事件没重传”
1. 看 `EdgeEventReporter.java`
2. 看 `StorageCenter.kt`
3. 看 `StorageConfig.kt`

## 10. 结论
以后定位问题，按下面的顺序即可：
1. 先判断问题属于 UI、相机、推理、事件、存储、网络中的哪一层。
2. 进入对应模块目录，不要先扫整个仓库。
3. 优先打开本文件列出的入口类。
4. 只在该模块和直接依赖模块内做 `rg` 搜索。

如果后面仓库继续扩张，建议优先维护这份文档，而不是依赖记忆找文件。
