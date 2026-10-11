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
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.CompiledQuery;
import io.github.janinolts.qsim.harness.Instances;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;
import io.github.janinolts.qsim.harness.QueryRunner;

import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measurement P4, the cold start: the time from the start of {@code main} in a
 * fresh JVM to the first instance of the compiled unit, which includes
 * loading the classes of Janino. Every start is a JVM of its own; a round
 * starts each of the four arms once, in a random order, and the rounds are
 * grouped into forks for the statistics.
 * <p>
 * {@code B'} is another start of the candidate. {@code B+} measures its own
 * time and then waits actively for another 1/33 of it, as in the other
 * measurements; a start counts as one operation.
 */
public final class ColdStart {

    /**
     * The settings of P4.
     *
     * @param forks the groups of rounds
     * @param rounds the rounds per group, each with one start per arm
     * @param timeoutSeconds the limit of one start
     * @param seed the seed of the order of the arms
     */
    record Settings(int forks, int rounds, long timeoutSeconds, long seed) {
    }

    /** The result of one start, written by the child as one line of JSON. */
    record Start(long nanos, long cpuNanos, long allocatedBytes) {
    }

    /** Consumes the instances, so that the JIT compiler cannot remove them. */
    private static volatile int sink;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private ColdStart() {
    }

    /**
     * Measures the points in one mode and writes the rounds.
     *
     * @param jvmOptions the options of the child JVMs
     * @param installations the system properties that name the directories of the Janino versions
     */
    static void run(List<Measurements.Point> points, Settings settings, List<String> jvmOptions,
            List<String> installations, String mode, String replica, String baselineVersion, String candidateVersion,
            boolean extended, Path out) throws IOException, InterruptedException {
        Gson gson = new Gson();
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (Measurements.Point point : points) {
                SplittableRandom random = new SplittableRandom(settings.seed() * 31 + (replica + "|" + mode + "|"
                        + point.id()).hashCode());
                for (int fork = 0; fork < settings.forks(); fork++) {
                    for (int round = 0; round < settings.rounds(); round++) {
                        List<Arm> order = new ArrayList<>(Arm.CORE);
                        Collections.shuffle(order, new java.util.Random(random.nextLong()));
                        for (int position = 0; position < order.size(); position++) {
                            Arm arm = order.get(position);
                            Start s = start(point, arm, settings, jvmOptions, installations, extended, out, gson);
                            RoundRecord r = new RoundRecord("P4", point.id(), point.unitKey(), mode, replica, fork,
                                    round, arm.label(), position, 1,
                                    arm == Arm.B_PLUS ? 1 + Arm.PLUS_FRACTION : 1, s.nanos(), s.cpuNanos(),
                                    s.allocatedBytes(), 0, 0, -1, -1, 0, false, baselineVersion, candidateVersion);
                            w.write(gson.toJson(r));
                            w.write('\n');
                        }
                    }
                    w.flush();
                }
                System.out.println("P4 " + mode + " " + point.id() + " done");
            }
        }
    }

    private static Start start(Measurements.Point point, Arm arm, Settings settings, List<String> jvmOptions,
            List<String> installations, boolean extended, Path out, Gson gson)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.addAll(installations);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ColdStart.class.getName());
        command.addAll(List.of("--point", point.id(), "--arm", arm.label()));
        if (extended) {
            command.add("--extended");
        }
        Path log = out.resolveSibling(out.getFileName() + ".start.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(settings.timeoutSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("TIMEOUT: P4 " + point.id() + " " + arm.label());
        }
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        if (process.exitValue() != 0 || !last.startsWith("{")) {
            throw new IllegalStateException("P4 " + point.id() + " " + arm.label() + " exited with "
                    + process.exitValue() + ": " + String.join("\n", lines));
        }
        return gson.fromJson(last, Start.class);
    }

    /**
     * The child: {@code --point <id> --arm <A|B|B'|B+> [--extended]}; prints
     * one line of JSON.
     */
    public static void main(String[] argv) {
        Args args = new Args(argv, Set.of("extended"));
        String id = args.get("point", null);
        Arm arm = Arm.ofLabel(args.get("arm", "A"));
        Measurements.Point point = Measurements.of("P4", args.flag("extended"), null).stream()
                .filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No point " + id + " in P4"));
        GeneratedQuery query = point.generate();
        CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, ColdStart.class.getClassLoader());

        long alloc0 = THREADS.getCurrentThreadAllocatedBytes();
        long cpu0 = THREADS.getCurrentThreadCpuTime();
        long t0 = System.nanoTime();
        JaninoInstallation installation = arm == Arm.A ? JaninoInstallation.baseline()
                : JaninoInstallation.candidate();
        IsolatedBackend backend = installation.load();
        CompiledQuery compiled = QueryRunner.compile(query, backend, options);
        if (compiled.failure() != null) {
            throw new IllegalStateException(backend + " does not compile " + query.unit() + ": " + compiled.failure());
        }
        int h = 0;
        for (int u = 0; u < query.units().size(); u++) {
            try {
                h += Instances.instantiate(query.units().get(u), compiled.mainClass(u)).hashCode();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (arm == Arm.B_PLUS) {
            h += Arm.waitExtra(t0, System.nanoTime());
        }
        long nanos = System.nanoTime() - t0;
        long cpu = THREADS.getCurrentThreadCpuTime() - cpu0;
        long alloc = THREADS.getCurrentThreadAllocatedBytes() - alloc0;
        sink = h;
        System.out.println(new Gson().toJson(new Start(nanos, cpu, alloc)));
    }
}
