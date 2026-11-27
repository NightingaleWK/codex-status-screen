package main

import (
	"context"
	"log"
	"net"
	"os"
	"slices"
	"time"

	"github.com/libp2p/zeroconf/v2"
)

const serviceType = "_codexstatus._tcp"

func startDiscovery(cfg config, device deviceInfo) func() {
	if !cfg.discover || net.ParseIP(cfg.bind).IsLoopback() {
		return func() {}
	}
	host, err := os.Hostname()
	if err != nil {
		host = device.Name
	}
	instance := "Codex Status " + host + " " + device.ID[:min(6, len(device.ID))]
	text := []string{"id=" + device.ID, "v=1"}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		var published *zeroconf.Server
		defer func() {
			if published != nil {
				published.Shutdown()
			}
		}()
		var addresses []string
		for {
			current := cfg.lanAddresses()
			if published == nil || !slices.Equal(current, addresses) {
				if published != nil {
					published.Shutdown()
					published = nil
				}
				if !slices.Equal(current, []string{"127.0.0.1"}) {
					var err error
					published, err = zeroconf.RegisterProxy(instance, serviceType, "local.", cfg.statusPort, host, current, text, nil)
					if err != nil {
						log.Printf("局域网发现: %v", err)
					}
				}
				addresses = current
			}
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	return func() {
		cancel()
		<-done
	}
}
