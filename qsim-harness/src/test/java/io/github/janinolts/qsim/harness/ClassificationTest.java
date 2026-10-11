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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.harness.Difference.Kind;
import io.github.janinolts.qsim.harness.RunResult.Status;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class ClassificationTest {

    private static final RunResult OK_A = RunResult.ok(List.<Object[]>of(new Object[] {1}));
    private static final RunResult OK_B = RunResult.ok(List.<Object[]>of(new Object[] {2}));
    private static final RunResult THROWS = RunResult.threw(new ArithmeticException(), "t", 3);
    private static final RunResult REJECTED = RunResult.rejected("Unknown variable \"x\"", 2, 5);
    private static final RunResult INVALID = RunResult.failed(Status.INVALID_CLASS, "VerifyError");
    private static final RunResult INTERNAL = RunResult.failed(Status.INTERNAL_ERROR, "ICE");
    private static final RunResult LIMIT = RunResult.failed(Status.LIMIT, "code too large");
    private static final RunResult UNSUPPORTED = RunResult.failed(Status.UNSUPPORTED, "no compliance mode");

    @Test
    void kinds() {
        assertNull(DifferentialRunner.classify(OK_A, OK_A, true));
        assertEquals(Kind.WRONG, DifferentialRunner.classify(OK_A, OK_B, true));
        assertEquals(Kind.WRONG, DifferentialRunner.classify(OK_A, THROWS, true));
        assertEquals(Kind.WRONG, DifferentialRunner.classify(THROWS, RunResult.threw(new ArithmeticException(), "t", 4),
                true));
        assertEquals(Kind.REJECTED, DifferentialRunner.classify(OK_A, REJECTED, true));
        assertEquals(Kind.REJECTED, DifferentialRunner.classify(LIMIT, THROWS, true));
        assertEquals(Kind.INVALID, DifferentialRunner.classify(INVALID, OK_A, true));
        assertEquals(Kind.INVALID, DifferentialRunner.classify(THROWS, INTERNAL, true));
        assertEquals(Kind.INVALID, DifferentialRunner.classify(REJECTED, INVALID, true));
        assertEquals(Kind.REJECTED, DifferentialRunner.classify(REJECTED, LIMIT, true));
        assertNull(DifferentialRunner.classify(OK_A, UNSUPPORTED, true));
        assertNull(DifferentialRunner.classify(LIMIT, LIMIT, true));
    }

    @Test
    void locationsOfCompileErrors() {
        RunResult explained = RunResult.rejected("Unknown variable \"x\"; did you mean \"y\"?", 2, 5);
        RunResult elsewhere = RunResult.rejected("Unknown variable \"x\"", 3, 5);
        RunResult otherMessage = RunResult.rejected("Something else", 2, 5);
        assertNull(DifferentialRunner.classify(REJECTED, explained, true));
        assertEquals(Kind.LOCATION, DifferentialRunner.classify(REJECTED, elsewhere, true));
        assertEquals(Kind.LOCATION, DifferentialRunner.classify(REJECTED, otherMessage, true));
        // javac and the candidate compile different renderings of a unit: no location comparison.
        assertNull(DifferentialRunner.classify(REJECTED, elsewhere, false));
    }

    @Test
    void equivalence() {
        assertTrue(DifferentialRunner.equivalent(REJECTED, RunResult.rejected("other", 9, 9)));
        assertFalse(DifferentialRunner.equivalent(OK_A, OK_B));
        assertFalse(DifferentialRunner.equivalent(REJECTED, LIMIT));
    }

    @Test
    void limits() {
        assertTrue(Limits.isLimit(CompileResult.internalError(new RuntimeException("Code grows beyond 64 KB"))));
        assertTrue(Limits.isLimit(CompileResult.internalError(new RuntimeException("Compiling \"Baz\"",
                new RuntimeException("Compiling \"bind(DataContext)\"",
                        new RuntimeException("Code grows beyond 64 KB"))))));
        assertTrue(Limits.isLimit(CompileResult.rejected("code too large", 3, 4, null)));
        assertTrue(Limits.isLimit(CompileResult.internalError(new IllegalStateException(new StackOverflowError()))));
        assertFalse(Limits.isLimit(CompileResult.rejected("incompatible types", 3, 4, null)));
        assertFalse(Limits.isLimit(CompileResult.ok(Object.class, null, Map.of())));
    }

    @Test
    void ledgerSyntax() {
        Ledger ledger = Ledger.parse("3.1.x", "# comment\n\nQ_*/WHOLE_STAGE/*\tJ3112:JLTS\tBYTES\t#2\tfinally code\n");
        assertEquals(1, ledger.entries().size());
        Ledger.Entry e = ledger.entries().get(0);
        assertEquals(3, e.lineNumber());
        assertTrue(e.matches(new UnitKey("Q_ANSI:S:ROWS", "WHOLE_STAGE", "STRICT", "abc")));
        assertFalse(e.matches(new UnitKey("Q_ANSI:S:ROWS", "EXPRESSION", "STRICT", "abc")));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.parse("3.1.x", "x\tINTERP:JAVAC\tWRONG\t#1\tqsim defect\n"));
        assertThrows(IllegalArgumentException.class, () -> Ledger.parse("3.1.x", "x\tJ3112:JLTS\tWRONG\t\tno id\n"));
        assertThrows(IllegalArgumentException.class, () -> Ledger.parse("3.1.x", "x\tJ3112:JLTS\tWRONG\n"));
        assertEquals("3.1.x", Ledger.lineOf("3.1.19"));
        assertEquals("master", Ledger.lineOf("3.2.0-SNAPSHOT"));
        // An entry with feature tags applies only to units with them.
        Ledger tagged = Ledger.parse("3.1.x", "seed:*/WHOLE_STAGE/*\tJ3112:JLTS\tBYTES\t#54\tnote\tloop-flag\n");
        Ledger.Entry t = tagged.entries().get(0);
        assertEquals(List.of("loop-flag"), t.tags());
        UnitKey seed = new UnitKey("seed:7", "WHOLE_STAGE", "STRICT", "abc");
        assertTrue(t.matches(seed, List.of("null-pair", "loop-flag")));
        assertFalse(t.matches(seed, List.of("null-pair")));
        Difference bytes = new Difference(seed, Difference.Pair.J3112_JLTS, Kind.BYTES,
                Difference.Assessment.CHANGED_CODE, "L2");
        assertTrue(tagged.reconcile(Map.of(seed, List.of("loop-flag")), List.of(bytes)).passed());
        assertFalse(tagged.reconcile(Map.of(seed, List.of()), List.of(bytes)).passed());
        // A unit without the tag does not orphan the entry.
        assertTrue(tagged.reconcile(Map.of(seed, List.of()), List.of()).passed());
        assertFalse(tagged.reconcile(Map.of(seed, List.of("loop-flag")), List.of()).passed());
        // The ledgers of qsim parse.
        assertEquals("3.1.x", Ledger.forLine("3.1.x").line());
        assertEquals("master", Ledger.forLine("master").line());
    }

    @Test
    void ledgerEntriesOfFixedDifferences() {
        Ledger ledger = Ledger.parse("3.1.x", "seed:*\tJAVAC:JLTS\tREJECTED\t#138\tfixed\t\t3.1.20\n"
                + "seed:*\tJ3112:JLTS\tREJECTED\t#138\tnot fixed\tscript-generic-array\t\n");
        Ledger.Entry fixed = ledger.entries().get(0);
        assertEquals("3.1.20", fixed.fixed());
        assertEquals(List.of(), fixed.tags());
        assertNull(ledger.entries().get(1).fixed());
        assertTrue(fixed.appliesTo("3.1.19"));
        assertFalse(fixed.appliesTo("3.1.20"));
        // A snapshot of the version contains the fix.
        assertFalse(fixed.appliesTo("3.1.20-SNAPSHOT"));
        assertFalse(fixed.appliesTo("3.2.0-SNAPSHOT"));
        assertEquals(2, ledger.applicableTo("3.1.19").entries().size());
        assertEquals(List.of("not fixed"),
                ledger.applicableTo("3.1.20").entries().stream().map(Ledger.Entry::note).toList());
        assertTrue(fixed.toString().endsWith("fixed in 3.1.20"));
        assertTrue(fixed.markdown().contains("(fixed in 3.1.20)"));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.parse("3.1.x", "x\tJ3112:JLTS\tWRONG\t#1\tnote\t\tsoon\n"));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.parse("3.1.x", "x\tJ3112:JLTS\tWRONG\t#1\tnote\t\t3.1.20\textra\n"));
        // The entry of #138 for the pair JAVAC:JLTS applies to 3.1.19, not to the snapshots with the fix.
        Predicate<Ledger> hasJavacEntry = l -> l.entries().stream()
                .anyMatch(e -> e.id().equals("#138") && e.pair() == Difference.Pair.JAVAC_JLTS);
        assertTrue(hasJavacEntry.test(Ledger.forVersion("3.1.19")));
        assertFalse(hasJavacEntry.test(Ledger.forVersion("3.1.20-SNAPSHOT")));
        assertFalse(hasJavacEntry.test(Ledger.forVersion("3.2.0-SNAPSHOT")));
        assertEquals("master", Ledger.forVersion("3.2.0-SNAPSHOT").line());
    }
}
