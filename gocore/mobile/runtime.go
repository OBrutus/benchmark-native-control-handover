package mobile

import "runtime"

// runtimeVersion is split out so Version() stays readable, and to make the
// point that the Go runtime is statically linked into the .so shipped in the
// APK — nothing is installed on the device.
func runtimeVersion() string {
	return runtime.Version() + " " + runtime.GOOS + "/" + runtime.GOARCH
}
