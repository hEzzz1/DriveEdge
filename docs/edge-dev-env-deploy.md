# DriveEdge 边缘层开发环境部署（macOS / Linux）

本文用于搭建 DriveEdge 边缘端（Android + YOLO）的本地开发环境，适用于算法联调、端侧联调和弱网补传验证。

## 1. 目标
完成后你应具备以下能力：
1. 本地编译并运行 Android 边缘应用。
2. 在开发机完成 YOLO 模型转换与产物管理。
3. 通过 `adb` 进行真机调试、日志抓取与接口联调。
4. 通过自检脚本快速发现环境缺项。

## 2. 环境要求

### 2.1 基础工具
1. `git`
2. `python3`（建议 3.10+）
3. `java`（JDK 17）
4. `adb`

### 2.2 Android 侧依赖
1. Android Studio（任意稳定版本，支持 AGP 8.x）
2. Android SDK Platform 34
3. Android Build-Tools 34.0.0
4. Android Platform-Tools
5. Android Command-line Tools（latest）
6. Android Emulator
7. Android System Image（推荐：`system-images;android-34;google_apis;arm64-v8a`）
8. Android NDK（26.x）
9. CMake（3.22.1）

### 2.3 推荐目录
在仓库根目录建议保持以下结构：
```text
DriveEdge/
  docs/
  models/
    raw/         # 原始模型（训练导出）
    deploy/      # 端侧部署模型（ncnn/tflite）
  tools/
    setup/
      check-edge-env.sh
  logs/
  artifacts/
```

## 3. Android 环境变量配置
将下面内容加入 `~/.zshrc`（或 `~/.bashrc`）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Linux 用户仅需把 `ANDROID_HOME` 改成你的 SDK 实际路径（例如 `$HOME/Android/Sdk`）。

如果你通过 Homebrew 安装了 Android command-line tools，也可使用：
```bash
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

## 4. 安装 Android SDK 组件（命令行方式）
如果你更习惯命令行安装，可执行：

```bash
yes | sdkmanager --licenses
sdkmanager \
  "emulator" \
  "platform-tools" \
  "platforms;android-34" \
  "system-images;android-34;google_apis;arm64-v8a" \
  "build-tools;34.0.0" \
  "ndk;26.1.10909125" \
  "cmake;3.22.1"
```

创建模拟器（示例）：
```bash
echo "no" | avdmanager create avd \
  -n DriveEdge_API34 \
  -k "system-images;android-34;google_apis;arm64-v8a" \
  -d "pixel_7" \
  -f
```

## 5. YOLO 开发依赖（本地 Python）
建议使用虚拟环境：

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install ultralytics onnx onnxruntime numpy opencv-python
```

说明：
1. 训练与导出通常在算法环境执行，边缘仓库只保留可部署模型产物。
2. 导出后的模型建议放在 `models/deploy/` 并携带版本号（例如 `yolo-v8n-int8-20260407`）。

## 6. 真机联调最低流程
1. 手机开启开发者模式和 USB 调试。
2. 连接设备后执行 `adb devices`，确认状态为 `device`。
3. 启动 App 后通过 `logcat` 查看核心日志：

```bash
adb logcat | rg -i "DriveEdge|YOLO|Upload|traceId"
```

4. 联调事件上报时，重点核对：
   - 请求头 `X-Device-Token`
   - 幂等键 `eventId`
   - 响应字段 `code/message/data/traceId`
5. Android 模拟器访问宿主机服务时使用 `http://10.0.2.2:8080`（不要使用模拟器内的 `127.0.0.1`）。

## 7. 一键自检
仓库内提供脚本：

```bash
bash tools/setup/check-edge-env.sh
```

脚本会检查：
1. Java / Python / adb / sdkmanager / CMake
2. `ANDROID_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_NDK_HOME`
3. Android SDK 关键目录是否存在
4. 仓库内 `models/`、`logs/`、`artifacts/` 目录是否就绪

## 8. 常见问题
1. `adb devices` 为空：检查 USB 调试授权、数据线模式、`platform-tools` 是否在 `PATH`。
2. `sdkmanager not found`：确认已安装 command-line tools 并导出到 `PATH`。
3. NDK 编译报错：优先确认 NDK 版本与项目 `ndkVersion` 是否一致。
4. `X-Device-Token` 鉴权失败：检查 token 是否过期、是否加密存储读取失败、是否被日志脱敏误替换。

## 9. 验收清单（开发机）
1. `bash tools/setup/check-edge-env.sh` 返回通过。
2. `adb devices` 可识别至少 1 台设备。
3. 可执行本地 YOLO 推理 demo（或模型加载单测）。
4. 事件上报链路可拿到 `traceId`，并能在端侧日志检索。
