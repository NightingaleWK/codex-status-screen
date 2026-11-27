# Codex 状态屏

Windows 采集各自的 Codex Desktop 状态，Android 在局域网内配对并汇总。Windows 采集器是可直接运行的 Go 程序，Android APK 使用本地发布密钥签名。

## 首次使用

1. 在每台 Windows 电脑解压 Windows 包，双击 `CodexStatusScreen.exe`。右键任务栏右下角的托盘图标，可以查看日志和手机连接情况、打开配对页或退出。图标也可能收在任务栏的「^」菜单里。
2. 安装 APK，在 App 中选择「添加设备」。扫码可直接配对，手动连接填写电脑配对页上的地址、8 位配对码和校验指纹。已有配对的手机会沿用 Windows 用户目录下的设备证书和授权记录。
3. 每配对一台电脑，就可以在「全部设备」中汇总查看，或切到单台。手机和电脑需要在可互通的局域网内。
4. 状态屏右上角的图标可以切换浅色或深色、一键横竖屏和打开设置。横屏并排显示运行任务、设备连接和今日完成。点任务可以看完整标题。App 默认前台常亮。
5. 任务完成显示绿色边框，设备掉线显示橙色边框。亮边在前 8 秒加粗并缓慢呼吸，随后保留细边和提醒条，点「知道了」收起。在设置中可以分别关闭两类提醒、提示音和亮边，也可以用「试听」直接播放两种提示音。默认按系统通知音量提醒，开启「用媒体音量提醒」后改走媒体音量。提醒在 App 前台监看时生效，首次连接只显示已有记录。

## Windows 只读采集

照常从原入口打开 Codex Desktop。采集器作为独立的 Windows 进程运行，从 Codex 已写出的会话日志和标题索引读取任务标题、最近阶段、耗时和完成记录。采集进度以日志写入为准。

只读日志无法可靠确认待审批、待回答、完成百分比或实时生成速度。屏幕显示由任务日志推断的阶段、已用时和最近更新时间。

## Windows 局域网连接

采集器的 HTTPS 端口为 TCP 8767，配对页仅监听本机。DNS-SD 使用 UDP 5353，帮助已配对设备在电脑地址变化时重新发现。也可以在手机设置中点开已配对的设备，修改连接地址，通过原证书和设备身份核验后保存。手机连接经过证书校验和设备授权。

如果新程序启动后手机一直离线，请检查 Windows 防火墙为 CodexStatusScreen.exe 设置的入站规则。管理员可以在专用网络为该程序放行 TCP 8767，有现成的阻止规则时需一并处理。

## 构建

Windows 构建使用 Go 1.26 或更新版本。在项目根目录的 PowerShell 中运行 `.\tools\Build-Windows.ps1`，生成 `dist/CodexStatus-Windows-x64.zip`。也可以用 `-Go` 指定便携工具链里的 `go.exe`。

Android 使用 JDK 21、Android SDK 35、Build Tools 35.0.0 和 ZXing core 3.5.4，并通过 PATH 中的 `node.exe` 生成提示音。在项目根目录的 PowerShell 中运行 `.\tools\Build-Android.ps1`，生成 `dist/CodexStatus-Android.apk`。脚本可接收 `-Sdk`、`-Jdk` 和 `-Zxing` 路径，默认使用 `%LOCALAPPDATA%\CodexStatusScreen\toolchain`。签名密钥保存在当前 Windows 用户的本地应用数据中。
