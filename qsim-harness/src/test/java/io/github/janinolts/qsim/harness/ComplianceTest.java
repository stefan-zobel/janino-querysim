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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.harness.Difference.Assessment;
import io.github.janinolts.qsim.harness.Difference.Kind;
import io.github.janinolts.qsim.harness.Difference.Pair;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.LanguageFeature.Behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The candidate in its compliance mode ({@code JLTS-C}): its expectation
 * from the catalog, the pair {@code JAVAC:JLTS-C}, the ledger and the guard
 * against a candidate without the mode. The build has no Janino of the
 * development line, so stand-ins play {@code JLTS-C}: javac, which behaves
 * like a compliance mode that corrects everything, and the candidate of the
 * build, which behaves like one that corrects nothing.
 */
class ComplianceTest {

    private static final String DEVELOPMENT = "3.2.0-SNAPSHOT";

    /** A stand-in for the candidate in its compliance mode: another backend under the ID {@code JLTS-C}. */
    private record StandIn(CompilerBackend delegate) implements CompilerBackend {

        @Override
        public String id() {
            return ComplianceBackend.ID;
        }

        @Override
        public String version() {
            return DEVELOPMENT;
        }

        @Override
        public CompileResult compile(GeneratedUnit unit, CompileOptions options) {
            return delegate.compile(unit, options);
        }
    }

    /** A backend that reports another version and records the options it is given. */
    private record Recording(CompilerBackend delegate, String version, List<CompileOptions> options)
            implements CompilerBackend {

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public CompileResult compile(GeneratedUnit unit, CompileOptions o) {
            options.add(o);
            return delegate.compile(unit, o);
        }
    }

    private static DifferentialRunner runner(CompilerBackend compliance) {
        List<CompilerBackend> all = TestBackends.all();
        // The candidate reports the version of the development line, so that the expectations follow it.
        CompilerBackend candidate = new Recording(all.get(2), DEVELOPMENT, new ArrayList<>());
        return new DifferentialRunner(new DifferentialRunner.Backends(all.get(0), all.get(1), candidate, compliance),
                ComplianceTest.class.getClassLoader());
    }

    @Test
    void theCatalogSaysWhatTheComplianceModeCorrects() {
        for (LanguageFeature f : List.of(LanguageFeature.NULL_BOOLEAN_OR, LanguageFeature.VARARGS_BEFORE_BOXING,
                LanguageFeature.CONDITIONAL_BOXED_TYPE, LanguageFeature.CONSTANT_CONDITIONAL,
                LanguageFeature.VARARGS_ARRAY)) {
            assertEquals(Behavior.JAVAC, f.complianceBehavior(DEVELOPMENT), f::toString);
            assertEquals(Behavior.JAVAC, f.complianceBehavior("3.2.0"), f::toString);
            assertEquals(Behavior.JANINO, f.janinoBehavior(DEVELOPMENT), f::toString);
        }
        // Open in the compliance mode: like the default mode.
        assertNull(LanguageFeature.FINAL_LOCAL_ASSIGNMENT.compliantSince());
        assertEquals(Behavior.JAVAC, LanguageFeature.FINAL_LOCAL_ASSIGNMENT.complianceBehavior(DEVELOPMENT));
        assertEquals(Behavior.REJECT, LanguageFeature.CONSTANT_SHIFT.complianceBehavior(DEVELOPMENT));
        assertEquals(Behavior.JAVAC, LanguageFeature.VARARGS_ARRAY_ELEMENT.complianceBehavior(DEVELOPMENT));

        Expectation e = Expectation.of(ProfileTest.unit(LanguageFeature.NULL_BOOLEAN_OR, Style.WHOLE_STAGE), "3.1.12",
                DEVELOPMENT);
        assertEquals(Behavior.JANINO, e.candidate());
        assertEquals(Behavior.JAVAC, e.compliance());
        assertEquals(Behavior.JAVAC, Expectation.STRICT.compliance());
    }

    @Test
    void aComplianceModeLikeJavacShowsNoDifference() {
        DifferentialRunner runner = runner(new StandIn(TestBackends.all().get(0)));
        List<UnitCase> units = new ArrayList<>(Workloads.smoke().subList(0, 3));
        units.add(ProfileTest.unit(LanguageFeature.NULL_BOOLEAN_OR, Style.WHOLE_STAGE));
        units.add(ProfileTest.unit(LanguageFeature.VARARGS_ARRAY, Style.WHOLE_STAGE));
        for (UnitCase unit : units) {
            UnitOutcome o = runner.run(unit);
            assertTrue(o.runs().stream().anyMatch(r -> r.id().equals(ComplianceBackend.ID)), o::toString);
            assertTrue(o.differences().stream().noneMatch(d -> d.pair() == Pair.JAVAC_JLTSC), o::toString);
            assertTrue(o.expected().stream().noneMatch(d -> d.pair() == Pair.JAVAC_JLTSC), o::toString);
        }
    }

    @Test
    void aComplianceModeThatCorrectsNothingIsNotAsExpected() {
        DifferentialRunner runner = runner(new StandIn(TestBackends.all().get(2)));

        UnitOutcome o = runner.run(ProfileTest.unit(LanguageFeature.NULL_BOOLEAN_OR, Style.WHOLE_STAGE));
        List<Difference> compliance = o.differences().stream().filter(d -> d.pair() == Pair.JAVAC_JLTSC).toList();
        assertEquals(1, compliance.size(), o::toString);
        assertEquals(Assessment.NOT_AS_EXPECTED, compliance.get(0).assessment());
        assertEquals(Kind.WRONG, compliance.get(0).kind());

        // L-04 is open in the compliance mode: accepted like by the default mode, while javac rejects it.
        o = runner.run(ProfileTest.unit(LanguageFeature.FINAL_LOCAL_ASSIGNMENT, Style.WHOLE_STAGE));
        assertTrue(o.differences().stream().noneMatch(d -> d.pair() == Pair.JAVAC_JLTSC), o::toString);
        assertTrue(o.expected().stream().anyMatch(d -> d.pair() == Pair.JAVAC_JLTSC && d.kind() == Kind.REJECTED),
                o::toString);
    }

    @Test
    void theBackendSetsTheOptionAndNeedsTheDevelopmentLine() {
        CompilerBackend candidate = TestBackends.all().get(2);
        assertThrows(IllegalArgumentException.class, () -> new ComplianceBackend(candidate));

        List<CompileOptions> options = new ArrayList<>();
        ComplianceBackend backend = new ComplianceBackend(new Recording(candidate, DEVELOPMENT, options));
        assertEquals(ComplianceBackend.ID, backend.id());
        assertEquals(DEVELOPMENT, backend.version());
        backend.compile(HandWrittenUnits.wholeStage(),
                CompileOptions.of(ApiPath.SIMPLE_COMPILER, ComplianceTest.class.getClassLoader()));
        assertTrue(options.get(0).compliance());
    }

    @Test
    void theLedgerKeepsTheEntriesOfTheComplianceModeOnlyForRunsWithIt() {
        Ledger ledger = Ledger.parse(Ledger.LINE_MASTER, "*\tJAVAC:JLTS-C\tWRONG\tS-14\tnot corrected yet\n"
                + "*\tJAVAC:JLTS\tWRONG\tS-14\tnot corrected\n");
        assertEquals(Pair.JAVAC_JLTSC, ledger.entries().get(0).pair());
        assertEquals(List.of(ledger.entries().get(1)), ledger.withoutCompliance().entries());

        UnitKey key = Workloads.smoke().get(0).key();
        Difference d = new Difference(key, Pair.JAVAC_JLTS, Kind.WRONG, Assessment.DEVIATION, "test");
        assertTrue(ledger.withoutCompliance().reconcile(List.of(key), List.of(d)).passed());
        Ledger.Reconciliation withCompliance = ledger.reconcile(Map.of(key, List.of()), List.of(d));
        assertEquals(List.of(ledger.entries().get(0)), withCompliance.orphans());

        assertTrue(Ledger.forVersion(DEVELOPMENT, false).entries().stream()
                .noneMatch(e -> e.pair() == Pair.JAVAC_JLTSC));
    }
}
