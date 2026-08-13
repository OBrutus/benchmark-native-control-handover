package sh.locus.goandroid;

import sh.locus.gocore.mobile.Greeter;
import sh.locus.gocore.mobile.Mobile;
import sh.locus.gocore.mobile.ProgressListener;

/**
 * The Go backend, via the gomobile-generated .aar.
 *
 * <p>Note how little code this is: gomobile already produced the Java classes and
 * the JNI shim, so the whole adapter is signature forwarding. Compare
 * {@link CppEngine} plus its 200 lines of greeter_jni.cpp for the same surface.
 */
public final class GoEngine implements GreetingEngine {

    // Constructing this loads libgojni.so and starts the Go runtime.
    private final Greeter greeter = Mobile.newGreeter();

    @Override
    public String name() {
        return "Go";
    }

    @Override
    public String version() {
        return Mobile.version();
    }

    @Override
    public String languages() {
        return greeter.languages();
    }

    @Override
    public byte[] greetJSON(String name, String lang) throws Exception {
        return greeter.greetJSON(name, lang);
    }

    @Override
    public byte[] benchmarkJSON(long iterations) throws Exception {
        // A package-level Go func, so it lands as a static on Mobile rather than
        // a method on Greeter.
        return Mobile.benchmarkJSON(iterations);
    }

    @Override
    public void greetAllAsync(String name, EngineCallback callback) {
        // Adapt our interface to gomobile's generated one. They have identical
        // shapes, which is not a coincidence — EngineCallback was modelled on it.
        greeter.greetAllAsync(name, new ProgressListener() {
            @Override
            public void onProgress(int percent) {
                callback.onProgress(percent);
            }

            @Override
            public void onComplete(String resultJSON) {
                callback.onComplete(resultJSON);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }
}
