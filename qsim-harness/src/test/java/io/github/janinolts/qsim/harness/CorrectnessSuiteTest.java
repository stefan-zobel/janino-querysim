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

package io.github.janinolts.qsim.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The fixed scenarios, compared across all backends and reconciled with the
 * ledger of the development line of the candidate. The reports go to
 * {@code target/qsim-reports/<timestamp>}.
 */
class CorrectnessSuiteTest {

    @Test
    void scenariosAgreeWithTheLedger() {
        List<CompilerBackend> all = TestBackends.all();
        DifferentialRunner runner = new DifferentialRunner(
                new DifferentialRunner.Backends(all.get(0), all.get(1), all.get(2)),
                CorrectnessSuiteTest.class.getClassLoader());
        Ledger ledger = Ledger.forLine(Ledger.lineOf(all.get(2).version()));

        CorrectnessRun run = CorrectnessRun.run("smoke", runner, ledger, Workloads.smoke());

        Path dir = Path.of("target", "qsim-reports",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        CorrectnessReport.write(run, dir);
        assertTrue(run.passed(), () -> CorrectnessReport.markdown(run));
    }

    /** The regression corpus and the seeds that the ledger names. */
    @Test
    void regressionAgreesWithTheLedger() {
        List<CompilerBackend> all = TestBackends.all();
        DifferentialRunner runner = new DifferentialRunner(
                new DifferentialRunner.Backends(all.get(0), all.get(1), all.get(2)),
                CorrectnessSuiteTest.class.getClassLoader());
        Ledger ledger = Ledger.forLine(Ledger.lineOf(all.get(2).version()));

        CorrectnessRun run = CorrectnessRun.run("regression", runner, ledger,
                Workloads.regression(ledger, RandomPlans.withDefaults()));

        Path dir = Path.of("target", "qsim-reports",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-regression");
        CorrectnessReport.write(run, dir);
        assertTrue(run.passed(), () -> CorrectnessReport.markdown(run));
    }
}
