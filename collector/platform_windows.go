package main

import (
	"errors"
	"net"
	"os"
	"strconv"
	"unsafe"

	"golang.org/x/sys/windows"
)

var messageBoxW = windows.NewLazySystemDLL("user32.dll").NewProc("MessageBoxW")

func openSharedRead(path string) (*os.File, error) {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	// Codex can keep appending to this file and can rename or remove it while it is being read.
	handle, err := windows.CreateFile(name, windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: path, Err: err}
	}
	return os.NewFile(uintptr(handle), path), nil
}

func lockInstance(cfg config) (windows.Handle, bool, error) {
	name := "Local\\CodexStatusScreen-" + strconv.Itoa(cfg.statusPort) + "-" + strconv.Itoa(cfg.uiPort)
	handle, err := windows.CreateMutex(nil, false, windows.StringToUTF16Ptr(name))
	if errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		windows.CloseHandle(handle)
		return 0, true, nil
	}
	return handle, false, err
}

func nativeMessage(text string, flags uintptr) {
	const title = "Codex 状态屏"
	textUTF16, err := windows.UTF16PtrFromString(text)
	if err != nil {
		return
	}
	titleUTF16 := windows.StringToUTF16Ptr(title)
	messageBoxW.Call(0, uintptr(unsafe.Pointer(textUTF16)), uintptr(unsafe.Pointer(titleUTF16)), flags)
}

func openPairingPage(port int) error {
	url := windows.StringToUTF16Ptr("http://" + net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	return windows.ShellExecute(0, windows.StringToUTF16Ptr("open"), url, nil, nil, windows.SW_SHOWNORMAL)
}
