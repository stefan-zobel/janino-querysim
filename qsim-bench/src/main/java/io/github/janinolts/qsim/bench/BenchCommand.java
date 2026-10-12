/*
 * Copyright 2026 the janino-querysim authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.janinolts.qsim.bench;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.ClassFileComparison;
import io.github.janinolts.qsim.harness.CompiledQuery;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;
import io.github.janinolts.qsim.harness.Limits;
import io.github.janinolts.qsim.harness.QueryRunner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The command {@code bench}: measures in fresh JVMs and writes only raw data
 * to {@code <out>/<replica>/}.
 * <p>
 * First this JVM selects the points that can be measured: both versions
 * compile the unit; for P9 the method bodies differ (level L3), for P10 the
 * class files of the baseline load in both configurations. It writes the
 * selection to {@code points.jsonl}, the class files of P8 and P7 and, unless
 * {@code --points} names the points, the limits of P2. Then it starts the forks of each measurement and mode; P4
 * starts a JVM per measurement instead.
 */
final class BenchCommand {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BenchCommand() {
    }

    static int run(String[] argv) throws IOException, InterruptedException {
        Args args = new Args(argv, Set.of("extended", "prime-first"));
        List<String> requested = args.positional().isEmpty() ? List.of("P1", "P8") : args.positional();
        for (String m : requested) {
            if (!Measurements.ALL.contains(m)) {
                throw new IllegalArgumentException("Unknown measurement: " + m + " (P1 to P10)");
            }
        }
        List<String> measurements = Measurements.ALL.stream().filter(requested::contains).toList();
        List<Mode> modes = new ArrayList<>();
        for (String m : args.get("mode", "xint,tiered").split(",")) {
            modes.add(Mode.ofLabel(m.strip()));
        }
        String replica = args.get("replica", System.getenv().getOrDefault("QSIM_REPLICA", "local"));
        Path dir = Path.of(args.get("out", "results")).resolve(replica);
        Files.createDirectories(dir);
        boolean extended = args.flag("extended");
        String patterns = args.get("points", null);
        PlusLoader plusLoader = PlusLoader.ofLabel(args.get("plus-loader", PlusLoader.SHARED.label()));
        boolean primeFirst = args.flag("prime-first");
        Path baselineDir = JaninoInstallation.baseline().directory().toAbsolutePath();
        Path candidateDir = JaninoInstallation.candidate().directory().toAbsolutePath();
        List<String> installations = List.of("-D" + JaninoInstallation.BASELINE_DIR_PROPERTY + "=" + baselineDir,
                "-D" + JaninoInstallation.CANDIDATE_DIR_PROPERTY + "=" + candidateDir);
        CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, BenchCommand.class.getClassLoader());

        Map<String, Long> seconds = new LinkedHashMap<>();
        long started = System.nanoTime();
        String baselineVersion;
        String candidateVersion;
        List<PointInfo> infos = new ArrayList<>();
        Map<String, List<Measurements.Point>> selected = new LinkedHashMap<>();
        try (IsolatedBackend baseline = JaninoInstallation.baseline().load();
             IsolatedBackend candidate = JaninoInstallation.candidate().load()) {
            baselineVersion = baseline.version();
            candidateVersion = candidate.version();
            for (String m : measurements) {
                List<Measurements.Point> points = Measurements.of(m, extended, patterns);
                if (m.equals("P8")) {
                    List<ClassFileMeasurement.Row> rows = ClassFileMeasurement.measure(points, baseline, candidate,
                            options);
                    writeLines(dir.resolve("classfiles.jsonl"), rows);
                    System.out.println("P8: " + rows.size() + " rows");
                    continue;
                }
                List<Measurements.Point> kept = new ArrayList<>();
                for (Measurements.Point p : points) {
                    String reason = check(p, baseline, candidate, options);
                    infos.add(new PointInfo(m, p.id(), p.unitKey(), p.sourceChars(), p.attributes(), reason == null,
                            reason));
                    if (reason == null) {
                        kept.add(p);
                    }
                }
                System.out.println(m + ": " + kept.size() + " of " + points.size() + " points measurable");
                selected.put(m, kept);
                if (m.equals("P2") && patterns == null) {
                    // A run that names its points, e.g. to confirm a finding, measures only those.
                    Map<String, String> versions = new LinkedHashMap<>();
                    JavacBackend javac = new JavacBackend();
                    versions.put(javac.id(), javac.version());
                    versions.put(baseline.id(), baseline.version());
                    versions.put(candidate.id(), candidate.version());
                    writeLines(dir.resolve("limits.jsonl"), LimitSearch.run(Series.LIMIT_SERIES, versions,
                            installations, args.getLong("limit-timeout-seconds", 600), dir));
                }
                if (m.equals("P7")) {
                    writeLines(dir.resolve("debug-classfiles.jsonl"),
                            ClassFileMeasurement.measureDebug(kept, baseline, candidate, options));
                }
            }
        }
        writeLines(dir.resolve(PointInfo.FILE), infos);
        seconds.put("select", secondsSince(started));

        Map<String, List<String>> jvmOptions = new LinkedHashMap<>();
        for (Map.Entry<String, List<Measurements.Point>> e : selected.entrySet()) {
            String m = e.getKey();
            if (e.getValue().isEmpty()) {
                continue;
            }
            Path pointsFile = dir.resolve("points-" + m + ".txt");
            Files.write(pointsFile, e.getValue().stream().map(Measurements.Point::id).toList(),
                    StandardCharsets.UTF_8);
            for (Mode mode : modes) {
                long modeStarted = System.nanoTime();
                List<String> options0 = jvmOptions(m, mode);
                jvmOptions.put(m + " " + mode.label(), options0);
                if (m.equals("P4")) {
                    ColdStart.Settings settings = new ColdStart.Settings(args.getInt("cold-forks", 3),
                            args.getInt("cold-rounds", 10), args.getLong("cold-timeout-seconds", 600),
                            args.getLong("seed", 1));
                    ColdStart.run(e.getValue(), settings, options0, installations, mode.label(), replica,
                            baselineVersion, candidateVersion, extended,
                            dir.resolve("rounds-P4-" + mode.label() + ".jsonl"));
                    seconds.put(m + " " + mode.label(), secondsSince(modeStarted));
                    continue;
                }
                for (int fork = 0; fork < args.getInt("forks", 3); fork++) {
                    String name = m + "-" + mode.label() + "-" + fork;
                    List<String> command = new ArrayList<>(m.equals("P5") ? List.of() : taskset());
                    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                    command.addAll(options0);
                    command.addAll(installations);
                    command.add("-cp");
                    command.add(System.getProperty("java.class.path"));
                    command.add(ForkMain.class.getName());
                    command.addAll(List.of("--measurement", m, "--mode", mode.label(), "--replica", replica,
                            "--fork", Integer.toString(fork), "--rounds", args.get("rounds", "40"),
                            "--round-millis", args.get("round-millis", "150"), "--seed", args.get("seed", "1"),
                            "--min-rounds", args.get("min-rounds", "8"),
                            "--point-seconds", args.get("point-seconds", "240"),
                            "--points-file", pointsFile.toString(), "--out",
                            dir.resolve("rounds-" + name + ".jsonl").toString()));
                    if (extended) {
                        command.add("--extended");
                    }
                    if (plusLoader != PlusLoader.SHARED) {
                        command.addAll(List.of("--plus-loader", plusLoader.label()));
                    }
                    if (primeFirst) {
                        command.add("--prime-first");
                    }
                    System.out.println(m + " " + mode.label() + " fork " + fork + " ...");
                    Process process = new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(dir.resolve("fork-" + name + ".log").toFile())
                            .start();
                    if (!process.waitFor(args.getInt("fork-timeout-minutes", 120), TimeUnit.MINUTES)) {
                        process.destroyForcibly();
                        System.err.println("TIMEOUT: " + m + " " + mode.label() + " fork " + fork);
                        return 2;
                    }
                    if (process.exitValue() != 0) {
                        System.err.println("CRASH: " + m + " " + mode.label() + " fork " + fork + " exited with "
                                + process.exitValue() + "; see the log in " + dir);
                        return 2;
                    }
                }
                seconds.put(m + " " + mode.label(), secondsSince(modeStarted));
            }
        }
        Environment env = Environment.current(replica, jvmOptions, baselineVersion, candidateVersion, seconds,
                plusLoader.describe(primeFirst));
        Files.writeString(dir.resolve(Environment.FILE), GSON.toJson(env), StandardCharsets.UTF_8);
        System.out.println("Raw data in " + dir.toAbsolutePath());
        return 0;
    }

    /**
     * Returns the JVM options of a measurement in a mode: P5 uses the parallel
     * collector, whose threads belong to what it measures; P4 leaves out
     * pre-touching the heap, which would only lengthen every start.
     */
    static List<String> jvmOptions(String measurement, Mode mode) {
        List<String> options = new ArrayList<>();
        for (String o : Mode.COMMON_OPTIONS) {
            if (measurement.equals("P5") && o.equals("-XX:+UseSerialGC")) {
                options.add("-XX:+UseParallelGC");
            } else if (!(measurement.equals("P4") && o.equals("-XX:+AlwaysPreTouch"))) {
                options.add(o);
            }
        }
        options.addAll(mode.options());
        return options;
    }

    /**
     * Checks whether a point can be measured.
     *
     * @return {@code null} if it can, or why not
     */
    static String check(Measurements.Point point, IsolatedBackend baseline, IsolatedBackend candidate,
            CompileOptions options) {
        try {
            GeneratedQuery query = point.generate();
            return switch (point.work()) {
                case Measurements.Work.Compile c -> compileBoth(query, baseline, candidate, c.debug().apply(options));
                case Measurements.Work.Threads t -> compileBoth(query, baseline, candidate, options);
                case Measurements.Work.ColdStart c -> compileBoth(query, baseline, candidate, options);
                case Measurements.Work.OnePhase p -> {
                    try (Operation op = new Operation.Phases(query,
                            new MeasurementLoop.Arms(baseline, candidate, candidate), options,
                            Measurements.Phase.INSTANTIATE)) {
                        for (Arm arm : List.of(Arm.A, Arm.B)) {
                            op.execute(arm, 1, op.prepare(arm, 1));
                        }
                    }
                    yield null;
                }
                case Measurements.Work.Run r -> {
                    CompiledQuery a = QueryRunner.compile(query, baseline, options);
                    CompiledQuery b = QueryRunner.compile(query, candidate, options);
                    if (a.failure() != null || b.failure() != null) {
                        yield a.failure() != null ? describe(baseline, a.failure()) : describe(candidate, b.failure());
                    }
                    ClassFileComparison.Level level = ClassFileComparison.compare(a.classFiles(), b.classFiles())
                            .level();
                    if (level != ClassFileComparison.Level.L3) {
                        yield "identical code (" + level + ")";
                    }
                    QueryRunner.checksum(query, a, point.tables());
                    QueryRunner.checksum(query, b, point.tables());
                    yield null;
                }
                case Measurements.Work.Load l -> {
                    CompiledQuery shipped = QueryRunner.compile(query, baseline, options);
                    CompiledQuery v52 = QueryRunner.compile(query, baseline, options.withTargetVersion(8));
                    if (shipped.failure() != null || v52.failure() != null) {
                        yield describe(baseline, shipped.failure() != null ? shipped.failure() : v52.failure());
                    }
                    ClassFileLoader.loadAll(options.parent(), shipped.classFiles());
                    try {
                        ClassFileLoader.loadAll(options.parent(), v52.classFiles());
                    } catch (LinkageError e) {
                        yield "loads only as version 50: " + e;
                    }
                    yield null;
                }
            };
        } catch (RuntimeException | LinkageError e) {
            String message = String.valueOf(e.getMessage());
            return e.getClass().getSimpleName() + ": "
                    + (message.length() > 200 ? message.substring(0, 200) + "..." : message);
        }
    }

    /** Compiles, loads and initializes the unit with both versions; returns why it failed, or {@code null}. */
    private static String compileBoth(GeneratedQuery query, CompilerBackend baseline, CompilerBackend candidate,
            CompileOptions options) {
        for (CompilerBackend backend : List.of(baseline, candidate)) {
            CompiledQuery c = QueryRunner.compile(query, backend, options);
            if (c.failure() != null) {
                return describe(backend, c.failure());
            }
            MeasurementLoop.execute(query, backend, options, 1);
        }
        return null;
    }

    /** Describes a failed compilation briefly: a limit of the JVM as such, with the message of the innermost cause. */
    static String describe(CompilerBackend backend, CompileResult failure) {
        return backend.id() + (Limits.isLimit(failure) ? " hits a limit of the JVM: " : " does not compile: "
                + failure.outcome() + ": ") + rootMessage(failure, 200);
    }

    /** Returns the message of the innermost cause of a failed compilation, shortened, on one line. */
    static String rootMessage(CompileResult failure, int maxLength) {
        Throwable root = failure.cause();
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root != null && root.getMessage() != null ? root.getMessage()
                : String.valueOf(failure.message());
        message = message.replace('\n', ' ');
        return message.length() > maxLength ? message.substring(0, maxLength) + "..." : message;
    }

    private static long secondsSince(long nanoTime) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - nanoTime);
    }

    private static void writeLines(Path file, List<?> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        rows.forEach(r -> sb.append(GSON.toJson(r)).append('\n'));
        Files.writeString(file, sb, StandardCharsets.UTF_8);
    }

    /** Returns the prefix that binds a fork to the upper half of the cores, on Linux with {@code taskset}. */
    private static List<String> taskset() {
        int cores = Runtime.getRuntime().availableProcessors();
        boolean linux = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
        if (!linux || cores < 2 || !Files.isExecutable(Path.of("/usr/bin/taskset"))) {
            return List.of();
        }
        return List.of("/usr/bin/taskset", "-c", (cores / 2) + "-" + (cores - 1));
    }
}
