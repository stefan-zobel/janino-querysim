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
import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.codegen.CodegenConfig;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.codegen.Generator;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.plan.DataGenerator;
import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.ExpressionGenerator;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Schema;
import io.github.janinolts.qsim.plan.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Random expressions of every kind, generated and run in both styles and
 * both modes: javac must give the result of the interpreter, otherwise the
 * generator or the interpreter of qsim is wrong. The Janino backends must give
 * the same result, too, as long as the profile STRICT avoids every registered
 * deviation.
 */
class RandomExpressionTest {

    private static final Schema SCHEMA = Schema.of(DataType.values());
    private static final int SEEDS = 40;

    static PlanNode randomPlan(long seed) {
        ExpressionGenerator g = new ExpressionGenerator(new SplittableRandom(seed), SCHEMA);
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            DataType[] types = DataType.values();
            columns.add(new PlanNode.NamedExpression("x" + i,
                    g.generate(types[(int) ((seed + i) % types.length)], 4)));
        }
        PlanNode scan = new PlanNode.Scan("t", SCHEMA, seed % 2 == 0 ? PlanNode.Layout.ROWS : PlanNode.Layout.COLUMNS);
        return new PlanNode.Project(new PlanNode.Filter(scan, g.generate(DataType.BOOLEAN, 3)), columns);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"WHOLE_STAGE", "EXPRESSION"})
    void backendsGiveTheResultOfTheInterpreter(String styleName) {
        Style style = Style.valueOf(styleName);
        List<String> failures = new ArrayList<>();
        int withRows = 0;
        int throwing = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            PlanNode plan = randomPlan(seed);
            Map<String, Table> tables = Map.of("t", DataGenerator.withSeed(seed).generate(SCHEMA, 128));
            for (boolean ansi : new boolean[] {false, true}) {
                RunResult expected = QueryRunner.interpret(plan, ansi, tables);
                withRows += expected.rows().isEmpty() ? 0 : 1;
                throwing += expected.status() == RunResult.Status.THROWS ? 1 : 0;
                GeneratedQuery query = Generator.generate(plan, CodegenConfig.of(style, ansi, seed));
                for (CompilerBackend backend : TestBackends.all()) {
                    RunResult actual = QueryRunner.run(query, backend, TestBackends.options(), tables);
                    if (!expected.sameAs(actual)) {
                        failures.add("seed " + seed + " ansi=" + ansi + " " + backend.id() + ": expected "
                                + expected + ", got " + actual);
                        if (backend.id().equals(JavacBackend.ID)) {
                            failures.add(UnitRenderer.compilationUnit(query.unit()));
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
        // The comparison is only worth something if both outcomes occur often enough.
        int runs = 2 * SEEDS;
        int rows = withRows;
        int threw = throwing;
        assertTrue(rows >= runs / 4 && threw >= runs / 10,
                () -> "Too little coverage: " + rows + " runs with rows, " + threw + " throwing, of " + runs);
    }
}
