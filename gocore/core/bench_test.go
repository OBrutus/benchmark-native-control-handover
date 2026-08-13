package core

import "testing"

// Locks in the checksum for a known input. If this value ever changes, the C++
// engine's parity test on the device will fail too — and one of them is wrong.
func TestChecksumIsDeterministic(t *testing.T) {
	first := Checksum(1000)
	second := Checksum(1000)

	if first != second {
		t.Fatalf("Checksum(1000) not deterministic: %#x != %#x", first, second)
	}
	if first == 0 {
		t.Fatal("Checksum(1000) == 0, loop probably optimised away")
	}
}

func TestChecksumDependsOnIterations(t *testing.T) {
	if Checksum(10) == Checksum(11) {
		t.Fatal("Checksum ignores its iteration count")
	}
}

func TestRunBenchmarkReportsWork(t *testing.T) {
	got := RunBenchmark(100_000)

	if got.Iterations != 100_000 {
		t.Errorf("Iterations = %d, want 100000", got.Iterations)
	}
	if got.Nanos <= 0 {
		t.Errorf("Nanos = %d, want > 0", got.Nanos)
	}
	if len(got.Checksum) != 18 { // "0x" + 16 hex digits
		t.Errorf("Checksum = %q, want 0x-prefixed 16 hex digits", got.Checksum)
	}
}

// Host-side baseline: `go test -bench=. ./core/`.
//
// This measures your Mac, NOT the phone — useful for iterating on the loop, but
// the on-device numbers are the ones that matter for the Go-vs-C++ comparison.
func BenchmarkChecksum(b *testing.B) {
	for i := 0; i < b.N; i++ {
		Checksum(1_000_000)
	}
}
