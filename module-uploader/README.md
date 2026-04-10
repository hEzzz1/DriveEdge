# module-uploader

职责：
1. 对接 DriveServer `POST /api/v1/events`
2. 请求头注入 `X-Device-Token`
3. 解析统一响应 `code/message/traceId`
4. 输出上传回执 `UploadReceipt`

输入：待上传事件 `EdgeEvent`  
输出：上传回执 `UploadReceipt`

## 核心类型
- `UploaderConfig`：上传配置（`baseUrl`、`deviceToken`、超时）
- `EventUploader`：上传入口，完成请求构造、调用与回执解析
- `UploadReceipt`：上传结果（`code/traceId/message/httpStatus`）
- `EventsApiTransport`：可替换传输层接口
- `HttpEventsApiTransport`：默认传输层实现（基于 `HttpURLConnection`，兼容 Android/JVM）

## 最小用法
```kotlin
val uploader =
  EventUploader(
    config =
      UploaderConfig(
        baseUrl = "https://driveserver.local",
        deviceToken = "device-token-001",
      ),
  )

val receipt: UploadReceipt = uploader.upload(edgeEvent)
```

说明：
1. 回执 `code` 直接透传服务端响应
2. 网络异常时回执 `code = -1`
3. 响应缺少 `code` 时回执 `code = -2`
4. 默认请求方法为 `POST`，自动携带 `X-Device-Token`
