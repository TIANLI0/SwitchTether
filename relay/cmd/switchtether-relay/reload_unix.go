//go:build !windows

package main

import (
	"os"
	"os/signal"
	"syscall"
)

// watchKeyReload calls reload on every SIGHUP until the returned stop function is called.
func watchKeyReload(reload func()) (stop func()) {
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGHUP)
	done := make(chan struct{})
	go func() {
		for {
			select {
			case <-ch:
				reload()
			case <-done:
				return
			}
		}
	}()
	return func() {
		signal.Stop(ch)
		close(done)
	}
}
