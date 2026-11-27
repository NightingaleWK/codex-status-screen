# Android 验证与构建

## 本地回归

`DeviceEventsTest` 是普通 Java 入口，编译事件状态机及测试类即可运行。下面从仓库根目录运行，沿用构建脚本的默认 JDK 位置。[测试实现](../../../android/test/local/codex/statusscreen/DeviceEventsTest.java) · [JDK 配置](../../../tools/Build-Android.ps1)

```powershell
$jdkBin = Join-Path $env:LOCALAPPDATA 'CodexStatusScreen\toolchain\jdk-21\bin'
$testClasses = Join-Path $PWD 'build\device-events-test'
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
& (Join-Path $jdkBin 'javac.exe') --release 8 -encoding UTF-8 -Xlint:-options -d $testClasses android/src/local/codex/statusscreen/DeviceEvents.java android/test/local/codex/statusscreen/DeviceEventsTest.java
if ($LASTEXITCODE -ne 0) { throw 'DeviceEventsTest compilation failed' }
& (Join-Path $jdkBin 'java.exe') -cp $testClasses local.codex.statusscreen.DeviceEventsTest
if ($LASTEXITCODE -ne 0) { throw 'DeviceEventsTest failed' }
```

## APK 构建

在 Windows 用 JDK 21、Android SDK 35、Build Tools 35.0.0、ZXing core 3.5.4 和 PATH 中的 `node.exe` 运行 `.\tools\Build-Android.ps1`。脚本接收 `-Sdk`、`-Jdk`、`-Zxing` 参数，顺序是生成音源 → AAPT → Java 编译 → D8 → 对齐和签名 → 验签，产物为 `dist/CodexStatus-Android.apk`。[构建脚本](../../../tools/Build-Android.ps1) · [构建依赖](../../../README.md)

最低 SDK 26，目标 SDK 35，版本号与包名在 `AndroidManifest.xml`。发布覆盖安装包时沿用应用 ID 和本地签名，并在清单里维护 `versionCode` 与 `versionName`。签名目录在 Windows 用户数据下。[清单](../../../android/AndroidManifest.xml) · [签名与验签](../../../tools/Build-Android.ps1)

## 改动后的真机检查

| 改动 | 实际入口与检查点 |
| --- | --- |
| 配对和迁移地址 | 添加设备的扫码及手动路径，已有配对在地址变更后继续取状态 |
| 多设备状态 | 全部设备与单台视图，运行计数、当日完成、缓存及等待日志 |
| 窗口和布局 | 横竖屏、深浅色、常亮、挖孔旁的可见边框和按钮可达性 |
| 提醒 | 完成与掉线的「测试」「试听」，通知和媒体音量，前台恢复时的基线 |

交互入口由 [MainActivity.java](../../../android/src/local/codex/statusscreen/MainActivity.java) 实现，能力与设置位置见 [README](../../../README.md)。
