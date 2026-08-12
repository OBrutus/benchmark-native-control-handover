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

import sh.locus.gocore.mobile.Greeter;
import sh.locus.gocore.mobile.Mobile;
import sh.locus.gocore.mobile.ProgressListener;

/**
 * Runs on a device or emulator, because the .aar contains arm64 machine code —
 * it cannot execute on a desktop JVM.
 *
 * <p>This is the test that actually proves the JNI bridge works end to end. Unit
 * tests of the Go logic live on the Go side ({@code go test ./...}); this covers
 * the boundary itself.
 */
@RunWith(AndroidJUnit4.class)
public class GoBridgeTest {

    @Test
    public void nativeLibraryLoadsAndReportsVersion() {
        String version = Mobile.version();

        assertTrue("version was blank: '" + version + "'", version.startsWith("gocore"));
        // Confirms the Go runtime is linked in and running on ARM.
        assertTrue("unexpected platform in '" + version + "'", version.contains("android/arm64"));
    }

    @Test
    public void greetJSONCrossesTheBoundary() throws Exception {
        Greeter greeter = Mobile.newGreeter();

        byte[] raw = greeter.greetJSON("Aniket", "en");
        JSONObject json = new JSONObject(new String(raw, StandardCharsets.UTF_8));

        assertEquals("Hello, Aniket!", json.getString("message"));
        assertEquals("en", json.getString("language"));
        assertTrue("missing timestamp", !json.getString("at").isEmpty());
    }

    @Test
    public void goErrorBecomesJavaException() {
        Greeter greeter = Mobile.newGreeter();

        try {
            greeter.greetJSON("Aniket", "xx");
            fail("expected an exception for an unsupported language");
        } catch (Exception e) {
            assertTrue("error message did not come from Go: " + e.getMessage(),
                    e.getMessage().contains("unsupported language"));
        }
    }

    @Test
    public void emptyNameIsRejectedByGo() {
        Greeter greeter = Mobile.newGreeter();

        try {
            greeter.greetJSON("   ", "en");
            fail("expected an exception for a blank name");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("must not be empty"));
        }
    }

    @Test
    public void languagesAreFlattenedToAString() {
        Greeter greeter = Mobile.newGreeter();

        List<String> codes = Arrays.asList(greeter.languages().split(","));

        assertEquals(greeter.languageCount(), codes.size());
        assertTrue("expected 'en' in " + codes, codes.contains("en"));
    }

    @Test
    public void goCallsBackIntoJava() throws Exception {
        Greeter greeter = Mobile.newGreeter();
        CountDownLatch done = new CountDownLatch(1);

        // Java cannot capture a mutable local in an anonymous class, so the
        // Kotlin `var`s become holders.
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        List<Integer> progressUpdates = new ArrayList<>();

        greeter.greetAllAsync("Aniket", new ProgressListener() {
            @Override
            public void onProgress(int percent) {
                // Deliberately not on the main thread — this is a Go goroutine.
                synchronized (progressUpdates) {
                    progressUpdates.add(percent);
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

        assertTrue("Go never called back within 10s", done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());

        JSONArray greetings = new JSONArray(result.get());
        assertEquals(greeter.languageCount(), greetings.length());

        synchronized (progressUpdates) {
            assertEquals("expected one progress callback per language",
                    greeter.languageCount(), progressUpdates.size());
            assertEquals("last progress should be 100",
                    100, (int) progressUpdates.get(progressUpdates.size() - 1));
        }
    }
}
