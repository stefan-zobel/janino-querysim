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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The time budget of the measured rounds of a point: when it ends the
 * measurement, how the rounds record it, and the note of the report.
 */
class TimeBudgetTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void theBudgetEndsTheRoundsAfterTheMinimum() {
        MeasurementLoop.Settings s = new MeasurementLoop.Settings(40, 150_000_000, 3, 120 * SECOND, 1, 8,
                240 * SECOND);
        // Rounds of 1 s: all 40 fit into the budget.
        assertTrue(s.continueAfter(39, 39 * SECOND));
        assertFalse(s.continueAfter(40, 40 * SECOND));
        // Rounds of 72 s: the minimum of 8 rounds, although the budget is exceeded after 4.
        assertTrue(s.continueAfter(7, 7 * 72 * SECOND));
        assertFalse(s.continueAfter(8, 8 * 72 * SECOND));
        // Rounds of 20 s: 12 rounds fit, a 13th would exceed the budget.
        assertTrue(s.continueAfter(11, 11 * 20 * SECOND));
        assertFalse(s.continueAfter(12, 12 * 20 * SECOND));
        // Without a budget, only the number of rounds counts.
        MeasurementLoop.Settings unlimited = new MeasurementLoop.Settings(40, 150_000_000, 3, 120 * SECOND, 1);
        assertTrue(unlimited.continueAfter(39, Long.MAX_VALUE / 2));
        assertFalse(unlimited.continueAfter(40, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MeasurementLoop.Settings(40, 150_000_000, 3, 120 * SECOND, 1, 0, SECOND));
    }

    @Test
    void theLoopStopsAtTheBudgetAndRecordsThePlannedRounds() throws Exception {
        Measurements.Point point = Measurements.of("P1", false, "Q_TINY*").get(0);
        // A budget of 1 ns ends the measurement after the minimum of 2 rounds.
        MeasurementLoop.Settings budget = new MeasurementLoop.Settings(20, 1_000_000, 1, 5 * SECOND, 1, 2, 1);
        List<RoundRecord> records = new ArrayList<>();
        try (IsolatedBackend a = JaninoInstallation.baseline().load();
             IsolatedBackend b = JaninoInstallation.candidate().load();
             IsolatedBackend bPrime = JaninoInstallation.candidate().load()) {
            CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, TimeBudgetTest.class.getClassLoader());
            MeasurementLoop.run(point, new MeasurementLoop.Arms(a, b, bPrime), options, budget, "xint", "local", 0,
                    records::add);
        }
        assertEquals(2 * Arm.CORE.size(), records.size());
        assertTrue(records.stream().allMatch(r -> r.round() < 2 && r.plannedRounds() == 20));
    }

    @Test
    void theReportNotesTheForksThatTheBudgetLimited() {
        // Fork 0 has all 10 planned rounds, fork 1 only 8, fork 2 only 9.
        List<RoundRecord> records = new ArrayList<>();
        int[] rounds = {10, 8, 9};
        for (int fork = 0; fork < rounds.length; fork++) {
            for (int round = 0; round < rounds[fork]; round++) {
                for (Arm arm : Arm.CORE) {
                    long nanos = 1_000_000 + round + (arm == Arm.B_PLUS ? 1_000_000 / 33 : 0);
                    records.add(new RoundRecord("P2", "columns:2048/WHOLE_STAGE", "k", "xint", "local", fork, round,
                            arm.label(), 0, 1, arm == Arm.B_PLUS ? 1 + Arm.PLUS_FRACTION : 1, nanos, nanos, 1000, 0,
                            0, -1, -1, 3, false, "3.1.12", "3.1.19", 10));
                }
            }
        }
        List<String> notes = Aggregator.aggregate(records, Aggregator.Options.DEFAULT).get(0).notes();
        assertTrue(notes.contains("time budget: 8 to 9 of 10 rounds in 2 of 3 forks"), notes::toString);

        // Rounds without the planned number, e.g. of older raw data or of P4, get no note.
        List<RoundRecord> unknown = records.stream().map(r -> new RoundRecord(r.measurement(), r.point(),
                r.unitKey(), r.mode(), r.replica(), r.fork(), r.round(), r.arm(), r.position(), r.ops(),
                r.executedOps(), r.nanos(), r.cpuNanos(), r.allocatedBytes(), r.gcMillis(), r.jitMillis(),
                r.involuntarySwitches(), r.stealTicks(), r.warmupRounds(), r.warmupCapped(), r.baselineVersion(),
                r.candidateVersion())).toList();
        assertTrue(Aggregator.aggregate(unknown, Aggregator.Options.DEFAULT).get(0).notes().stream()
                .noneMatch(n -> n.startsWith("time budget")));
    }

    @Test
    void olderRawDataReadsWithoutThePlannedRounds() {
        Gson gson = new Gson();
        RoundRecord r = record();
        String json = gson.toJson(r);
        assertEquals(10, gson.fromJson(json, RoundRecord.class).plannedRounds());
        String old = json.replace(",\"plannedRounds\":10", "");
        assertFalse(old.contains("plannedRounds"));
        assertEquals(0, gson.fromJson(old, RoundRecord.class).plannedRounds());
    }

    private static RoundRecord record() {
        return new RoundRecord("P2", "columns:8/WHOLE_STAGE", "k", "xint", "local", 0, 0, "A", 0, 1, 1, 1000, 1000,
                1000, 0, 0, -1, -1, 3, false, "3.1.12", "3.1.19", 10);
    }
}
