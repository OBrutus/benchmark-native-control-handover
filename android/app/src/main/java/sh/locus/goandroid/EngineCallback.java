package sh.locus.goandroid;

/**
 * Progress sink for {@link GreetingEngine#greetAllAsync}.
 *
 * <p>Shaped like gomobile's generated {@code ProgressListener} so {@link GoEngine}
 * can forward straight through, and so {@link CppEngine}'s JNI code can look up
 * the same three method signatures: {@code (I)V} and {@code (Ljava/lang/String;)V}.
 *
 * <p>Every method is called from a background thread.
 */
public interface EngineCallback {

    void onProgress(int percent);

    void onComplete(String resultJSON);

    void onError(String message);
}
