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
import io.github.janinolts.qsim.codegen.CodegenConfig;
import io.github.janinolts.qsim.codegen.NegativePrograms;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.harness.Difference.Assessment;
import io.github.janinolts.qsim.harness.Difference.Pair;
import io.github.janinolts.qsim.plan.DataGenerator;
import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.Expressions;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.LanguageFeature.Behavior;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Schema;
import io.github.janinolts.qsim.plan.Table;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The catalog of language features against the real backends: in the
 * whole-stage style, javac, the baseline and the candidate behave as the
 * register and the change log say, for the versions of the build; and the
 * expectation follows the version of the candidate.
 */
class ProfileTest {

    private static final Schema SCHEMA = Schema.of(DataType.INT, DataType.BYTE, DataType.BOOLEAN, DataType.STRING);

    private static final Map<String, Table> TABLES = Map.of("t", DataGenerator.withSeed(5).generate(SCHEMA, 64));

    static UnitCase unit(LanguageFeature f, Style style) {
        DataType type = f.operandType() == null ? DataType.INT : f.operandType();
        int column = switch (type) {
            case BYTE -> 1;
            case BOOLEAN -> 2;
            case STRING -> 3;
            default -> 0;
        };
        Expression operand = Expressions.col(column, type);
        PlanNode plan = new PlanNode.Project(new PlanNode.Scan("t", SCHEMA, PlanNode.Layout.ROWS), List.of(
                new PlanNode.NamedExpression("f", new Expression.Feature(f, operand)),
                new PlanNode.NamedExpression("x", Expressions.col(0, DataType.INT))));
        CodegenConfig config = CodegenConfig.of(style, false, 5).withProfile(Profile.valueOf(f.profile()));
        return UnitCase.of("feature", plan, TABLES, config);
    }

    private static DifferentialRunner runner(CompilerBackend candidate) {
        List<CompilerBackend> all = TestBackends.all();
        return new DifferentialRunner(new DifferentialRunner.Backends(all.get(0), all.get(1), candidate),
                ProfileTest.class.getClassLoader());
    }

    @ParameterizedTest
    @EnumSource(LanguageFeature.class)
    void theBackendsBehaveAsRegistered(LanguageFeature f) {
        DifferentialRunner runner = runner(TestBackends.all().get(2));
        UnitCase unit = unit(f, Style.WHOLE_STAGE);
        UnitOutcome o = runner.run(unit);
        assertTrue(o.differences().stream().noneMatch(d -> d.kind() != Difference.Kind.BYTES),
                () -> o.differences().toString());
        assertEquals("WHOLE_STAGE", o.key().style());
        assertEquals(f.profile() + ":" + f.id(), o.key().profile());
        Expectation e = runner.expectation(unit);
        boolean differs = e.javac() != e.candidate() || e.baseline() != e.candidate()
                || (e.candidate() == Behavior.JANINO);
        // A feature that only changes the acceptance or the result shows it in at least one pair.
        if (differs) {
            assertFalse(o.expected().isEmpty(), () -> f + ": " + e + " but no expected difference");
        }
        assertTrue(o.expected().stream().allMatch(d -> d.assessment() == Assessment.EXPECTED
                && d.detail().startsWith(f.id() + ":")));
    }

    /** A candidate that reports another version than it has: the expectation follows the reported one. */
    private record Versioned(CompilerBackend delegate, String version) implements CompilerBackend {

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public CompileResult compile(GeneratedUnit unit, CompileOptions options) {
            return delegate.compile(unit, options);
        }
    }

    @Test
    void theExpectationFollowsTheVersionOfTheCandidate() {
        CompilerBackend candidate = TestBackends.all().get(2);
        UnitCase unit = unit(LanguageFeature.MULTI_CATCH, Style.WHOLE_STAGE);

        Expectation old = Expectation.of(unit, "3.1.12", "3.1.15");
        assertEquals(Behavior.REJECT, old.candidate());
        assertEquals(Behavior.JAVAC, Expectation.of(unit, "3.1.12", "3.1.16").candidate());
        assertEquals(Behavior.REJECT, Expectation.of(unit, "3.1.12", "3.1.16").baseline());
        assertEquals(Behavior.JAVAC, Expectation.of(unit(LanguageFeature.VARARGS_ARRAY_ELEMENT, Style.WHOLE_STAGE),
                "3.1.12", "3.1.20-SNAPSHOT").candidate());
        assertTrue(Expectation.of(unit(LanguageFeature.NULL_BOOLEAN_OR, Style.WHOLE_STAGE), "3.1.12", "3.1.19")
                .needsJaninoSemantics());
        assertTrue(Expectation.STRICT.isStrict());

        // The candidate of the build (3.1.16 or later) compiles multi-catch; reported as 3.1.15, it is not expected
        // to. The baseline behaves as expected, so the difference belongs to the pair of baseline and candidate.
        UnitOutcome o = runner(new Versioned(candidate, "3.1.15")).run(unit);
        assertEquals(1, o.differences().size(), o::toString);
        Difference d = o.differences().get(0);
        assertEquals(Assessment.NOT_AS_EXPECTED, d.assessment());
        assertEquals(Pair.J3112_JLTS, d.pair());
        assertEquals(Difference.Kind.REJECTED, d.kind());
    }

    @Test
    void aNegativeProgramIsRejectedByAll() {
        UnitCase unit = UnitCase.of("seed:1:NEG:TYPE_MISMATCH", unit(LanguageFeature.MULTI_CATCH,
                Style.WHOLE_STAGE).plan(), TABLES, CodegenConfig.of(Style.WHOLE_STAGE, false, 1)
                .withProfile(Profile.MODERN), NegativePrograms.Mutation.TYPE_MISMATCH);
        DifferentialRunner runner = runner(TestBackends.all().get(2));
        assertEquals("negative:TYPE_MISMATCH", runner.expectation(unit).reason());
        UnitOutcome o = runner.run(unit);
        for (String id : List.of("JAVAC", "J3112", "JLTS")) {
            assertEquals(RunResult.Status.REJECTED, o.result(id).status(), id);
        }
        assertTrue(o.differences().isEmpty(), () -> o.differences().toString());
        assertEquals(List.of(Pair.INTERP_JAVAC), o.expected().stream().map(Difference::pair).toList());
    }
}
