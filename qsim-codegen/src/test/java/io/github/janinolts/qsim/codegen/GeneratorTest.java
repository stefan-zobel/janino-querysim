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

package io.github.janinolts.qsim.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.UnitKind;
import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.ExpressionGenerator;
import io.github.janinolts.qsim.plan.Expressions;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GeneratorTest {

    private static PlanNode randomPlan(long seed) {
        Schema schema = Schema.of(DataType.values());
        SplittableRandom random = new SplittableRandom(seed);
        ExpressionGenerator g = new ExpressionGenerator(random, schema);
        PlanNode scan = new PlanNode.Scan("t", schema, PlanNode.Layout.ROWS);
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (DataType t : DataType.values()) {
            columns.add(new PlanNode.NamedExpression("x", g.generate(t, 4)));
        }
        return new PlanNode.Project(new PlanNode.Filter(scan, g.generate(DataType.BOOLEAN, 3)), columns);
    }

    @ParameterizedTest
    @EnumSource(value = Style.class, names = {"WHOLE_STAGE", "EXPRESSION"})
    void sameSeedAndConfigurationGiveTheSameSource(Style style) {
        for (long seed = 1; seed <= 20; seed++) {
            for (boolean ansi : new boolean[] {false, true}) {
                CodegenConfig config = CodegenConfig.of(style, ansi, seed);
                String a = UnitRenderer.compilationUnit(Generator.generate(randomPlan(seed), config).unit());
                String b = UnitRenderer.compilationUnit(Generator.generate(randomPlan(seed), config).unit());
                assertEquals(a, b);
            }
        }
    }

    @Test
    void ansiChangesTheCodeShape() {
        Schema schema = Schema.of(DataType.STRING, DataType.INT);
        PlanNode plan = new PlanNode.Project(new PlanNode.Scan("t", schema, PlanNode.Layout.ROWS), List.of(
                new PlanNode.NamedExpression("i", Expressions.cast(Expressions.col(0, DataType.STRING), DataType.INT)),
                new PlanNode.NamedExpression("j", Expressions.add(Expressions.col(1, DataType.INT),
                        Expressions.lit(1)))));
        String plain = Generator.generate(plan, CodegenConfig.of(Style.WHOLE_STAGE, false, 1)).unit().body();
        String ansi = Generator.generate(plan, CodegenConfig.of(Style.WHOLE_STAGE, true, 1)).unit().body();
        assertNotEquals(plain, ansi);
        assertTrue(plain.contains("catch (RuntimeException"), "non-ANSI parses inside try/catch");
        assertFalse(ansi.contains("catch (RuntimeException"));
        assertTrue(ansi.contains("Math.addExact("));
        assertFalse(plain.contains("Math.addExact("));
    }

    @Test
    void wholeStageShape() {
        GeneratedQuery q = Generator.generate(Scenario.filterProject(10, PlanNode.Layout.COLUMNS).plan(),
                CodegenConfig.of(Style.WHOLE_STAGE, false, 1));
        assertEquals(ApiPath.CLASS_BODY, q.apiPath());
        assertInstanceOf(GeneratedQuery.Driver.WholeStage.class, q.driver());
        String body = q.unit().body();
        assertTrue(body.contains("final class GeneratedIterator extends BufferedRowIterator"));
        assertTrue(body.contains("} while (false);"));
        assertTrue(body.matches("(?s).*scan0_values_0\\[row_\\d+\\].*"), body);
        assertTrue(q.unit().metadata().featureTags().containsAll(List.of("null-pair", "do-while-false", "inner")),
                q.unit().metadata().featureTags()::toString);
    }

    @Test
    void tinyBecomesAnExpressionWithoutAnsi() {
        PlanNode plan = Scenario.tiny(PlanNode.Layout.ROWS).plan();
        GeneratedQuery q = Generator.generate(plan, CodegenConfig.of(Style.EXPRESSION, false, 1));
        assertEquals(UnitKind.EXPRESSION, q.unit().kind());
        assertEquals("((p0 * 3) + p1)", q.unit().body());
        assertEquals(new GeneratedQuery.Driver.PerRow(List.of(0, 1), true), q.driver());

        GeneratedQuery ansi = Generator.generate(plan, CodegenConfig.of(Style.EXPRESSION, true, 1));
        assertEquals(UnitKind.SCRIPT, ansi.unit().kind());
    }

    @Test
    void stringLiteralsAreAscii() {
        assertEquals("\"a\\\"b\\\\c\\012\\u00df\\ud834\\udd1e\"",
                JavaTypes.stringLiteral("a\"b\\c\n\u00DF\uD834\uDD1E"));
    }
}
