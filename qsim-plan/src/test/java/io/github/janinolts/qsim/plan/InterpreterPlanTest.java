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

import static io.github.janinolts.qsim.plan.Expressions.add;
import static io.github.janinolts.qsim.plan.Expressions.col;
import static io.github.janinolts.qsim.plan.Expressions.div;
import static io.github.janinolts.qsim.plan.Expressions.gt;
import static io.github.janinolts.qsim.plan.Expressions.lit;
import static io.github.janinolts.qsim.plan.Expressions.mul;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.janinolts.qsim.plan.PlanNode.Filter;
import io.github.janinolts.qsim.plan.PlanNode.Layout;
import io.github.janinolts.qsim.plan.PlanNode.NamedExpression;
import io.github.janinolts.qsim.plan.PlanNode.Project;
import io.github.janinolts.qsim.plan.PlanNode.Scan;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InterpreterPlanTest {

    private static final Schema SCHEMA = Schema.of(DataType.INT, DataType.INT);

    private static final Table TABLE = new Table(SCHEMA, Arrays.asList(
            new Object[] {1, 10},
            new Object[] {2, 0},
            new Object[] {null, 5},
            new Object[] {4, 2}));

    private static final Scan SCAN = new Scan("t", SCHEMA, Layout.ROWS);

    private static List<Object[]> run(PlanNode plan, boolean ansi) {
        return new Interpreter(ansi).execute(plan, Map.of("t", TABLE));
    }

    @Test
    void scanFilterProject() {
        PlanNode plan = new Project(new Filter(SCAN, gt(col(0, DataType.INT), lit(1))), List.of(
                new NamedExpression("sum", add(col(0, DataType.INT), col(1, DataType.INT))),
                new NamedExpression("twice", mul(col(0, DataType.INT), lit(2L)))));

        List<Object[]> rows = run(plan, false);

        assertEquals(2, rows.size());
        assertArrayEquals(new Object[] {2, 4L}, rows.get(0));
        assertArrayEquals(new Object[] {6, 8L}, rows.get(1));
        assertEquals(Schema.of(DataType.INT, DataType.LONG).fields().stream().map(Schema.Field::type).toList(),
                plan.schema().fields().stream().map(Schema.Field::type).toList());
    }

    @Test
    void nullConditionDropsTheRow() {
        PlanNode plan = new Filter(SCAN, gt(col(0, DataType.INT), lit(0)));
        assertEquals(3, run(plan, false).size());
    }

    @Test
    void exceptionCarriesTheIndexOfTheScannedRow() {
        PlanNode plan = new Project(SCAN, List.of(
                new NamedExpression("q", div(col(0, DataType.INT), col(1, DataType.INT)))));

        assertArrayEquals(new Object[] {null}, run(plan, false).get(1));
        RowException e = assertThrows(RowException.class, () -> run(plan, true));
        assertEquals(1, e.rowIndex());
        assertInstanceOf(ArithmeticException.class, e.getCause());
    }

    @Test
    void rowsFlowThroughThePipelineOneAtATime() {
        // Row 1 fails in the projection before row 3 could fail in the filter.
        PlanNode plan = new Project(
                new Filter(SCAN, gt(div(lit(100), add(col(1, DataType.INT), lit(-2))), lit(-1000))),
                List.of(new NamedExpression("q", div(lit(1), col(1, DataType.INT)))));

        RowException e = assertThrows(RowException.class, () -> run(plan, true));
        assertEquals(1, e.rowIndex());
    }

    @Test
    void plansCheckTheirExpressions() {
        assertThrows(IllegalArgumentException.class, () -> new Filter(SCAN, lit(1)));
        assertThrows(IllegalArgumentException.class, () -> new Filter(SCAN, gt(col(2, DataType.INT), lit(1))));
        assertThrows(IllegalArgumentException.class, () -> new Filter(SCAN, gt(col(0, DataType.LONG), lit(1))));
    }

    @Test
    void scanChecksTheSchemaOfTheTable() {
        Scan other = new Scan("t", Schema.of(DataType.INT), Layout.COLUMNS);
        assertThrows(IllegalArgumentException.class, () -> run(other, false));
    }
}
