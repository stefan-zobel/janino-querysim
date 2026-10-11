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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Every kind of operation in the measurement loop with real backends and tiny
 * rounds, the selection of points by {@code bench}, a cold start in fresh
 * JVMs, and the report over all of them.
 */
class OperationTest {

    private static final MeasurementLoop.Settings TINY = new MeasurementLoop.Settings(3, 1_000_000, 1,
            5_000_000_000L, 1);

    @Test
    void everyOperationMeasures() throws Exception {
        List<Measurements.Point> points = new ArrayList<>();
        points.addAll(Measurements.of("P3", true, "Q_TINY*"));
        points.addAll(Measurements.of("P5", false, "*threads=2"));
        points.addAll(Measurements.of("P6", false, "classes:4,overloads:4"));
        points.addAll(Measurements.of("P7", false, "Q_STRING*"));
        points.addAll(Measurements.of("P9", false, "Q_TINY*,Q_FILTER_PROJECT*"));
        points.addAll(Measurements.of("P10", false, "Q_TINY*,Q_AGG*"));
        assertEquals(4 + 1 + 2 + 3 + 2 + 2, points.size());

        List<RoundRecord> records = new ArrayList<>();
        List<PointInfo> infos = new ArrayList<>();
        try (IsolatedBackend a = JaninoInstallation.baseline().load();
             IsolatedBackend b = JaninoInstallation.candidate().load();
             IsolatedBackend bPrime = JaninoInstallation.candidate().load()) {
            CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, OperationTest.class.getClassLoader());
            MeasurementLoop.Arms withJavac = new MeasurementLoop.Arms(a, b, bPrime, new JavacBackend());
            MeasurementLoop.Arms withoutJavac = new MeasurementLoop.Arms(a, b, bPrime);
            for (Measurements.Point p : points) {
                if (!p.measurement().equals("P9")) {
                    assertNull(BenchCommand.check(p, a, b, options), p.id());
                }
                infos.add(new PointInfo(p.measurement(), p.id(), p.unitKey(), p.sourceChars(), p.attributes(), true,
                        null));
                for (int fork = 0; fork < 2; fork++) {
                    MeasurementLoop.run(p, ForkMain.javacReference(p.measurement(), "xint") ? withJavac
                            : withoutJavac, options, TINY, "xint", "local", fork, records::add);
                }
            }
            // P9 measures only units whose method bodies differ; 3.1.19 and 3.1.12 generate the same.
            Measurements.Point p9 = Measurements.of("P9", false, "Q_TINY*").get(0);
            String reason = BenchCommand.check(p9, a, b, options);
            assertTrue(reason == null || reason.startsWith("identical code"), reason);
        }

        Map<String, Set<String>> arms = records.stream().collect(Collectors.groupingBy(RoundRecord::measurement,
                Collectors.mapping(RoundRecord::arm, Collectors.toSet())));
        assertEquals(Set.of("A", "B", "B'", "B+", "J"), arms.get("P9"));
        assertEquals(Set.of("A", "B", "B'", "B+"), arms.get("P3"));
        for (RoundRecord r : records) {
            assertTrue(r.ops() >= 1 && r.nanos() > 0, r::toString);
            assertEquals(r.arm().equals("B+") ? r.ops() * (1 + Arm.PLUS_FRACTION) : r.ops(), r.executedOps(), 1e-9,
                    r::toString);
            if (r.measurement().equals("P5") || r.measurement().equals("P6")) {
                assertTrue(r.allocatedBytes() > 0, r::toString);
            }
        }

        AggregateCommand.Data data = new AggregateCommand.Data(records, List.of(), List.of(), infos, List.of(),
                List.of());
        PerformanceReport report = AggregateCommand.evaluate(data, Aggregator.Options.DEFAULT, 1.01);
        assertEquals(points.size(), report.results().size());
        for (Aggregator.PointResult r : report.results()) {
            assertEquals(r.measurement().equals("P9"), r.reference().units() > 0, r.point());
        }
        String markdown = report.markdown();
        for (String section : List.of("## P3: phases", "### Shares of the phases", "## P5: concurrency",
                "### Throughput", "## P6: class resolution", "## P7: debugging information", "| J/A |",
                "## P9: runtime of the generated code", "## P10: target version")) {
            assertTrue(markdown.contains(section), section);
        }
        assertFalse(markdown.contains("NaN"), markdown);
    }

    /**
     * The series {@code tryJumps} is where the candidate generates other code
     * than 3.1.12: P8 finds level L3, which the ledger explains through the
     * feature tag {@code try-jump}, and P9 measures it.
     */
    @Test
    void tryJumpsDifferAndAreExplained() throws Exception {
        List<Measurements.Point> p8 = Measurements.of("P8", false, "tryJumps:2/*");
        Measurements.Point p9 = Measurements.of("P9", false, "tryJumps:2/*").get(0);
        try (IsolatedBackend a = JaninoInstallation.baseline().load();
             IsolatedBackend b = JaninoInstallation.candidate().load()) {
            CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, OperationTest.class.getClassLoader());
            List<ClassFileMeasurement.Row> rows = ClassFileMeasurement.measure(p8, a, b, options);
            assertTrue(rows.stream().anyMatch(r -> "L3".equals(r.level())), rows::toString);
            assertTrue(rows.stream().allMatch(r -> r.tags().contains("try-jump")), rows::toString);
            AggregateCommand.Data data = new AggregateCommand.Data(List.of(), rows,
                    List.of(Environment.current("local", Map.of(), a.version(), b.version(), Map.of())));
            PerformanceReport report = AggregateCommand.evaluate(data, Aggregator.Options.DEFAULT, 1.01);
            assertTrue(report.classFileFindings().isEmpty(), report.classFileFindings()::toString);
            assertNull(BenchCommand.check(p9, a, b, options));
        }
    }

    @Test
    void positiveControlWaitsForOneThirtyThirdOfItsTime() {
        long start = System.nanoTime() - 33_000_000;
        long end = System.nanoTime();
        Arm.waitExtra(start, end);
        long waited = System.nanoTime() - end;
        assertTrue(waited >= (end - start) / 33 && waited < 200_000_000, () -> "waited " + waited + " ns");
    }

    @Test
    void coldStartsInFreshJvms() throws Exception {
        List<Measurements.Point> points = Measurements.of("P4", false, "Q_TINY*");
        assertEquals(1, points.size());
        Path out = Files.createTempDirectory("qsim-p4").resolve("rounds-P4-tiered.jsonl");
        List<String> installations = List.of(
                "-D" + JaninoInstallation.BASELINE_DIR_PROPERTY + "="
                        + JaninoInstallation.baseline().directory().toAbsolutePath(),
                "-D" + JaninoInstallation.CANDIDATE_DIR_PROPERTY + "="
                        + JaninoInstallation.candidate().directory().toAbsolutePath());
        ColdStart.run(points, new ColdStart.Settings(1, 1, 300, 1), List.of("-Xmx256m"), installations, "tiered",
                "local", "3.1.12", "3.1.19", false, out);
        List<RoundRecord> records = AggregateCommand.read(List.of(out.getParent())).rounds();
        assertEquals(4, records.size());
        for (RoundRecord r : records) {
            assertEquals("P4", r.measurement());
            assertEquals(1, r.ops());
            assertEquals(r.arm().equals("B+") ? 1 + Arm.PLUS_FRACTION : 1, r.executedOps(), 1e-12);
            assertTrue(r.nanos() > 1_000_000 && r.allocatedBytes() > 0, r::toString);
        }
    }
}
