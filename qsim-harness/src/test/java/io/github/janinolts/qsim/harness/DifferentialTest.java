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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.harness.Difference.Assessment;
import io.github.janinolts.qsim.harness.Difference.Kind;
import io.github.janinolts.qsim.harness.Difference.Pair;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Milestone M4: the differential harness detects a backend with a wrong
 * result as {@code WRONG}, and the ledger fails in both directions.
 */
class DifferentialTest {

    /**
     * A candidate that compiles with javac, but turns every addition of the
     * source into a subtraction: {@code +} and, in the ANSI mode,
     * {@code Math.addExact}.
     */
    private static final class WrongBackend implements CompilerBackend {

        private final CompilerBackend javac = TestBackends.all().get(0);

        @Override
        public String id() {
            return "JLTS";
        }

        @Override
        public String version() {
            return "wrong";
        }

        @Override
        public CompileResult compile(GeneratedUnit u, CompileOptions options) {
            GeneratedUnit wrong = new GeneratedUnit(u.kind(), u.packageName(), u.className(), u.imports(),
                    u.extendedClass(), u.implementedInterfaces(), u.body().replace(" + ", " - ").replace("Math.addExact(", "Math.subtractExact("), u.parameters(),
                    u.returnType(), u.instantiation(), u.references(), u.metadata());
            return javac.compile(wrong, options);
        }
    }

    /**
     * {@code Q_TINY} without ANSI, in both styles: every row has a result
     * that the wrong candidate changes. (With ANSI, an overflow in the
     * multiplication throws in the same row with and without the defect.)
     */
    private static List<UnitCase> tinyUnits() {
        return Workloads.smoke().stream()
                .filter(u -> u.key().workload().startsWith("Q_TINY:ROWS") && !u.config().ansi()
                        && (u.config().style() == io.github.janinolts.qsim.codegen.Style.WHOLE_STAGE
                                || u.config().style() == io.github.janinolts.qsim.codegen.Style.EXPRESSION))
                .toList();
    }

    private static DifferentialRunner runner(CompilerBackend candidate) {
        List<CompilerBackend> all = TestBackends.all();
        return new DifferentialRunner(new DifferentialRunner.Backends(all.get(0), all.get(1), candidate),
                DifferentialTest.class.getClassLoader());
    }

    @Test
    void aWrongCandidateIsDetectedAsWrong() {
        CorrectnessRun run = CorrectnessRun.run("test", runner(new WrongBackend()), Ledger.parse("3.1.x", ""),
                tinyUnits());

        assertFalse(run.passed());
        List<Difference> differences = run.differences();
        assertFalse(differences.isEmpty());
        for (Difference d : differences) {
            assertTrue(d.pair() == Pair.J3112_JLTS || d.pair() == Pair.JAVAC_JLTS, d::toString);
            assertEquals(Kind.WRONG, d.kind(), d::toString);
            assertEquals(Assessment.REGRESSION, d.assessment(), d::toString);
        }
        // Both pairs with the candidate, in each of the two units.
        assertEquals(4, differences.size(), () -> differences.toString());
        assertEquals(differences, run.reconciliation().unexplained());
    }

    @Test
    void aLedgerEntryExplainsTheDifference() {
        Ledger ledger = Ledger.parse("3.1.x", "Q_TINY:ROWS/*\tJ3112:JLTS\tWRONG\t#0\ttest\n"
                + "Q_TINY:ROWS/*\tJAVAC:JLTS\tWRONG\tS-04\ttest\n");
        CorrectnessRun run = CorrectnessRun.run("test", runner(new WrongBackend()), ledger, tinyUnits());

        assertTrue(run.passed(), () -> run.reconciliation().toString());
        assertEquals(4, run.reconciliation().explained().size());
        assertEquals(List.of(2, 2), List.copyOf(run.reconciliation().usedEntries().values()));

        // An issue number refers to janino-lts, not to the repository that runs qsim.
        Ledger.Entry issue = ledger.entries().get(0);
        assertEquals("janino-lts/janino#0", issue.reference());
        assertEquals("[janino-lts/janino#0](https://github.com/janino-lts/janino/issues/0)",
                issue.markdownReference());
        assertEquals("S-04", ledger.entries().get(1).reference());
        String report = CorrectnessReport.markdown(run);
        assertTrue(report.contains("- 2 differences: `Q_TINY:ROWS/*` J3112:JLTS WRONG [janino-lts/janino#0]"),
                report);
        assertTrue(report.contains(" (ledger: `S-04`, line 2)"), report);
    }

    @Test
    void anOrphanedLedgerEntryFails() {
        Ledger ledger = Ledger.parse("3.1.x", "Q_TINY:ROWS/EXPRESSION/*\tJ3112:JLTS\tBYTES\t#0\tnot there\n"
                + "Q_NOT_RUN/*\tJ3112:JLTS\tWRONG\t#0\tunit not run\n");
        // The baseline as the candidate: no differences, whatever the candidate of the build is.
        CorrectnessRun run = CorrectnessRun.run("test", runner(TestBackends.all().get(1)), ledger, tinyUnits());

        assertTrue(run.reconciliation().unexplained().isEmpty(), () -> run.reconciliation().toString());
        assertEquals(1, run.reconciliation().orphans().size(), () -> run.reconciliation().orphans().toString());
        assertEquals(1, run.reconciliation().orphans().get(0).lineNumber());
        assertFalse(run.passed());
    }

    @Test
    void reportsAreWritten() throws Exception {
        CorrectnessRun run = CorrectnessRun.run("test", runner(new WrongBackend()), Ledger.parse("3.1.x", ""),
                tinyUnits());
        Path dir = Files.createTempDirectory("qsim-report");
        CorrectnessReport.write(run, dir);

        List<String> units = Files.readAllLines(dir.resolve(CorrectnessReport.UNITS_FILE));
        assertEquals(2 * 4, units.size());
        assertTrue(units.get(0).contains("\"backend\":\"INTERP\""), units.get(0));
        String report = Files.readString(dir.resolve(CorrectnessReport.REPORT_FILE));
        assertTrue(report.contains("Result: **FAILED**"), report);
        assertTrue(report.contains("| J3112:JLTS | WRONG | 0 | 2 |"), report);
    }
}
