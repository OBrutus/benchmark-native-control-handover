package sh.locus.goandroid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // Both engines are constructed up front, which loads libgojni.so AND
    // libgreeter.so. Switching is then just picking a reference — no reloading.
    private final GreetingEngine goEngine = new GoEngine();
    private final GreetingEngine cppEngine = new CppEngine();

    private TextView output;
    private TextView versionText;
    private ProgressBar progress;
    private EditText nameInput;
    private Spinner langSpinner;
    private RadioGroup engineGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        output = findViewById(R.id.outputText);
        versionText = findViewById(R.id.versionText);
        progress = findViewById(R.id.progressBar);
        nameInput = findViewById(R.id.nameInput);
        langSpinner = findViewById(R.id.langSpinner);
        engineGroup = findViewById(R.id.engineGroup);

        // Flipping the radio re-reads everything from the newly selected engine,
        // so the version banner and the language list both prove which one is live.
        engineGroup.setOnCheckedChangeListener((group, id) -> bindEngine());
        bindEngine();

        ((Button) findViewById(R.id.greetButton)).setOnClickListener(v -> greet());
        ((Button) findViewById(R.id.greetAllButton)).setOnClickListener(v -> greetAll());
        ((Button) findViewById(R.id.benchButton)).setOnClickListener(v -> benchmark());
    }

    /** The only place the engine choice is read. */
    private GreetingEngine engine() {
        return engineGroup.getCheckedRadioButtonId() == R.id.engineCpp ? cppEngine : goEngine;
    }

    /** Repopulates engine-derived UI: version banner and language spinner. */
    private void bindEngine() {
        final GreetingEngine engine = engine();
        versionText.setText(engine.version());

        final List<String> languages =
                new ArrayList<>(Arrays.asList(engine.languages().split(",")));
        languages.add("xx (invalid)");

        langSpinner.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, languages));
    }

    /**
     * A synchronous call across the boundary — JNI + cgo for Go, plain JNI for C++.
     *
     * <p>Note that nothing here knows which engine it is talking to. Both throw on
     * a bad name or unknown language, and the catch block reports either.
     */
    private void greet() {
        final GreetingEngine engine = engine();
        final String label = (String) langSpinner.getSelectedItem();

        progress.setProgress(0);
        try {
            final long started = System.nanoTime();
            final byte[] json = engine.greetJSON(nameInput.getText().toString(),
                    languageCode(label));
            final long elapsedUs = (System.nanoTime() - started) / 1000;

            final JSONObject greeting = new JSONObject(new String(json, StandardCharsets.UTF_8));

            output.setText("message  : " + greeting.getString("message") + "\n"
                    + "language : " + greeting.getString("language") + "\n"
                    + "at       : " + greeting.getString("at") + "\n"
                    + "engine   : " + engine.name() + micros(elapsedUs) + "\n\n"
                    + "raw JSON from " + engine.name() + " (" + json.length + " bytes):\n"
                    + greeting.toString(2));
        } catch (Exception e) {
            output.setText(engine.name() + " returned an error:\n\n" + e.getMessage());
        }
    }

    /**
     * The reverse direction: native code calls back into Java.
     *
     * <p>For Go that callback comes from a goroutine's thread; for C++ from a
     * std::thread attached to the JVM. Identical from here — and identically
     * unsafe to touch views from, hence runOnUiThread.
     */
    private void greetAll() {
        final GreetingEngine engine = engine();
        final long started = System.nanoTime();

        progress.setProgress(0);
        output.setText("working… (" + engine.name() + ")");

        engine.greetAllAsync(nameInput.getText().toString(), new EngineCallback() {
            @Override
            public void onProgress(int percent) {
                runOnUiThread(() -> progress.setProgress(percent));
            }

            @Override
            public void onComplete(String resultJSON) {
                final long elapsedUs = (System.nanoTime() - started) / 1000;
                runOnUiThread(() -> renderAll(engine, resultJSON, elapsedUs));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progress.setProgress(0);
                    output.setText(engine.name() + " reported an error:\n\n" + message);
                });
            }
        });
    }

    private void renderAll(GreetingEngine engine, String resultJSON, long elapsedUs) {
        try {
            final JSONArray arr = new JSONArray(resultJSON);
            final StringBuilder sb = new StringBuilder()
                    .append(arr.length()).append(" greetings from ").append(engine.name())
                    .append(micros(elapsedUs)).append("\n\n");

            for (int i = 0; i < arr.length(); i++) {
                final JSONObject g = arr.getJSONObject(i);
                sb.append("  [").append(g.getString("language")).append("] ")
                        .append(g.getString("message")).append('\n');
            }
            output.setText(sb.toString());
        } catch (Exception e) {
            output.setText("Could not parse the response:\n\n" + e.getMessage());
        }
    }

    // --- benchmark ----------------------------------------------------------

    private static final long BENCH_ITERATIONS = 20_000_000L;
    private static final int BENCH_REPS = 5;

    /** One engine's measurement, taken from its fastest repetition. */
    private static final class Measurement {
        final String engine;
        final long nativeNanos;  // measured inside Go / C++: compute only
        final long wallNanos;    // measured here: compute + boundary crossing
        final String checksum;

        Measurement(String engine, long nativeNanos, long wallNanos, String checksum) {
            this.engine = engine;
            this.nativeNanos = nativeNanos;
            this.wallNanos = wallNanos;
            this.checksum = checksum;
        }

        long overheadNanos() {
            return Math.max(0, wallNanos - nativeNanos);
        }
    }

    /**
     * Benchmarks BOTH engines back to back, ignoring the radio button — the
     * comparison is the whole point, so running one at a time would be useless.
     *
     * <p>Runs off the main thread: at 20M iterations each pass takes long enough
     * to drop frames, and a janky UI thread would also pollute the numbers.
     */
    private void benchmark() {
        progress.setProgress(0);
        output.setText(String.format(Locale.US,
                "benchmarking %,d iterations × %d reps…", BENCH_ITERATIONS, BENCH_REPS));

        new Thread(() -> {
            try {
                final Measurement go = measure(goEngine);
                final Measurement cpp = measure(cppEngine);
                final String report = formatReport(go, cpp);
                runOnUiThread(() -> output.setText(report));
            } catch (Exception e) {
                runOnUiThread(() -> output.setText("benchmark failed:\n\n" + e.getMessage()));
            }
        }, "benchmark").start();
    }

    private static Measurement measure(GreetingEngine engine) throws Exception {
        // Warm-up. Pays first-call costs — lazy symbol resolution, page faults,
        // and for Go the initial cgo thread setup — outside the measurement.
        engine.benchmarkJSON(100_000);

        long bestWall = Long.MAX_VALUE;
        long bestNative = 0;
        String checksum = null;

        for (int i = 0; i < BENCH_REPS; i++) {
            final long started = System.nanoTime();
            final byte[] json = engine.benchmarkJSON(BENCH_ITERATIONS);
            final long wall = System.nanoTime() - started;

            final JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));

            // Keep native and wall from the SAME repetition, otherwise the
            // difference between two independent minima is meaningless.
            if (wall < bestWall) {
                bestWall = wall;
                bestNative = o.getLong("nanos");
            }
            checksum = o.getString("checksum");
        }

        return new Measurement(engine.name(), bestNative, bestWall, checksum);
    }

    private static String formatReport(Measurement go, Measurement cpp) {
        final StringBuilder sb = new StringBuilder();

        sb.append(String.format(Locale.US, "loop benchmark%n%,d iterations · best of %d%n%n",
                BENCH_ITERATIONS, BENCH_REPS));

        sb.append(String.format(Locale.US, "%-7s %11s %11s %9s%n",
                "engine", "native", "via JNI", "overhead"));
        for (Measurement m : new Measurement[]{go, cpp}) {
            sb.append(String.format(Locale.US, "%-7s %8.2f ms %8.2f ms %6d µs%n",
                    m.engine,
                    m.nativeNanos / 1e6,
                    m.wallNanos / 1e6,
                    m.overheadNanos() / 1000));
        }

        final double ratio = (double) go.nativeNanos / (double) cpp.nativeNanos;
        sb.append('\n');
        if (ratio >= 1.0) {
            sb.append(String.format(Locale.US, "C++ is %.2f× faster on compute%n", ratio));
        } else {
            sb.append(String.format(Locale.US, "Go is %.2f× faster on compute%n", 1.0 / ratio));
        }

        sb.append('\n').append("checksum\n")
                .append("  Go   ").append(go.checksum).append('\n')
                .append("  C++  ").append(cpp.checksum).append('\n')
                .append("  ").append(go.checksum.equals(cpp.checksum)
                        ? "match ✓ — both compiled the loop identically"
                        : "MISMATCH ✗ — the loops are not equivalent");

        return sb.toString();
    }

    /**
     * Formats elapsed time, switching to ms once µs stops being readable.
     *
     * <p>For the async path this is dominated by the deliberate 250ms-per-language
     * sleep in both engines, so it measures the harness, not the language. Only the
     * sync number says anything about boundary-crossing cost.
     */
    private static String micros(long us) {
        if (us >= 10_000) {
            return String.format(Locale.US, "  (%.1f ms)", us / 1000.0);
        }
        return String.format(Locale.US, "  (%d µs)", us);
    }

    /** Trims the spinner's "xx (invalid)" entry down to a bare language code. */
    private static String languageCode(String label) {
        return label.length() > 2 ? label.substring(0, 2) : label;
    }
}
