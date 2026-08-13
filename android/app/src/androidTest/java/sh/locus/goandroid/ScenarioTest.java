package sh.locus.goandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Load, concurrency, edge-case and encoding scenarios for both engines.
 *
 * <p>{@link EngineParityTest} proves the two engines agree on the happy path.
 * This class tries to break them: thousands of sequential calls, many threads at
 * once, overlapping async runs, pathological inputs, and non-BMP text.
 *
 * <p>Measurements are written to logcat under the tag {@code ScenarioTest} —
 * assertions here use deliberately loose bounds, because a shared CI machine or a
 * throttled emulator would otherwise make them flaky. The logged numbers are the
 * interesting output; the assertions only catch pathology.
 */
@RunWith(AndroidJUnit4.class)
public class ScenarioTest {

    private static final String TAG = "ScenarioTest";

    private static List<GreetingEngine> engines() {
        return Arrays.asList(new GoEngine(), new CppEngine());
    }

    private static JSONObject greet(GreetingEngine e, String name, String lang) throws Exception {
        return new JSONObject(new String(e.greetJSON(name, lang), StandardCharsets.UTF_8));
    }

    // --- sustained load -----------------------------------------------------

    /** 2000 sequential crossings per engine; every result must still be correct. */
    @Test
    public void sustainedSequentialCallsStayCorrect() throws Exception {
        final int calls = 2000;

        for (GreetingEngine e : engines()) {
            final long started = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                assertEquals(e.name() + " wrong result at call " + i,
                        "Hello, Aniket!", greet(e, "Aniket", "en").getString("message"));
            }
            final long elapsed = System.nanoTime() - started;

            Log.i(TAG, String.format("[%s] %d sequential greetJSON calls: %.2f ms total, %.1f µs/call",
                    e.name(), calls, elapsed / 1e6, elapsed / 1000.0 / calls));

            // Pathology guard only: 1ms per trivial call would mean something is
            // deeply wrong, e.g. a lock or a thread attach per call.
            assertTrue(e.name() + " averaged over 1ms per call",
                    elapsed / calls < 1_000_000L);
        }
    }

    /**
     * Isolates per-call boundary cost using the cheapest available call.
     *
     * <p>This is the measurement the in-app benchmark cannot make: there, a single
     * 28ms call swamps the crossing entirely. Here the body does almost nothing,
     * so what remains IS the crossing — JNI for both, plus cgo for Go.
     */
    @Test
    public void perCallBoundaryCostIsMeasured() {
        final int calls = 5000;

        for (GreetingEngine e : engines()) {
            e.languages();  // warm up

            final long started = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                e.languages();
            }
            final long elapsed = System.nanoTime() - started;

            Log.i(TAG, String.format("[%s] %d languages() calls: %.2f ms total, %.2f µs/call",
                    e.name(), calls, elapsed / 1e6, elapsed / 1000.0 / calls));
        }
    }

    // --- concurrency --------------------------------------------------------

    /**
     * Eight threads hammering one engine instance.
     *
     * <p>Both backends should be safe: Go's Greeter only reads an immutable map,
     * and the C++ side uses a function-local static const instance. If either
     * were not, this is where a data race or a crash would surface.
     */
    @Test
    public void concurrentCallsFromManyThreadsAreSafe() throws Exception {
        final int threads = 8;
        final int callsPerThread = 250;

        for (GreetingEngine e : engines()) {
            final CountDownLatch ready = new CountDownLatch(threads);
            final CountDownLatch go = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            final AtomicInteger ok = new AtomicInteger();
            final List<String> failures = Collections.synchronizedList(new ArrayList<>());

            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            // Rotate languages so threads are not all doing the
                            // identical lookup.
                            String lang = e.languages().split(",")[i % 5];
                            String msg = greet(e, "Aniket", lang).getString("message");
                            if (msg.isEmpty()) failures.add("empty message");
                            else ok.incrementAndGet();
                        }
                    } catch (Throwable ex) {
                        failures.add(ex.toString());
                    } finally {
                        done.countDown();
                    }
                }, e.name() + "-worker").start();
            }

            assertTrue("threads did not start", ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(e.name() + " concurrent run did not finish in 60s",
                    done.await(60, TimeUnit.SECONDS));

            Log.i(TAG, String.format("[%s] %d threads x %d calls: %d ok, %d failures",
                    e.name(), threads, callsPerThread, ok.get(), failures.size()));

            assertTrue(e.name() + " concurrent failures: " + failures, failures.isEmpty());
            assertEquals(e.name() + " lost calls", threads * callsPerThread, ok.get());
        }
    }

    /**
     * Four overlapping async runs per engine.
     *
     * <p>For Go that means four goroutines calling back through Seq; for C++ four
     * std::threads each attaching to the JVM independently. Callbacks must not be
     * dropped, duplicated, or delivered to the wrong listener.
     */
    @Test
    public void overlappingAsyncRunsDoNotInterfere() throws Exception {
        final int runs = 4;

        for (GreetingEngine e : engines()) {
            final CountDownLatch done = new CountDownLatch(runs);
            final List<String> results = Collections.synchronizedList(new ArrayList<>());
            final List<String> failures = Collections.synchronizedList(new ArrayList<>());
            final AtomicInteger progressCallbacks = new AtomicInteger();

            for (int r = 0; r < runs; r++) {
                final String who = "Runner" + r;
                e.greetAllAsync(who, new EngineCallback() {
                    @Override
                    public void onProgress(int percent) {
                        progressCallbacks.incrementAndGet();
                    }

                    @Override
                    public void onComplete(String resultJSON) {
                        try {
                            JSONArray arr = new JSONArray(resultJSON);
                            // Every greeting in this payload must name THIS runner.
                            for (int i = 0; i < arr.length(); i++) {
                                String n = arr.getJSONObject(i).getString("name");
                                if (!who.equals(n)) {
                                    failures.add("crossed streams: expected " + who + " got " + n);
                                }
                            }
                            results.add(who);
                        } catch (Exception ex) {
                            failures.add(ex.toString());
                        } finally {
                            done.countDown();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        failures.add(who + ": " + message);
                        done.countDown();
                    }
                });
            }

            assertTrue(e.name() + " overlapping runs did not all finish in 30s",
                    done.await(30, TimeUnit.SECONDS));

            Log.i(TAG, String.format("[%s] %d overlapping async runs: %d completed, %d progress callbacks, %d failures",
                    e.name(), runs, results.size(), progressCallbacks.get(), failures.size()));

            assertTrue(e.name() + " failures: " + failures, failures.isEmpty());
            assertEquals(e.name() + " missing completions", runs, results.size());
            assertEquals(e.name() + " wrong progress callback total",
                    runs * e.languages().split(",").length, progressCallbacks.get());
        }
    }

    /** Ten async runs back to back — checks nothing degrades across repeats. */
    @Test
    public void repeatedAsyncRunsAreStable() throws Exception {
        final int runs = 10;

        for (GreetingEngine e : engines()) {
            for (int r = 0; r < runs; r++) {
                final CountDownLatch done = new CountDownLatch(1);
                final AtomicReference<String> result = new AtomicReference<>();
                final AtomicReference<String> failure = new AtomicReference<>();

                e.greetAllAsync("Aniket", new EngineCallback() {
                    @Override
                    public void onProgress(int percent) {
                    }

                    @Override
                    public void onComplete(String resultJSON) {
                        result.set(resultJSON);
                        done.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        failure.set(message);
                        done.countDown();
                    }
                });

                assertTrue(e.name() + " run " + r + " timed out", done.await(15, TimeUnit.SECONDS));
                assertNull(e.name() + " run " + r + " failed", failure.get());
                assertEquals(e.name() + " run " + r + " wrong count",
                        e.languages().split(",").length, new JSONArray(result.get()).length());
            }
            Log.i(TAG, String.format("[%s] %d repeated async runs all stable", e.name(), runs));
        }
    }

    // --- input edge cases ---------------------------------------------------

    /** Non-ASCII and non-BMP names, through the byte[] (sync) path. */
    @Test
    public void unicodeNamesSurviveTheSyncPath() throws Exception {
        final String[] names = {
                "Aniket",
                "अनिकेत",          // Devanagari, BMP
                "アニケット",         // Katakana, BMP
                "Ægir Þórsson",   // Latin-1 supplement
                "🚀 Rocket",       // non-BMP: 4-byte UTF-8, surrogate pair in Java
        };

        for (String name : names) {
            String goMsg = greet(new GoEngine(), name, "en").getString("message");
            String cppMsg = greet(new CppEngine(), name, "en").getString("message");

            assertEquals("engines disagree on name " + name, goMsg, cppMsg);
            assertTrue("name lost in " + goMsg, goMsg.contains(name));
        }
        Log.i(TAG, "sync path: all " + names.length + " unicode names round-tripped identically");
    }

    /**
     * The same names through the async path, which returns a Java String rather
     * than bytes.
     *
     * <p>This is the risky one for C++: {@code NewStringUTF} expects JNI's
     * modified UTF-8, where a non-BMP character is a 6-byte surrogate pair, not
     * standard UTF-8's 4 bytes. Go's generated bridge handles this; hand-written
     * JNI is where it typically breaks.
     */
    @Test
    public void unicodeNamesSurviveTheAsyncPath() throws Exception {
        final String[] names = {"अनिकेत", "アニケット", "🚀 Rocket"};

        for (String name : names) {
            String goMsg = firstAsyncMessage(new GoEngine(), name);
            String cppMsg = firstAsyncMessage(new CppEngine(), name);

            assertEquals("async engines disagree on name " + name, goMsg, cppMsg);
            assertTrue("name lost in async result: " + cppMsg, cppMsg.contains(name));
        }
        Log.i(TAG, "async path: all " + names.length + " unicode names round-tripped identically");
    }

    private static String firstAsyncMessage(GreetingEngine e, String name) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();

        e.greetAllAsync(name, new EngineCallback() {
            @Override
            public void onProgress(int percent) {
            }

            @Override
            public void onComplete(String resultJSON) {
                result.set(resultJSON);
                done.countDown();
            }

            @Override
            public void onError(String message) {
                failure.set(message);
                done.countDown();
            }
        });

        assertTrue(e.name() + " async timed out for " + name, done.await(15, TimeUnit.SECONDS));
        assertNull(e.name() + " async errored: " + failure.get(), failure.get());
        return new JSONArray(result.get()).getJSONObject(0).getString("message");
    }

    /** A 10k-character name — checks buffer handling on both sides. */
    @Test
    public void veryLongNamesAreHandled() throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) sb.append('a');
        final String longName = sb.toString();

        for (GreetingEngine e : engines()) {
            String msg = greet(e, longName, "en").getString("message");
            assertEquals(e.name() + " mangled a long name",
                    "Hello, " + longName + "!", msg);
        }
        Log.i(TAG, "both engines handled a 10,000-character name");
    }

    /** Whitespace variants that must all be rejected identically. */
    @Test
    public void blankNameVariantsAreAllRejected() {
        final String[] blanks = {"", " ", "   ", "\t", "\n", " \t\n\r "};

        for (GreetingEngine e : engines()) {
            for (String blank : blanks) {
                try {
                    e.greetJSON(blank, "en");
                    fail(e.name() + " accepted a blank name: " + escape(blank));
                } catch (Exception ex) {
                    assertTrue(e.name() + " wrong error for " + escape(blank) + ": " + ex.getMessage(),
                            ex.getMessage().contains("must not be empty"));
                }
            }
        }
        Log.i(TAG, "both engines rejected all " + blanks.length + " blank-name variants");
    }

    /** Bad language codes, including ones that look plausible. */
    @Test
    public void invalidLanguagesAreAllRejected() {
        final String[] bad = {"", "x", "xx", "EN", "en-US", "zz", "  ", "de "};

        for (GreetingEngine e : engines()) {
            for (String lang : bad) {
                try {
                    e.greetJSON("Aniket", lang);
                    fail(e.name() + " accepted language " + escape(lang));
                } catch (Exception ex) {
                    assertTrue(e.name() + " wrong error for " + escape(lang) + ": " + ex.getMessage(),
                            ex.getMessage().contains("unsupported language"));
                }
            }
        }
        Log.i(TAG, "both engines rejected all " + bad.length + " invalid language codes");
    }

    private static String escape(String s) {
        return "\"" + s.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // --- benchmark scenarios ------------------------------------------------

    /** Checksum parity and timing monotonicity across a wide range of sizes. */
    @Test
    public void benchmarkAgreesAndScalesAcrossSizes() throws Exception {
        final long[] sizes = {0, 1, 2, 10, 1_000, 100_000, 1_000_000, 5_000_000};

        long previousGoNanos = -1;
        for (long size : sizes) {
            JSONObject go = bench(new GoEngine(), size);
            JSONObject cpp = bench(new CppEngine(), size);

            assertEquals("checksum diverged at " + size,
                    go.getString("checksum"), cpp.getString("checksum"));

            Log.i(TAG, String.format("bench %,12d iters | Go %8.3f ms | C++ %8.3f ms | %s",
                    size, go.getLong("nanos") / 1e6, cpp.getLong("nanos") / 1e6,
                    go.getString("checksum")));

            if (size >= 100_000) {
                assertTrue("Go timing not monotonic at " + size,
                        go.getLong("nanos") > previousGoNanos);
                previousGoNanos = go.getLong("nanos");
            }
        }
    }

    /** Zero and negative iteration counts must not loop or crash. */
    @Test
    public void degenerateIterationCountsAreSafe() throws Exception {
        for (GreetingEngine e : engines()) {
            JSONObject zero = bench(e, 0);
            JSONObject negative = bench(e, -1_000_000);

            // With no iterations the accumulator is the untouched FNV basis, so
            // both must produce the same value as each other and as zero.
            assertEquals(e.name() + " negative count did work",
                    zero.getString("checksum"), negative.getString("checksum"));
            assertEquals(e.name() + " lost the iteration count", -1_000_000, negative.getLong("iterations"));
        }
        Log.i(TAG, "both engines treated 0 and negative iteration counts as no-ops");
    }

    /** Repeated benchmark runs must return a stable checksum. */
    @Test
    public void benchmarkIsRepeatable() throws Exception {
        for (GreetingEngine e : engines()) {
            String first = bench(e, 500_000).getString("checksum");
            for (int i = 0; i < 5; i++) {
                assertEquals(e.name() + " checksum changed between runs",
                        first, bench(e, 500_000).getString("checksum"));
            }
        }
        Log.i(TAG, "checksums stable across 6 repetitions on both engines");
    }

    private static JSONObject bench(GreetingEngine e, long iterations) throws Exception {
        return new JSONObject(new String(e.benchmarkJSON(iterations), StandardCharsets.UTF_8));
    }

    // --- engine independence ------------------------------------------------

    /**
     * Interleaves the two engines to make sure they do not share state.
     *
     * <p>Both native libraries are loaded into the same process, so a bug in
     * either could plausibly corrupt the other.
     */
    @Test
    public void interleavingEnginesKeepsThemIndependent() throws Exception {
        final GreetingEngine go = new GoEngine();
        final GreetingEngine cpp = new CppEngine();

        for (int i = 0; i < 500; i++) {
            assertEquals("Hallo, Aniket!", greet(go, "Aniket", "de").getString("message"));
            assertEquals("Hallo, Aniket!", greet(cpp, "Aniket", "de").getString("message"));
            assertEquals("こんにちは、Aniketさん!", greet(cpp, "Aniket", "ja").getString("message"));
            assertEquals("こんにちは、Aniketさん!", greet(go, "Aniket", "ja").getString("message"));
        }
        Log.i(TAG, "500 interleaved rounds across both engines, no interference");
    }
}
