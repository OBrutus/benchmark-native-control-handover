# go-android

Android app with its core logic in **Go**, called from Java — plus the same logic
reimplemented in **C++**, and a runtime toggle to switch between them.

No Go UI toolkit. Native Android views, native logic underneath, two ways of
getting there so they can be compared on device.

---

## Quick start

**Prerequisites:** Android SDK with NDK 28.2, CMake 4.1.2, JDK 21+, Go 1.24+.

```bash
# 1. Generate the Go .aar (Gradle does NOT do this for you)
./build-aar.sh

# 2. Build and install
cd android && ./gradlew installDebug
```

Then pick **Go** or **C++** with the radio buttons and tap a button.

```bash
cd gocore  && go test ./...                        # Go logic — fast, no device
cd android && ./gradlew connectedDebugAndroidTest  # the boundaries — needs a device
```

---

## Layout

```
gocore/                        Go module
  core/          real logic — idiomatic Go, no boundary constraints
  mobile/        the gomobile facade; the ONLY package passed to bind

android/app/src/main/
  cpp/           greeter.cpp (logic) + greeter_jni.cpp (hand-written JNI)
  java/…/        GreetingEngine + GoEngine + CppEngine + MainActivity

build-aar.sh                   runs go test, then gomobile bind
```

Two boundaries, same shape on both sides:

| | logic | facade | facade written by |
|---|---|---|---|
| Go | `core/greeter.go` | `mobile/bridge.go` | you (~90 lines) + **581 generated C lines** |
| C++ | `cpp/greeter.cpp` | `cpp/greeter_jni.cpp` | you (**184 hand-written lines**) |

`GreetingEngine` hides both behind one Java interface, so `MainActivity` never
knows which is live.

## The facade pattern

`gomobile bind` can only cross a narrow type boundary: `string`, `bool`,
`[]byte`, sized ints, floats, types declared in the bound package, and `error`
as the last return. **No maps, no `[]string`, no structs from other packages, no
`time.Time`, no multiple non-error returns.**

So `core/` stays idiomatic and `mobile/` translates — structs become JSON bytes,
`[]string` becomes a comma-joined string. Adding a field to `core.Greeting`
requires no facade change.

⚠️ **A `map` or slice return type is silently dropped.** `gomobile bind` exits 0
with no warning and the method simply is not in the generated Java. Verify after
every facade change:

```bash
unzip -o -q android/app/libs/gocore-sources.jar -d /tmp/goapi
grep -E 'public|native' /tmp/goapi/sh/locus/gocore/mobile/*.java
```

(A *multiple-return* signature does fail loudly. Only maps and slices go quiet.)

---

# Report

Measured on a Pixel_9a emulator, Android 16 (API 37), arm64. Host: Apple M3 Pro.

## Test results

| Suite | Scope | Result |
|---|---|---|
| `go test -count=10` | 8 tests × 10 runs | pass, no flakes |
| `go test -race -count=2` | race detector | clean |
| `connectedDebugAndroidTest` ×3 | 30 tests × 3 runs | **90/90, zero failures** |

The 30 device tests: 6 Go-bridge, 10 cross-engine parity, 14 load/concurrency/
encoding/edge-case scenarios — 2000 sequential crossings per engine, 8 threads ×
250 concurrent calls, 4 overlapping async runs, 500 interleaved rounds, 10k-char
names, 6 blank-name and 8 invalid-language variants, 8 benchmark sizes,
degenerate iteration counts.

## Per-call boundary cost — where the two actually differ

Measured with 5000 near-empty calls, so the crossing *is* the measurement:

| run | Go | C++ | ratio |
|---|---|---|---|
| 1 | 0.99 µs | 0.32 µs | 3.1× |
| 2 | 1.26 µs | 0.33 µs | 3.8× |
| 3 | 0.96 µs | 0.29 µs | 3.3× |

**A Go crossing costs 3–4× a C++ crossing — roughly 700 ns extra.** That is the
cgo transition stacked on top of JNI. The ratio held across all three runs even
as absolute numbers moved with emulator load.

700 ns is nothing once and 700 ms across a million calls, which is the whole
argument for designing a coarse facade: one `processBatch([]byte)` beats a
thousand `processOne()` calls.

For a method doing real work (greet + JSON marshal + `byte[]`) the gap narrows to
1.2–1.4×: Go 5.7–12.9 µs vs C++ 5.0–9.1 µs.

## Compute — a tie

Shared workload: a dependent chain of wrapping `uint64` xor/multiply/rotate.
20,000,000 iterations, best of 5:

| engine | native | via JNI |
|---|---|---|
| Go | 28.59 ms | 28.61 ms |
| C++ | 28.80 ms | 28.82 ms |

Across sizes from 1K to 5M the ratio wanders between 1.0× and 1.25× in either
direction — noise, not signal. **On this workload the two are equivalent.** Both
compilers emit essentially the same ARM64 instructions.

Checksums are byte-identical at every size (`0x243d51a37dd7179b` at 5M), which is
what makes the comparison legitimate rather than apples-to-oranges: the engines
provably computed the same thing. Host baseline: 491 µs/1M iterations, ~8×
faster than the emulator.

## The `-O2` trap

`gomobile bind` has **no debug variant** — it always emits optimized Go. CMake
honours `CMAKE_BUILD_TYPE=Debug`, i.e. `-O0`. An out-of-the-box debug build
therefore compares optimized Go against unoptimized C++:

| build | Go | C++ | apparent verdict |
|---|---|---|---|
| default debug (`-O0`) | 35.15 ms | **129.90 ms** | "Go is 3.70× faster" |
| with `-O2` | 28.59 ms | 28.80 ms | tie |

**A naive setup reports C++ as 4.5× slower than it is.**
`target_compile_options(greeter PRIVATE -O2)` in `cpp/CMakeLists.txt` is what
keeps the benchmark honest.

## Two predicted failures that did not happen

**Non-BMP text through C++'s `NewStringUTF`.** JNI specifies *modified* UTF-8
(surrogate pairs, 6 bytes); the C++ passes standard 4-byte UTF-8. `🚀` round-tripped
byte-identically to Go anyway — ART is lenient here. Treat it as
device-dependent rather than guaranteed, since the spec does not promise it.

**Overlapping async runs crossing streams.** Four simultaneous runs per engine,
each asserting every greeting in its payload names *its* runner: 20 progress
callbacks and 4 completions per engine, no crossover. Neither Seq's refnum table
nor the hand-written `NewGlobalRef`/`AttachCurrentThread` handling leaks state
between concurrent listeners.

## Binary cost

| | size |
|---|---|
| `libgojni.so` (Go + runtime, statically linked) | 2.1 MB |
| `libgreeter.so` (C++) | 0.9 MB |
| debug APK, arm64 only | 5.0 MB |

Only `arm64-v8a` is built. `./build-aar.sh --all-abis` adds the rest — widen
`abiFilters` to match.

## Verdict

Performance is not the reason to choose between these. Compute ties; Go's
boundary costs 3–4× more but is measured in hundreds of nanoseconds.

The real difference is what you maintain. `GoEngine.java` is 67 lines of pure
forwarding because gomobile generated the bridge. `CppEngine` needs 184 hand-written
JNI lines: caching `JavaVM` in `JNI_OnLoad`, `NewGlobalRef` on the callback,
`AttachCurrentThread` in the worker, `ExceptionCheck` after every callback, and
`DeleteGlobalRef` + `DetachCurrentThread` on **every** exit path including error
returns. Miss the last two and you leak a JVM thread and pin the Activity forever.

---

## Environment gotchas

**`go mod tidy` breaks the build.** Nothing imports `golang.org/x/mobile`, so
tidy strips it and `gomobile bind` then fails. Re-add with
`go get golang.org/x/mobile/bind@latest`.

**AGP 9 rejects the Kotlin plugin.** Kotlin support is built in; applying
`org.jetbrains.kotlin.android` is a hard error. This project is Java-only.

**A global Gradle init script can shadow `google()`.** If
`~/.gradle/init.gradle.kts` injects repositories into `allprojects`, those take
precedence over `settings.gradle.kts` — and without `google()`, AGP cannot
resolve `aapt2`. The root `build.gradle.kts` adds it back for this project.

**Gradle does not know Go exists.** Nothing rebuilds the `.aar`; run
`./build-aar.sh`. C++ *is* tracked and rebuilds automatically.

## Versions

Go 1.26.4 · NDK 28.2.13676358 · CMake 4.1.2 · AGP 9.3.1 · Gradle 9.6.1 ·
compileSdk 36 · minSdk 24
