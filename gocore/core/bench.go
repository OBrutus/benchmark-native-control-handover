package core

import (
	"fmt"
	"time"
)

// BenchmarkResult is the outcome of one Checksum run.
type BenchmarkResult struct {
	Iterations int64  `json:"iterations"`
	Checksum   string `json:"checksum"`
	Nanos      int64  `json:"nanos"`
}

// Checksum runs a deterministic integer loop and returns the accumulated hash.
//
// The C++ engine implements this identically. Both use uint64 wrapping
// arithmetic — defined behaviour in Go and for unsigned types in C++ — so the
// two must agree bit for bit. That makes this a parity check as well as a
// benchmark: if a compiler on either side miscompiled the loop, the checksums
// would diverge.
//
// The body is deliberately dependent (each iteration feeds the next) so neither
// compiler can vectorise or hoist it out.
func Checksum(iterations int64) uint64 {
	const (
		offset64 = 14695981039346656037
		prime64  = 1099511628211
	)

	acc := uint64(offset64)
	for i := int64(0); i < iterations; i++ {
		acc ^= uint64(i)
		acc *= prime64
		acc = acc<<7 | acc>>57 // rotate left 7
	}
	return acc
}

// RunBenchmark times Checksum and reports the result.
//
// Timing happens here, inside Go, so it excludes the JNI/cgo crossing. The
// caller also measures wall time from Java; the difference between the two is
// the boundary cost.
func RunBenchmark(iterations int64) BenchmarkResult {
	start := time.Now()
	sum := Checksum(iterations)
	elapsed := time.Since(start)

	return BenchmarkResult{
		Iterations: iterations,
		Checksum:   fmt.Sprintf("0x%016x", sum),
		Nanos:      elapsed.Nanoseconds(),
	}
}
