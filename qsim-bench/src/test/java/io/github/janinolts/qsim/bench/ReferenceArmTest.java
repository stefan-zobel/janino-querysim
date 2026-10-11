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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The javac reference J of P1 and P9: where it takes part, its own number of
 * operations per round, and the aggregation, where J/A is estimated like B/A
 * and does not change the classification; without J there is no estimate.
 */
class ReferenceArmTest {

    @Test
    void referenceIsEstimatedBesideTheFourArms() {
        List<RoundRecord> records = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(7);
        for (String point : List.of("with", "without")) {
            for (int fork = 0; fork < 3; fork++) {
                for (int round = 0; round < 10; round++) {
                    for (Arm arm : Arm.values()) {
                        if (arm == Arm.J && point.equals("without")) {
                            continue;
                        }
                        double factor = switch (arm) {
                            case B_PLUS -> 1 + Arm.PLUS_FRACTION;
                            case J -> 0.8;
                            default -> 1;
                        };
                        long nanos = Math.round(1_000_000 * factor * (1 + random.nextDouble() * 1e-3));
                        records.add(new RoundRecord("P9", point, "k", "xint", "local", fork, round, arm.label(), 0,
                                1, arm == Arm.B_PLUS ? 1 + Arm.PLUS_FRACTION : 1, nanos, nanos, 1000, 0, 0, -1, -1, 3, false,
                                "3.1.12", "3.1.19"));
                    }
                }
            }
        }
        List<Aggregator.PointResult> results = Aggregator.aggregate(records, Aggregator.Options.DEFAULT);
        assertEquals(2, results.size());
        Aggregator.PointResult with = results.stream().filter(r -> r.point().equals("with")).findFirst().orElseThrow();
        Aggregator.PointResult without = results.stream().filter(r -> r.point().equals("without")).findFirst()
                .orElseThrow();
        assertEquals(0.8, with.reference().ratio(), 0.01);
        assertEquals(3, with.reference().units());
        assertEquals(0, without.reference().units());
        assertEquals(Aggregator.Classification.SAME, with.classification());
        assertEquals(Aggregator.Classification.SAME, without.classification());
    }

    @Test
    void javacTakesPartInP1ExceptInXintAndInP9() {
        assertTrue(ForkMain.javacReference("P1", "tiered"));
        assertTrue(ForkMain.javacReference("P1", "c1"));
        assertFalse(ForkMain.javacReference("P1", "xint"));
        assertTrue(ForkMain.javacReference("P9", "xint"));
        assertFalse(ForkMain.javacReference("P2", "tiered"));
        assertFalse(ForkMain.javacReference("P7", "tiered"));
    }

    @Test
    void theReferenceOfP1HasOperationsOfItsOwn() throws Exception {
        Measurements.Point point = Measurements.of("P1", false, "Q_TINY*").get(0);
        MeasurementLoop.Settings settings = new MeasurementLoop.Settings(3, 20_000_000, 2, 10_000_000_000L, 1);
        List<RoundRecord> records = new ArrayList<>();
        try (IsolatedBackend a = JaninoInstallation.baseline().load();
             IsolatedBackend b = JaninoInstallation.candidate().load();
             IsolatedBackend bPrime = JaninoInstallation.candidate().load()) {
            CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, ReferenceArmTest.class.getClassLoader());
            MeasurementLoop.run(point, new MeasurementLoop.Arms(a, b, bPrime, new JavacBackend()), options, settings,
                    "tiered", "local", 0, records::add);
        }
        assertEquals(3 * Arm.WITH_REFERENCE.size(), records.size());
        Map<Integer, Map<String, RoundRecord>> rounds = records.stream().collect(Collectors.groupingBy(
                RoundRecord::round, Collectors.toMap(RoundRecord::arm, r -> r)));
        for (Map<String, RoundRecord> round : rounds.values()) {
            RoundRecord a = round.get("A");
            RoundRecord j = round.get("J");
            // javac takes a multiple of the time of Janino: fewer operations, so that its round is not longer.
            assertTrue(j.ops() < a.ops(), () -> "J " + j.ops() + ", A " + a.ops());
            assertEquals(a.ops(), round.get("B").ops());
            assertEquals(j.ops(), j.executedOps(), 1e-9);
        }
    }

    @Test
    void theReportOfP1ShowsJOverA() {
        Measurements.Point point = Measurements.of("P1", false, "Q_TINY*").get(0);
        List<RoundRecord> records = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(11);
        for (String mode : List.of("tiered", "xint")) {
            for (int fork = 0; fork < 3; fork++) {
                for (int round = 0; round < 10; round++) {
                    for (Arm arm : ForkMain.javacReference("P1", mode) ? Arm.WITH_REFERENCE : Arm.CORE) {
                        double factor = switch (arm) {
                            case B_PLUS -> 1 + Arm.PLUS_FRACTION;
                            case J -> 3;
                            default -> 1;
                        };
                        // J executes one operation per round, the others ten.
                        long ops = arm == Arm.J ? 1 : 10;
                        long nanos = Math.round(ops * 1_000_000 * factor * (1 + random.nextDouble() * 1e-3));
                        records.add(new RoundRecord("P1", point.id(), point.unitKey(), mode, "local", fork, round,
                                arm.label(), 0, ops, arm == Arm.B_PLUS ? ops * (1 + Arm.PLUS_FRACTION) : ops, nanos,
                                nanos, 1000 * ops, 0, 0, -1, -1, 3, false, "3.1.12", "3.1.19", 10));
                    }
                }
            }
        }
        PerformanceReport report = AggregateCommand.evaluate(new AggregateCommand.Data(records, List.of(), List.of()),
                Aggregator.Options.DEFAULT, 1.01);
        Map<String, Aggregator.PointResult> byMode = report.results().stream().collect(Collectors.toMap(
                Aggregator.PointResult::mode, r -> r));
        assertEquals(3, byMode.get("tiered").reference().ratio(), 0.01);
        assertEquals(0, byMode.get("xint").reference().units());
        for (Aggregator.PointResult r : report.results()) {
            assertEquals(Aggregator.Classification.SAME, r.classification(), r.mode());
        }
        String markdown = report.markdown();
        assertTrue(markdown.contains(" | J/A | Units | Notes |"), markdown);
        assertTrue(markdown.contains("the median of J/A over the points in tiered 3.00 (1 point)."), markdown);
        assertFalse(markdown.contains("Rows/s"), markdown);
        assertFalse(markdown.contains("NaN"), markdown);
    }
}
