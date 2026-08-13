package sh.locus.goandroid;

/**
 * One greeting backend. Implemented twice — once over Go via gomobile
 * ({@link GoEngine}) and once over C++ via hand-written JNI ({@link CppEngine}) —
 * so the UI can switch between them without knowing which is active.
 *
 * <p>The signatures are deliberately shaped by gomobile's constraints: bytes
 * instead of a parsed object, a comma-joined string instead of a list. The C++
 * engine has no such limits, but matching them is what makes the two
 * interchangeable.
 */
public interface GreetingEngine {

    /** Short label for the UI: "Go" or "C++". */
    String name();

    /** Version banner, proving which native library answered. */
    String version();

    /** Supported language codes, comma-joined. */
    String languages();

    /**
     * Builds one greeting, returned as UTF-8 JSON bytes.
     *
     * @throws Exception carrying the backend's error message — a Go {@code error}
     *                   for {@link GoEngine}, a JNI {@code ThrowNew} for
     *                   {@link CppEngine}. The caller cannot tell them apart.
     */
    byte[] greetJSON(String name, String lang) throws Exception;

    /**
     * Greets in every supported language, reporting progress.
     *
     * <p>Callbacks arrive on a background thread in BOTH implementations: a
     * goroutine's thread for Go, a {@code std::thread} for C++. Neither is the
     * main thread, so callers must marshal before touching views.
     */
    void greetAllAsync(String name, EngineCallback callback);

    /**
     * Runs the shared integer-loop workload and returns
     * {@code {"iterations":…,"checksum":"0x…","nanos":…}} as UTF-8 JSON bytes.
     *
     * <p>{@code nanos} is measured inside the native code, so it is compute only.
     * The caller's own wall-clock measurement includes the boundary crossing;
     * subtracting the two isolates what JNI (and for Go, cgo) costs per call.
     *
     * <p>Both engines run a bit-identical loop, so the checksums must match. A
     * mismatch means one of the two compiled it wrong.
     */
    byte[] benchmarkJSON(long iterations) throws Exception;
}
