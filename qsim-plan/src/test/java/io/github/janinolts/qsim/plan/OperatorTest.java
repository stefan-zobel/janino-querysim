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

import static io.github.janinolts.qsim.plan.Expressions.col;
import static io.github.janinolts.qsim.plan.Expressions.div;
import static io.github.janinolts.qsim.plan.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.plan.PlanNode.AggregateCall;
import io.github.janinolts.qsim.plan.PlanNode.AggregateFunction;
import io.github.janinolts.qsim.plan.PlanNode.Layout;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OperatorTest {

    private static final Schema KV = Schema.of(DataType.INT, DataType.LONG);

    private static Table table(Schema schema, Object[]... rows) {
        return new Table(schema, Arrays.asList(rows));
    }

    private static List<Object[]> run(PlanNode plan, boolean ansi, Map<String, Table> tables) {
        return new Interpreter(ansi).execute(plan, tables);
    }

    @Test
    void aggregationGroupsNullsTogetherAndShowsCanonicalKeys() {
        Schema schema = Schema.of(DataType.DOUBLE, DataType.DECIMAL, DataType.INT);
        Table t = table(schema,
                new Object[] {0.0, new BigDecimal("1.0"), 5},
                new Object[] {-0.0, new BigDecimal("1.00"), 7},
                new Object[] {null, null, null},
                new Object[] {Double.NaN, new BigDecimal("2"), 1},
                new Object[] {null, null, 3},
                new Object[] {Double.longBitsToDouble(0x7ff8000000000001L), new BigDecimal("2.0"), 2});
        PlanNode plan = new PlanNode.HashAggregate(new PlanNode.Scan("t", schema, Layout.ROWS), List.of(0, 1),
                List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, "n"),
                        new AggregateCall(AggregateFunction.COUNT, 2, "c"),
                        new AggregateCall(AggregateFunction.SUM, 2, "s"),
                        new AggregateCall(AggregateFunction.MIN, 2, "min"),
                        new AggregateCall(AggregateFunction.AVG, 2, "avg")));

        List<Object[]> rows = run(plan, false, Map.of("t", t));

        assertEquals(3, rows.size());
        assertArrayEquals(new Object[] {0.0, BigDecimal.ONE, 2L, 2L, 12L, 5, 6.0}, rows.get(0));
        assertArrayEquals(new Object[] {null, null, 2L, 1L, 3L, 3, 3.0}, rows.get(1));
        assertArrayEquals(new Object[] {Double.NaN, new BigDecimal("2"), 2L, 2L, 3L, 1, 1.5}, rows.get(2));
    }

    @Test
    void globalAggregationOfNothingGivesOneRow() {
        PlanNode plan = new PlanNode.HashAggregate(
                new PlanNode.Filter(new PlanNode.Scan("t", KV, Layout.ROWS), lit(false)), List.of(),
                List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, "n"),
                        new AggregateCall(AggregateFunction.SUM, 1, "s"),
                        new AggregateCall(AggregateFunction.MAX, 0, "m")));
        List<Object[]> rows = run(plan, false, Map.of("t", table(KV, new Object[] {1, 2L})));
        assertEquals(1, rows.size());
        assertArrayEquals(new Object[] {0L, null, null}, rows.get(0));

        PlanNode grouped = new PlanNode.HashAggregate(((PlanNode.HashAggregate) plan).input(), List.of(0),
                List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, "n")));
        assertEquals(0, run(grouped, false, Map.of("t", table(KV, new Object[] {1, 2L}))).size());
    }

    @Test
    void sumOverflowsOrThrowsAtItsRow() {
        Table t = table(KV, new Object[] {1, Long.MAX_VALUE}, new Object[] {1, 1L}, new Object[] {1, 5L});
        PlanNode plan = new PlanNode.HashAggregate(new PlanNode.Scan("t", KV, Layout.ROWS), List.of(0),
                List.of(new AggregateCall(AggregateFunction.SUM, 1, "s")));
        assertArrayEquals(new Object[] {1, Long.MIN_VALUE + 5}, run(plan, false, Map.of("t", t)).get(0));
        RowException e = assertThrows(RowException.class, () -> run(plan, true, Map.of("t", t)));
        assertEquals("t", e.source());
        assertEquals(1, e.rowIndex());
    }

    @Test
    void joinOrderNullsAndOuterRows() {
        Schema probeSchema = Schema.of(DataType.INT, DataType.STRING);
        Schema buildSchema = Schema.of(DataType.INT, DataType.LONG);
        Table probe = table(probeSchema, new Object[] {1, "a"}, new Object[] {null, "b"}, new Object[] {2, "c"},
                new Object[] {1, "d"});
        Table build = table(buildSchema, new Object[] {1, 10L}, new Object[] {null, 20L}, new Object[] {1, 30L},
                new Object[] {3, 40L});
        PlanNode p = new PlanNode.Scan("p", probeSchema, Layout.ROWS);
        PlanNode b = new PlanNode.Scan("b", buildSchema, Layout.ROWS);
        Map<String, Table> tables = Map.of("p", probe, "b", build);

        List<Object[]> inner = run(new PlanNode.HashJoin(p, b, List.of(0), List.of(0), PlanNode.JoinType.INNER),
                false, tables);
        assertEquals(4, inner.size());
        assertArrayEquals(new Object[] {1, "a", 1, 10L}, inner.get(0));
        assertArrayEquals(new Object[] {1, "a", 1, 30L}, inner.get(1));
        assertArrayEquals(new Object[] {1, "d", 1, 30L}, inner.get(3));

        List<Object[]> outer = run(new PlanNode.HashJoin(p, b, List.of(0), List.of(0),
                PlanNode.JoinType.LEFT_OUTER), false, tables);
        assertEquals(6, outer.size());
        assertArrayEquals(new Object[] {null, "b", null, null}, outer.get(2));
        assertArrayEquals(new Object[] {2, "c", null, null}, outer.get(3));
    }

    @Test
    void theBuildSideRunsBeforeTheProbeSide() {
        // Both sides throw in their first row; the build side throws first.
        PlanNode failing = new PlanNode.Project(new PlanNode.Scan("t", KV, Layout.ROWS),
                List.of(new PlanNode.NamedExpression("q", div(col(0, DataType.INT), lit(0)))));
        PlanNode failingBuild = new PlanNode.Project(new PlanNode.Scan("u", KV, Layout.ROWS),
                List.of(new PlanNode.NamedExpression("q", div(col(0, DataType.INT), lit(0)))));
        PlanNode join = new PlanNode.HashJoin(failing, failingBuild, List.of(0), List.of(0), PlanNode.JoinType.INNER);
        Map<String, Table> tables = Map.of("t", table(KV, new Object[] {1, 1L}), "u", table(KV, new Object[] {1, 1L}));
        RowException e = assertThrows(RowException.class, () -> run(join, true, tables));
        assertEquals("u", e.source());
    }

    @Test
    void sortIsStableAndTotal() {
        Schema schema = Schema.of(DataType.DOUBLE, DataType.INT);
        Table t = table(schema, new Object[] {Double.NaN, 1}, new Object[] {0.0, 2}, new Object[] {null, 3},
                new Object[] {-0.0, 4}, new Object[] {0.0, 5}, new Object[] {Double.NEGATIVE_INFINITY, 6});
        PlanNode scan = new PlanNode.Scan("t", schema, Layout.ROWS);
        List<Object[]> asc = run(new PlanNode.Sort(scan, List.of(new PlanNode.SortKey(0, true, true))), false,
                Map.of("t", t));
        assertEquals(List.of(3, 6, 4, 2, 5, 1), asc.stream().map(r -> r[1]).toList());
        List<Object[]> desc = run(new PlanNode.Sort(scan, List.of(new PlanNode.SortKey(0, false, false))), false,
                Map.of("t", t));
        assertEquals(List.of(1, 2, 5, 4, 6, 3), desc.stream().map(r -> r[1]).toList());
    }

    @Test
    void limitStopsPulling() {
        // The third row would throw; the limit of two never pulls it.
        Table t = table(KV, new Object[] {1, 1L}, new Object[] {2, 1L}, new Object[] {0, 1L});
        PlanNode plan = new PlanNode.Limit(new PlanNode.Project(new PlanNode.Scan("t", KV, Layout.ROWS),
                List.of(new PlanNode.NamedExpression("q", div(lit(10), col(0, DataType.INT))))), 2);
        assertEquals(2, run(plan, true, Map.of("t", t)).size());
    }

    @Test
    void anExceptionAfterACollectingOperatorIsAttributedToItsOutputRow() {
        Table t = table(KV, new Object[] {1, 2L}, new Object[] {0, 3L}, new Object[] {5, 4L});
        PlanNode sorted = new PlanNode.Sort(new PlanNode.Scan("t", KV, Layout.ROWS),
                List.of(new PlanNode.SortKey(0, false, true)));
        PlanNode plan = new PlanNode.Project(sorted,
                List.of(new PlanNode.NamedExpression("q", div(lit(10), col(0, DataType.INT)))));
        RowException e = assertThrows(RowException.class, () -> run(plan, true, Map.of("t", t)));
        // Sorted descending: 5, 1, 0; the sort is node 1 of the plan.
        assertEquals("node:1", e.source());
        assertEquals(2, e.rowIndex());
    }

    @Test
    void unionKeepsTheOrderOfItsInputs() {
        PlanNode a = new PlanNode.Scan("a", KV, Layout.ROWS);
        PlanNode b = new PlanNode.Scan("b", KV, Layout.ROWS);
        List<Object[]> rows = run(new PlanNode.Union(List.of(b, a)), false, Map.of(
                "a", table(KV, new Object[] {1, 1L}), "b", table(KV, new Object[] {2, 2L}, new Object[] {3, 3L})));
        assertEquals(List.of(2, 3, 1), rows.stream().map(r -> r[0]).toList());
    }

    @Test
    void allScenariosRunInAllSizes() {
        for (String name : Scenario.NAMES) {
            for (Scenario.Size size : Scenario.Size.values()) {
                Scenario s = Scenario.of(name, size, Layout.ROWS);
                for (boolean ansi : new boolean[] {false, true}) {
                    try {
                        List<Object[]> rows = new Interpreter(ansi).execute(s.plan(), s.data(1, 64));
                        assertTrue(ansi || !rows.isEmpty(), name + " " + size);
                    } catch (RowException e) {
                        assertTrue(ansi, () -> name + " " + size + " throws without ANSI: " + e);
                    }
                }
            }
        }
        assertTrue(Scenario.ordered(Scenario.of("Q_SORT", Scenario.Size.S, Layout.ROWS).plan()));
    }
}
