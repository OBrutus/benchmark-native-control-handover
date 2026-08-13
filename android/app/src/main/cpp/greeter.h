// Pure C++ port of gocore/core/greeter.go.
//
// Deliberately mirrors the Go split: this header + greeter.cpp are the "core"
// (no JNI, no Android, unit-testable on any platform), and greeter_jni.cpp is
// the facade — the exact role gocore/mobile/bridge.go plays for Go.
//
// The difference is that gomobile GENERATES the facade for Go. Here you write
// it by hand, which is the whole point of the comparison.
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace greeter {

struct Greeting {
    std::string message;
    std::string language;
    std::string name;
    std::string at;  // RFC3339 with nanoseconds, matching Go's time.Time JSON
};

class Greeter {
public:
    Greeter();

    // C++ has no multiple return values, so Go's (Greeting, error) becomes an
    // out-parameter plus a bool. Returns false and fills *err on failure.
    bool Greet(const std::string& name,
               const std::string& lang,
               Greeting* out,
               std::string* err) const;

    // Sorted language codes, same as core.Greeter.Languages().
    std::vector<std::string> Languages() const;

private:
    std::map<std::string, std::string> templates_;
};

// Serialisers matching encoding/json's output for core.Greeting.
std::string ToJSON(const Greeting& g);
std::string ToJSONArray(const std::vector<Greeting>& greetings);

// Exposed for the JNI layer's version string.
std::string RuntimeVersion();

// --- benchmark -------------------------------------------------------------

// Bit-for-bit equivalent of core.Checksum in Go: a dependent integer loop using
// wrapping uint64 arithmetic. Both engines must return the same value.
std::uint64_t Checksum(std::int64_t iterations);

// Times Checksum and returns {"iterations":…,"checksum":"0x…","nanos":…},
// matching core.RunBenchmark's JSON exactly.
std::string RunBenchmarkJSON(std::int64_t iterations);

}  // namespace greeter
