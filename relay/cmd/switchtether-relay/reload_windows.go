//go:build windows

package main

// watchKeyReload is a no-op on Windows (there is no SIGHUP); restart the relay to pick up
// an edited keys file.
func watchKeyReload(func()) (stop func()) { return func() {} }
