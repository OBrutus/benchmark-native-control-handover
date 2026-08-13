package sh.locus.goandroid;

/**
 * The C++ backend, via hand-written JNI in src/main/cpp/greeter_jni.cpp.
 *
 * <p>The contrast with {@link GoEngine} is the point of this class. There, the
 * native methods, the shim and the thread handling were all generated. Here you
 * declare each {@code native} method yourself, and its name must match the
 * {@code Java_sh_locus_goandroid_CppEngine_*} symbol in the C++ exactly —
 * a mismatch is an {@code UnsatisfiedLinkError} at call time, not a build error.
 */
public final class CppEngine implements GreetingEngine {

    static {
        // The manual counterpart to go/Seq.java's static initializer. Nothing
        // starts a runtime here — C++ has none to start.
        System.loadLibrary("greeter");
    }

    @Override
    public String name() {
        return "C++";
    }

    @Override
    public String version() {
        return nativeVersion();
    }

    @Override
    public String languages() {
        return nativeLanguages();
    }

    @Override
    public byte[] greetJSON(String name, String lang) throws Exception {
        return nativeGreetJSON(name, lang);
    }

    @Override
    public byte[] benchmarkJSON(long iterations) throws Exception {
        return nativeBenchmarkJSON(iterations);
    }

    @Override
    public void greetAllAsync(String name, EngineCallback callback) {
        nativeGreetAllAsync(name, callback);
    }

    // --- native declarations; see src/main/cpp/greeter_jni.cpp ---------------

    private static native String nativeVersion();

    private static native String nativeLanguages();

    /** Throws whatever the C++ side passes to ThrowNew. */
    private static native byte[] nativeGreetJSON(String name, String lang) throws Exception;

    /** Callbacks arrive on a std::thread attached to the JVM. */
    private static native void nativeGreetAllAsync(String name, EngineCallback callback);

    private static native byte[] nativeBenchmarkJSON(long iterations);
}
