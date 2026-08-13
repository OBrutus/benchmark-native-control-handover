#include "greeter.h"

#include <chrono>
#include <cstdio>
#include <ctime>
#include <sstream>
#include <time.h>

namespace greeter {
namespace {

// Equivalent of Go's strings.TrimSpace for ASCII whitespace.
std::string TrimSpace(const std::string& s) {
    const char* ws = " \t\n\v\f\r";
    const auto begin = s.find_first_not_of(ws);
    if (begin == std::string::npos) return "";
    const auto end = s.find_last_not_of(ws);
    return s.substr(begin, end - begin + 1);
}

// Substitutes the single "{}" placeholder. Go uses fmt.Sprintf("%s"); the
// resulting strings are identical.
std::string Format(const std::string& tmpl, const std::string& value) {
    const auto pos = tmpl.find("{}");
    if (pos == std::string::npos) return tmpl;
    return tmpl.substr(0, pos) + value + tmpl.substr(pos + 2);
}

std::string Join(const std::vector<std::string>& parts, const std::string& sep) {
    std::string out;
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i > 0) out += sep;
        out += parts[i];
    }
    return out;
}

// Reproduces time.RFC3339Nano as encoding/json emits it: nanosecond precision
// with trailing zeros (and a bare dot) trimmed.
std::string NowRFC3339Nano() {
    using namespace std::chrono;
    const auto now = system_clock::now();
    const auto secs = time_point_cast<seconds>(now);
    const auto nanos = duration_cast<nanoseconds>(now - secs).count();

    const std::time_t t = system_clock::to_time_t(secs);
    std::tm tm{};
    gmtime_r(&t, &tm);

    char date[32];
    std::strftime(date, sizeof(date), "%Y-%m-%dT%H:%M:%S", &tm);

    char frac[16];
    std::snprintf(frac, sizeof(frac), "%09lld", static_cast<long long>(nanos));

    std::string fraction(frac);
    while (!fraction.empty() && fraction.back() == '0') fraction.pop_back();

    std::string out(date);
    if (!fraction.empty()) out += "." + fraction;
    return out + "Z";
}

// Matches encoding/json's string escaping for the characters that can appear
// here. Multi-byte UTF-8 (Devanagari, Japanese) passes through untouched,
// exactly as Go does.
std::string EscapeJSON(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

}  // namespace

Greeter::Greeter()
    : templates_{
          {"en", "Hello, {}!"},
          {"hi", "नमस्ते, {}!"},
          {"ja", "こんにちは、{}さん!"},
          {"fr", "Bonjour, {} !"},
          {"de", "Hallo, {}!"},
      } {}

bool Greeter::Greet(const std::string& name,
                    const std::string& lang,
                    Greeting* out,
                    std::string* err) const {
    const std::string trimmed = TrimSpace(name);
    if (trimmed.empty()) {
        *err = "name must not be empty";
        return false;
    }

    const auto it = templates_.find(lang);
    if (it == templates_.end()) {
        // Byte-identical to Go's fmt.Errorf with %q, so the parity test can
        // assert the same substring against both engines.
        *err = "unsupported language \"" + lang + "\" (have: " + Join(Languages(), ", ") + ")";
        return false;
    }

    out->message = Format(it->second, trimmed);
    out->language = lang;
    out->name = trimmed;
    out->at = NowRFC3339Nano();
    return true;
}

std::vector<std::string> Greeter::Languages() const {
    // std::map is already ordered by key, so this comes out sorted — the same
    // guarantee core.Greeter.Languages() gets from sort.Strings.
    std::vector<std::string> out;
    out.reserve(templates_.size());
    for (const auto& kv : templates_) out.push_back(kv.first);
    return out;
}

std::string ToJSON(const Greeting& g) {
    // Field order matches core.Greeting's struct tags, so the two engines
    // produce byte-comparable JSON.
    std::ostringstream os;
    os << "{\"message\":\"" << EscapeJSON(g.message) << "\","
       << "\"language\":\"" << EscapeJSON(g.language) << "\","
       << "\"name\":\"" << EscapeJSON(g.name) << "\","
       << "\"at\":\"" << EscapeJSON(g.at) << "\"}";
    return os.str();
}

std::string ToJSONArray(const std::vector<Greeting>& greetings) {
    std::string out = "[";
    for (size_t i = 0; i < greetings.size(); ++i) {
        if (i > 0) out += ",";
        out += ToJSON(greetings[i]);
    }
    return out + "]";
}

std::string RuntimeVersion() {
    std::ostringstream os;
    os << "c++" << (__cplusplus / 100 % 100) << " android/arm64";
    return os.str();
}

std::uint64_t Checksum(std::int64_t iterations) {
    constexpr std::uint64_t kOffset64 = 14695981039346656037ULL;
    constexpr std::uint64_t kPrime64 = 1099511628211ULL;

    std::uint64_t acc = kOffset64;
    for (std::int64_t i = 0; i < iterations; ++i) {
        acc ^= static_cast<std::uint64_t>(i);
        acc *= kPrime64;
        acc = (acc << 7) | (acc >> 57);  // rotate left 7
    }
    return acc;
}

std::string RunBenchmarkJSON(std::int64_t iterations) {
    // steady_clock, not system_clock: monotonic and immune to wall-clock jumps.
    const auto start = std::chrono::steady_clock::now();
    const std::uint64_t sum = Checksum(iterations);
    const auto elapsed = std::chrono::steady_clock::now() - start;

    const auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count();

    char checksum[32];
    std::snprintf(checksum, sizeof(checksum), "0x%016llx",
                  static_cast<unsigned long long>(sum));

    std::ostringstream os;
    os << "{\"iterations\":" << iterations
       << ",\"checksum\":\"" << checksum << "\""
       << ",\"nanos\":" << nanos << "}";
    return os.str();
}

}  // namespace greeter
