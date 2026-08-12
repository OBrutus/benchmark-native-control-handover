// Package mobile is the gomobile facade — the ONLY package passed to
// `gomobile bind`.
//
// Everything exported from here has to be expressible in gomobile's narrow
// type system, so this is where the compromises live:
//
//	supported: int/int8..int64, float32/64, bool, string, []byte,
//	           types declared in this package, interfaces declared here,
//	           and `error` as the LAST return value
//	NOT supported: maps, slices of anything except byte, structs from other
//	           packages, channels, generics, variadics, multiple non-error
//	           returns, time.Time
//
// The rule of thumb: keep this file thin and boring. It translates, it does
// not decide. All real behaviour belongs in ../core, which stays idiomatic.
//
// Type mapping worth remembering on the Kotlin side:
//
//	Go int    -> Java long   (Go's int is 64-bit)
//	Go int32  -> Java int
//	Go []byte -> Java byte[]
//	Go error  -> thrown Exception
package mobile

import (
	"encoding/json"
	"strings"
	"time"

	"github.com/aniketv/gocore/core"
)

// Version identifies the Go layer. A trivial no-argument call like this is
// the cheapest possible smoke test that the .aar loaded and JNI works.
func Version() string {
	return "gocore 0.1.0 (" + runtimeVersion() + ")"
}

// Greeter wraps core.Greeter. gomobile turns this into a Java class whose
// methods forward across JNI; `inner` is unexported so it is invisible there.
type Greeter struct {
	inner *core.Greeter
}

// NewGreeter is bound as a constructor: `Mobile.newGreeter()` in Kotlin.
func NewGreeter() *Greeter {
	return &Greeter{inner: core.NewGreeter()}
}

// GreetJSON returns the greeting as JSON bytes.
//
// core.Greeting cannot cross the boundary, so we serialise instead of
// inventing a flattened mirror struct. JSON also means adding a field to
// core.Greeting does not require touching this signature.
//
// The trailing error becomes a Java exception at the call site.
func (g *Greeter) GreetJSON(name, lang string) ([]byte, error) {
	greeting, err := g.inner.Greet(name, lang)
	if err != nil {
		return nil, err
	}
	return json.Marshal(greeting)
}

// Languages returns the supported codes as one comma-separated string,
// because []string cannot be bound.
//
// For a list this small, splitting a string in Kotlin is cheaper than a JSON
// round trip. For anything structured, prefer JSON.
func (g *Greeter) Languages() string {
	return strings.Join(g.inner.Languages(), ",")
}

// LanguageCount is int32 so Kotlin sees `Int` rather than `Long`.
func (g *Greeter) LanguageCount() int32 {
	return int32(len(g.inner.Languages()))
}

// ProgressListener is implemented on the Kotlin side and passed in — this is
// how Go calls back into the app without polling.
//
// Note the shapes: no `error` parameter (only supported as a return value),
// and int32 so Kotlin gets Int. Errors are delivered as OnError(String).
type ProgressListener interface {
	OnProgress(percent int32)
	OnComplete(resultJSON string)
	OnError(message string)
}

// GreetAllAsync greets name in every supported language, reporting progress,
// then hands back a JSON array. It returns immediately.
//
// IMPORTANT: the listener methods are invoked from this goroutine, i.e. NOT
// on Android's main thread. The Kotlin implementation must marshal to the
// main thread before touching any view.
func (g *Greeter) GreetAllAsync(name string, listener ProgressListener) {
	go func() {
		langs := g.inner.Languages()
		results := make([]core.Greeting, 0, len(langs))

		for i, lang := range langs {
			greeting, err := g.inner.Greet(name, lang)
			if err != nil {
				listener.OnError(err.Error())
				return
			}
			results = append(results, greeting)

			// Stand-in for real work, so the progress callbacks are visible.
			time.Sleep(250 * time.Millisecond)
			listener.OnProgress(int32(float64(i+1) / float64(len(langs)) * 100))
		}

		payload, err := json.Marshal(results)
		if err != nil {
			listener.OnError(err.Error())
			return
		}
		listener.OnComplete(string(payload))
	}()
}
