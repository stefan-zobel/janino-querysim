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

import static io.github.janinolts.qsim.plan.Expressions.and;
import static io.github.janinolts.qsim.plan.Expressions.caseWhen;
import static io.github.janinolts.qsim.plan.Expressions.coalesce;
import static io.github.janinolts.qsim.plan.Expressions.div;
import static io.github.janinolts.qsim.plan.Expressions.eq;
import static io.github.janinolts.qsim.plan.Expressions.iff;
import static io.github.janinolts.qsim.plan.Expressions.isNotNull;
import static io.github.janinolts.qsim.plan.Expressions.isNull;
import static io.github.janinolts.qsim.plan.Expressions.lit;
import static io.github.janinolts.qsim.plan.Expressions.nullOf;
import static io.github.janinolts.qsim.plan.Expressions.or;
import static io.github.janinolts.qsim.plan.Expressions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class ThreeValuedLogicTest {

    private static final Expression T = lit(true);
    private static final Expression F = lit(false);
    private static final Expression N = nullOf(DataType.BOOLEAN);

    /** An expression that throws with ANSI when it is evaluated. */
    private static final Expression THROWS = eq(div(lit(1), lit(0)), lit(0));

    private static Object eval(Expression e) {
        return new Interpreter(false).evaluate(e, new Object[0]);
    }

    private static Object evalAnsi(Expression e) {
        return new Interpreter(true).evaluate(e, new Object[0]);
    }

    @Test
    void andTruthTable() {
        assertEquals(true, eval(and(T, T)));
        assertEquals(false, eval(and(T, F)));
        assertNull(eval(and(T, N)));
        assertEquals(false, eval(and(F, T)));
        assertEquals(false, eval(and(F, F)));
        assertEquals(false, eval(and(F, N)));
        assertNull(eval(and(N, T)));
        assertEquals(false, eval(and(N, F)));
        assertNull(eval(and(N, N)));
    }

    @Test
    void orTruthTable() {
        assertEquals(true, eval(or(T, T)));
        assertEquals(true, eval(or(T, F)));
        assertEquals(true, eval(or(T, N)));
        assertEquals(true, eval(or(F, T)));
        assertEquals(false, eval(or(F, F)));
        assertNull(eval(or(F, N)));
        assertEquals(true, eval(or(N, T)));
        assertNull(eval(or(N, F)));
        assertNull(eval(or(N, N)));
    }

    @Test
    void not() {
        assertEquals(false, eval(Expressions.not(T)));
        assertEquals(true, eval(Expressions.not(F)));
        assertNull(eval(Expressions.not(N)));
    }

    @Test
    void isNullIsNeverNull() {
        assertEquals(true, eval(isNull(N)));
        assertEquals(false, eval(isNull(T)));
        assertEquals(false, eval(isNotNull(N)));
        assertEquals(false, eval(isNotNull(nullOf(DataType.INT))));
        assertEquals(true, eval(isNotNull(lit(0))));
    }

    @Test
    void comparisonsWithNull() {
        assertNull(eval(eq(nullOf(DataType.INT), lit(1))));
        assertNull(eval(eq(nullOf(DataType.STRING), nullOf(DataType.STRING))));
    }

    @Test
    void shortCircuitAndOrderOfEvaluation() {
        assertEquals(false, evalAnsi(and(F, THROWS)));
        assertEquals(true, evalAnsi(or(T, THROWS)));
        assertThrows(ArithmeticException.class, () -> evalAnsi(and(T, THROWS)));
        assertThrows(ArithmeticException.class, () -> evalAnsi(and(N, THROWS)));
        // The left operand is evaluated first, even if the right one decides.
        assertThrows(ArithmeticException.class, () -> evalAnsi(and(THROWS, F)));
    }

    @Test
    void between() {
        assertEquals(true, eval(Expressions.between(lit(5), lit(1), lit(10))));
        assertEquals(false, eval(Expressions.between(lit(5), nullOf(DataType.INT), lit(4))));
        assertNull(eval(Expressions.between(lit(5), nullOf(DataType.INT), lit(10))));
        assertNull(eval(Expressions.between(nullOf(DataType.INT), lit(1), lit(10))));
        // Mixed types are compared in their common type.
        assertEquals(true, eval(Expressions.between(lit((byte) 5), lit(4.5), lit(5L))));
    }

    @Test
    void in() {
        assertEquals(true, eval(Expressions.in(lit(2), lit(1), lit(2))));
        assertEquals(false, eval(Expressions.in(lit(3), lit(1), lit(2))));
        assertNull(eval(Expressions.in(lit(3), lit(1), nullOf(DataType.INT))));
        assertEquals(true, eval(Expressions.in(lit(1), lit(1), nullOf(DataType.INT))));
        assertNull(eval(Expressions.in(nullOf(DataType.INT), lit(1))));
        // Stops at the first match; does not evaluate the list of a NULL value.
        assertEquals(true, evalAnsi(Expressions.in(lit(0), lit(0), div(lit(1), lit(0)))));
        assertNull(evalAnsi(Expressions.in(nullOf(DataType.INT), div(lit(1), lit(0)))));
    }

    @Test
    void caseIfAndCoalesceTreatNullAsFalseAndEvaluateLazily() {
        Expression c = caseWhen(List.of(when(N, lit(1)), when(T, lit(2L))), div(lit(1), lit(0)));
        assertEquals(2L, evalAnsi(c));
        assertEquals(DataType.LONG, c.type());
        assertEquals(3.0, eval(caseWhen(List.of(when(F, lit(1))), lit(3.0))));
        assertEquals(2, eval(iff(N, lit(1), lit(2))));
        assertEquals(1, evalAnsi(iff(T, lit(1), div(lit(1), lit(0)))));
        assertEquals(7L, eval(coalesce(nullOf(DataType.INT), lit(7L), lit(8))));
        assertNull(eval(coalesce(nullOf(DataType.INT), nullOf(DataType.INT))));
        assertEquals(1, evalAnsi(coalesce(lit(1), div(lit(1), lit(0)))));
    }

    @Test
    void notOfNullComparison() {
        assertNull(eval(Expressions.not(eq(nullOf(DataType.DOUBLE), lit(1.0)))));
        assertNull(eval(Expressions.not(Expressions.between(lit(1), nullOf(DataType.INT), lit(2)))));
        assertNull(eval(Expressions.not(Expressions.in(lit(1), nullOf(DataType.INT)))));
    }
}
