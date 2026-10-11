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

package io.github.janinolts.qsim.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * Random plans: deterministic, within the settings, of every operator, and
 * executable by the interpreter; language features placed into them.
 */
class RandomPlansTest {

    @Test
    void aSeedDeterminesThePlanAndTheData() {
        RandomPlans g = RandomPlans.withDefaults();
        for (long seed = 1; seed <= 50; seed++) {
            RandomPlans.RandomPlan a = g.generate(seed);
            RandomPlans.RandomPlan b = g.generate(seed);
            assertEquals(a.plan(), b.plan());
            Map<String, Table> da = a.data(16);
            Map<String, Table> db = b.data(16);
            assertEquals(da.keySet(), db.keySet());
            for (String t : da.keySet()) {
                assertEquals(da.get(t).rows().size(), db.get(t).rows().size());
                for (int r = 0; r < da.get(t).size(); r++) {
                    assertTrue(Arrays.equals(da.get(t).rows().get(r), db.get(t).rows().get(r)));
                }
            }
        }
    }

    @Test
    void plansStayWithinTheSettingsAndUseEveryOperator() {
        RandomPlans g = RandomPlans.withDefaults();
        Set<Class<?>> operators = new HashSet<>();
        int pipelines = 0;
        for (long seed = 1; seed <= 400; seed++) {
            PlanNode plan = g.generate(seed).plan();
            List<PlanNode> nodes = Plans.nodes(plan);
            long joins = nodes.stream().filter(n -> n instanceof PlanNode.HashJoin).count();
            assertTrue(joins <= RandomPlans.Settings.DEFAULT.maxJoins(), () -> plan.toString());
            nodes.forEach(n -> operators.add(n.getClass()));
            if (nodes.stream().allMatch(n -> n instanceof PlanNode.Scan || n instanceof PlanNode.Filter
                    || n instanceof PlanNode.Project)) {
                pipelines++;
            }
            // Every plan runs: rows or an exception of a row, never a failure of the interpreter.
            try {
                new Interpreter(seed % 2 == 0).execute(plan, g.generate(seed).data(32));
            } catch (RowException e) {
                // What casts and arithmetic throw with ANSI; anything else would be a defect of the interpreter.
                assertTrue(e.getCause() instanceof ArithmeticException || e.getCause() instanceof IllegalArgumentException,
                        e::toString);
            }
        }
        assertEquals(Set.of(PlanNode.Scan.class, PlanNode.Filter.class, PlanNode.Project.class,
                PlanNode.HashAggregate.class, PlanNode.HashJoin.class, PlanNode.Sort.class, PlanNode.Limit.class,
                PlanNode.Union.class), operators);
        assertTrue(pipelines > 40, "pipelines: " + pipelines);
    }

    @Test
    void theWeightsDecide() {
        Map<RandomPlans.Operator, Integer> onlyFilters = new EnumMap<>(RandomPlans.Operator.class);
        onlyFilters.put(RandomPlans.Operator.FILTER, 1);
        RandomPlans g = new RandomPlans(new RandomPlans.Settings(4, 2, 3, 0, onlyFilters,
                ExpressionGenerator.Weights.DEFAULT, 1000));
        for (long seed = 1; seed <= 20; seed++) {
            List<PlanNode> nodes = Plans.nodes(g.generate(seed).plan());
            assertEquals(4, nodes.size());
            assertTrue(nodes.stream().allMatch(n -> n instanceof PlanNode.Scan || n instanceof PlanNode.Filter));
        }
        assertThrows(IllegalArgumentException.class, () -> new RandomPlans.Settings(0, 2, 3, 0, onlyFilters,
                ExpressionGenerator.Weights.DEFAULT, 4));
    }

    @Test
    void aFeatureIsPlacedIntoThePlan() {
        RandomPlans g = RandomPlans.withDefaults();
        for (LanguageFeature f : LanguageFeature.values()) {
            for (long seed = 1; seed <= 20; seed++) {
                PlanNode plan = g.generate(seed).plan();
                PlanNode withFeature = RandomPlans.withFeature(plan, f, new SplittableRandom(seed),
                        ExpressionGenerator.Weights.DEFAULT);
                assertEquals(List.of(f), Plans.features(withFeature));
                if (f.operandType() == null) {
                    // A feature that passes its operand through keeps the schema.
                    assertEquals(plan.schema().fields().stream().map(Schema.Field::type).toList(),
                            withFeature.schema().fields().stream().map(Schema.Field::type).toList());
                }
            }
        }
    }

    @Test
    void featuresCompareVersionsAndCompute() {
        assertTrue(LanguageFeature.compareVersions("3.1.12", "3.1.16") < 0);
        assertEquals(0, LanguageFeature.compareVersions("3.1.20-SNAPSHOT", "3.1.20"));
        assertTrue(LanguageFeature.compareVersions("3.2.0-SNAPSHOT", "3.1.20") > 0);
        assertThrows(IllegalArgumentException.class, () -> LanguageFeature.compareVersions("wrong", "3.1.12"));

        assertEquals(LanguageFeature.Behavior.REJECT, LanguageFeature.MULTI_CATCH.janinoBehavior("3.1.12"));
        assertEquals(LanguageFeature.Behavior.REJECT, LanguageFeature.MULTI_CATCH.janinoBehavior("3.1.15"));
        assertEquals(LanguageFeature.Behavior.JAVAC, LanguageFeature.MULTI_CATCH.janinoBehavior("3.1.16"));
        assertEquals(LanguageFeature.Behavior.REJECT, LanguageFeature.FINAL_LOCAL_ASSIGNMENT.javacBehavior());
        assertEquals(LanguageFeature.Behavior.JAVAC, LanguageFeature.FINAL_LOCAL_ASSIGNMENT.janinoBehavior("3.1.12"));
        assertEquals(LanguageFeature.Behavior.JANINO, LanguageFeature.NULL_BOOLEAN_OR.janinoBehavior("3.2.0-SNAPSHOT"));
        assertEquals(LanguageFeature.Behavior.REJECT, LanguageFeature.CONSTANT_SHIFT.janinoBehavior("3.2.0-SNAPSHOT"));
        assertEquals(LanguageFeature.Behavior.REJECT,
                LanguageFeature.VARARGS_ARRAY_ELEMENT.janinoBehavior("3.1.19"));
        assertEquals(LanguageFeature.Behavior.JAVAC,
                LanguageFeature.VARARGS_ARRAY_ELEMENT.janinoBehavior("3.1.20-SNAPSHOT"));

        LanguageFeature.Semantics javac = LanguageFeature.Semantics.JAVAC;
        LanguageFeature.Semantics janino = LanguageFeature.Semantics.JANINO;
        assertThrows(NullPointerException.class, () -> LanguageFeature.NULL_BOOLEAN_OR.evaluate(null, javac));
        assertEquals(Boolean.TRUE, LanguageFeature.NULL_BOOLEAN_OR.evaluate(null, janino));
        assertEquals("java.lang.Byte:-4", LanguageFeature.CONDITIONAL_BOXED_TYPE.evaluate((byte) -4, javac));
        assertEquals("java.lang.Integer:5", LanguageFeature.CONDITIONAL_BOXED_TYPE.evaluate((byte) 3, janino));
        assertEquals(Boolean.FALSE, LanguageFeature.CONSTANT_CONDITIONAL.evaluate(null, javac));
        assertEquals(Integer.MIN_VALUE, LanguageFeature.CONSTANT_SHIFT.evaluate(Integer.MAX_VALUE - 3, javac));
        assertEquals(null, LanguageFeature.VARARGS_ARRAY.evaluate(null, javac));
        assertEquals("x", LanguageFeature.BINARY_NAME.evaluate("x", janino));

        // The interpreter computes either.
        Expression probe = new Expression.Feature(LanguageFeature.VARARGS_BEFORE_BOXING,
                new Expression.Column(0, DataType.INT));
        Object[] row = {7};
        assertEquals("Object", new Interpreter(false).evaluate(probe, row));
        assertEquals("int...", new Interpreter(false, janino).evaluate(probe, row));
        assertFalse(LanguageFeature.ofProfile("PROBE").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Expression.Feature(LanguageFeature.NULL_BOOLEAN_OR,
                new Expression.Column(0, DataType.INT)));
    }
}
