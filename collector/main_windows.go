package main

import (
	"context"
	_ "embed"
	"fmt"
	"time"

	"fyne.io/systray"
	"golang.org/x/sys/windows"
)

//go:embed assets/tray.ico
var trayIcon []byte

func main() {
	if err := run(); err != nil {
		nativeMessage("启动采集器时出错\n\n"+err.Error(), windows.MB_ICONERROR)
	}
}

func run() error {
	cfg, err := readConfig()
	if err != nil {
		return err
	}
	instance, duplicate, err := lockInstance(cfg)
	if err != nil {
		return err
	}
	if duplicate {
		nativeMessage("采集器正在运行。请在任务栏右下角查看 Codex 状态屏图标。", windows.MB_ICONINFORMATION)
		return nil
	}
	defer windows.CloseHandle(instance)

	id, err := openIdentity(cfg.home)
	if err != nil {
		return fmt.Errorf("无法打开现有设备的配对记录\n%w", err)
	}
	app := newCollector(cfg, id)
	closeServers, err := app.serve()
	if err != nil {
		return err
	}
	defer closeServers()
	stopDiscovery := startDiscovery(cfg, id.file.deviceInfo)
	defer stopDiscovery()
	runTray(app)
	return nil
}

func runTray(app *collector) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	systray.Run(func() {
		systray.SetIcon(trayIcon)
		systray.SetTooltip("Codex 状态屏")
		logs := systray.AddMenuItem("等待 Codex 会话日志", "")
		logs.Disable()
		phones := systray.AddMenuItem("手机连接", "")
		phones.Disable()
		systray.AddSeparator()
		pairing := systray.AddMenuItem("打开配对页", "在本机浏览器中打开")
		exit := systray.AddMenuItem("退出", "退出 Codex 状态屏采集器")

		var last connectionStatus
		updateStatus := func() {
			now := app.connections(time.Now())
			if now == last {
				return
			}
			logs.SetTitle(now.sourceLabel())
			phones.SetTitle(fmt.Sprintf("手机  %d 台在线 / %d 台已配对", now.online, now.paired))
			last = now
		}
		// Force the initial count to be shown even if there are no phones or logs yet.
		last.paired = -1
		updateStatus()
		go func() {
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					updateStatus()
				case <-pairing.ClickedCh:
					if err := openPairingPage(app.cfg.uiPort); err != nil {
						go nativeMessage("无法打开配对页\n\n"+err.Error(), windows.MB_ICONERROR)
					}
				case <-exit.ClickedCh:
					systray.Quit()
					return
				}
			}
		}()
	}, cancel)
}
