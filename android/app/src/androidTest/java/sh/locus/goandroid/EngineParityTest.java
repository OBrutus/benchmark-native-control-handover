package sh.locus.goandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the same assertions against both engines, then compares them to each
 * other.
 *
 * <p>This is the test that gives the toggle meaning: if Go and C++ ever diverge
 * in message text, language set, or error wording, the UI would silently change
 * behaviour depending on a radio button. These tests fail instead.
 */
@RunWith(AndroidJUnit4.class)
public class EngineParityTest {

    private static List<GreetingEngine> engines() {
        return Arrays.asList(new GoEngine(), new CppEngine());
    }

    private static JSONObject greet(GreetingEngine e, String name, String lang) throws Exception {
        return new JSONObject(new String(e.greetJSON(name, lang), StandardCharsets.UTF_8));
    }

    @Test
    public void bothLibrariesLoadAndReportAVersion() {
        for (GreetingEngine e : engines()) {
            String v = e.version();
            assertTrue(e.name() + " version was blank", v != null && !v.isEmpty());
            assertTrue(e.name() + " version lacks platform: " + v, v.contains("android/arm64"));
        }
    }

    @Test
    public void bothEnginesGreetIdentically() throws Exception {
        for (GreetingEngine e : engines()) {
            assertEquals(e.name(), "Hello, Aniket!", greet(e, "Aniket", "en").getString("message"));
            assertEquals(e.name(), "Hallo, Aniket!", greet(e, "Aniket", "de").getString("message"));
        }
    }

    @Test
    public void bothEnginesAgreeOnEveryLanguage() throws Exception {
        String goLangs = new GoEngine().languages();
        String cppLangs = new CppEngine().languages();

        assertEquals("language sets diverge", goLangs, cppLangs);

        // And every message must match, including the non-ASCII ones — proof the
        // UTF-8 round trip is equivalent through cgo and through raw JNI.
        for (String lang : goLangs.split(",")) {
            assertEquals("message differs for " + lang,
                    greet(new GoEngine(), "Aniket", lang).getString("message"),
                    greet(new CppEngine(), "Aniket", lang).getString("message"));
        }
    }

    @Test
    public void bothEnginesProduceTheSameJsonShape() throws Exception {
        JSONObject go = greet(new GoEngine(), "Aniket", "ja");
        JSONObject cpp = greet(new CppEngine(), "Aniket", "ja");

        assertEquals(go.getString("message"), cpp.getString("message"));
        assertEquals(go.getString("language"), cpp.getString("language"));
        assertEquals(go.getString("name"), cpp.getString("name"));

        // Timestamps differ by construction, but the format must not.
        assertTrue("Go timestamp not RFC3339Z: " + go.getString("at"),
                go.getString("at").endsWith("Z"));
        assertTrue("C++ timestamp not RFC3339Z: " + cpp.getString("at"),
                cpp.getString("at").endsWith("Z"));
    }

    @Test
    public void bothEnginesRejectAnUnknownLanguageTheSameWay() {
        for (GreetingEngine e : engines()) {
            try {
                e.greetJSON("Aniket", "xx");
                fail(e.name() + ": expected an exception for an unsupported language");
            } catch (Exception ex) {
                assertTrue(e.name() + " error wording differs: " + ex.getMessage(),
                        ex.getMessage().contains("unsupported language"));
            }
        }
    }

    @Test
    public void bothEnginesRejectABlankName() {
        for (GreetingEngine e : engines()) {
            try {
                e.greetJSON("   ", "en");
                fail(e.name() + ": expected an exception for a blank name");
            } catch (Exception ex) {
                assertTrue(e.name() + " error wording differs: " + ex.getMessage(),
                        ex.getMessage().contains("must not be empty"));
            }
        }
    }

    /**
     * The strongest parity assertion in the suite.
     *
     * <p>The benchmark loop is a dependent chain of wrapping uint64 operations. If
     * Go and C++ produce the same checksum over millions of iterations, the two
     * implementations are provably computing the same thing — which is what makes
     * the timing comparison legitimate rather than apples-to-oranges.
     */
    @Test
    public void bothEnginesComputeTheSameChecksum() throws Exception {
        for (long iterations : new long[]{0, 1, 1_000, 1_000_000}) {
            JSONObject go = benchmark(new GoEngine(), iterations);
            JSONObject cpp = benchmark(new CppEngine(), iterations);

            assertEquals("checksum diverged at " + iterations + " iterations",
                    go.getString("checksum"), cpp.getString("checksum"));
            assertEquals(iterations, go.getLong("iterations"));
            assertEquals(iterations, cpp.getLong("iterations"));
        }
    }

    @Test
    public void bothEnginesReportPlausibleTimings() throws Exception {
        for (GreetingEngine e : engines()) {
            JSONObject small = benchmark(e, 10_000);
            JSONObject large = benchmark(e, 10_000_000);

            assertTrue(e.name() + " reported non-positive nanos", large.getLong("nanos") > 0);
            // 1000x the work must take measurably longer, or the loop was elided.
            assertTrue(e.name() + " timing does not scale with work: "
                            + small.getLong("nanos") + " vs " + large.getLong("nanos"),
                    large.getLong("nanos") > small.getLong("nanos"));
        }
    }

    private static JSONObject benchmark(GreetingEngine e, long iterations) throws Exception {
        return new JSONObject(new String(e.benchmarkJSON(iterations), StandardCharsets.UTF_8));
    }

    @Test
    public void bothEnginesCallBackFromABackgroundThread() throws Exception {
        String mainThread = "main";

        for (GreetingEngine e : engines()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicReference<String> callbackThread = new AtomicReference<>();
            List<Integer> updates = new ArrayList<>();

            e.greetAllAsync("Aniket", new EngineCallback() {
                @Override
                public void onProgress(int percent) {
                    callbackThread.set(Thread.currentThread().getName());
                    synchronized (updates) {
                        updates.add(percent);
                    }
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

            assertTrue(e.name() + " never called back within 10s", done.await(10, TimeUnit.SECONDS));
            assertNull(e.name() + " reported an error", failure.get());

            JSONArray greetings = new JSONArray(result.get());
            int expected = e.languages().split(",").length;
            assertEquals(e.name() + " wrong greeting count", expected, greetings.length());

            synchronized (updates) {
                assertEquals(e.name() + " wrong progress count", expected, updates.size());
                assertEquals(e.name() + " last progress should be 100",
                        100, (int) updates.get(updates.size() - 1));
            }

            // Neither engine may deliver callbacks on the main looper — the whole
            // reason MainActivity wraps them in runOnUiThread.
            assertTrue(e.name() + " called back on the main thread",
                    !mainThread.equals(callbackThread.get()));
        }
    }

    @Test
    public void asyncResultsMatchAcrossEngines() throws Exception {
        List<String> goMessages = allMessages(new GoEngine());
        List<String> cppMessages = allMessages(new CppEngine());

        assertEquals(goMessages, cppMessages);
    }

    private static List<String> allMessages(GreetingEngine e) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

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
                done.countDown();
            }
        });

        assertTrue(e.name() + " timed out", done.await(10, TimeUnit.SECONDS));

        JSONArray arr = new JSONArray(result.get());
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            messages.add(arr.getJSONObject(i).getString("language") + "="
                    + arr.getJSONObject(i).getString("message"));
        }
        return messages;
    }
}
