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
import static io.github.janinolts.qsim.plan.Expressions.bitwise;
import static io.github.janinolts.qsim.plan.Expressions.call;
import static io.github.janinolts.qsim.plan.Expressions.cmp;
import static io.github.janinolts.qsim.plan.Expressions.div;
import static io.github.janinolts.qsim.plan.Expressions.eq;
import static io.github.janinolts.qsim.plan.Expressions.gt;
import static io.github.janinolts.qsim.plan.Expressions.lit;
import static io.github.janinolts.qsim.plan.Expressions.lt;
import static io.github.janinolts.qsim.plan.Expressions.mul;
import static io.github.janinolts.qsim.plan.Expressions.neg;
import static io.github.janinolts.qsim.plan.Expressions.nullOf;
import static io.github.janinolts.qsim.plan.Expressions.rem;
import static io.github.janinolts.qsim.plan.Expressions.shift;
import static io.github.janinolts.qsim.plan.Expressions.sub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.janinolts.qsim.plan.Expression.BitwiseOp;
import io.github.janinolts.qsim.plan.Expression.ComparisonOp;
import io.github.janinolts.qsim.plan.Expression.MathFunction;
import io.github.janinolts.qsim.plan.Expression.ShiftOp;
import io.github.janinolts.qsim.plan.Expression.StringFunction;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ArithmeticTest {

    private static Object eval(Expression e) {
        return new Interpreter(false).evaluate(e, new Object[0]);
    }

    private static Object evalAnsi(Expression e) {
        return new Interpreter(true).evaluate(e, new Object[0]);
    }

    @Test
    void resultTypes() {
        assertEquals(DataType.BYTE, add(lit((byte) 1), lit((byte) 1)).type());
        assertEquals(DataType.SHORT, add(lit((byte) 1), lit((short) 1)).type());
        assertEquals(DataType.DECIMAL, add(lit(1L), lit(BigDecimal.ONE)).type());
        assertEquals(DataType.FLOAT, add(lit(1L), lit(1f)).type());
        assertEquals(DataType.DOUBLE, add(lit(BigDecimal.ONE), lit(1f)).type());
        assertEquals(DataType.DOUBLE, add(lit(1.0), lit(1f)).type());
        assertThrows(IllegalArgumentException.class, () -> add(lit(1), lit("1")));
        assertThrows(IllegalArgumentException.class, () -> add(Expressions.date(1), lit(1)));
        assertThrows(IllegalArgumentException.class, () -> eq(lit(true), lit(1)));
        assertThrows(IllegalArgumentException.class, () -> bitwise(BitwiseOp.AND, lit(1), lit(1.0)));
    }

    @Test
    void narrowTypesWrapAroundOrThrow() {
        assertEquals((byte) -128, eval(add(lit(Byte.MAX_VALUE), lit((byte) 1))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(add(lit(Byte.MAX_VALUE), lit((byte) 1))));
        assertEquals((short) 0, eval(mul(lit((short) 256), lit((short) 256))));
        assertEquals((byte) -128, eval(neg(lit(Byte.MIN_VALUE))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(neg(lit(Byte.MIN_VALUE))));
        assertEquals((byte) -128, eval(div(lit(Byte.MIN_VALUE), lit((byte) -1))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(div(lit(Byte.MIN_VALUE), lit((byte) -1))));
        assertEquals((byte) 0, evalAnsi(rem(lit(Byte.MIN_VALUE), lit((byte) -1))));
    }

    @Test
    void intAndLongOverflow() {
        assertEquals(Integer.MIN_VALUE, eval(add(lit(Integer.MAX_VALUE), lit(1))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(add(lit(Integer.MAX_VALUE), lit(1))));
        assertEquals(Integer.MIN_VALUE, eval(div(lit(Integer.MIN_VALUE), lit(-1))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(div(lit(Integer.MIN_VALUE), lit(-1))));
        assertEquals(Long.MIN_VALUE, eval(sub(lit(Long.MIN_VALUE), lit(0L))));
        assertEquals(Long.MAX_VALUE, eval(sub(lit(Long.MIN_VALUE), lit(1L))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(sub(lit(Long.MIN_VALUE), lit(1L))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(mul(lit(Long.MAX_VALUE), lit(2L))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(div(lit(Long.MIN_VALUE), lit(-1L))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(neg(lit(Long.MIN_VALUE))));
        // A mixed operation is computed in the wider type: no overflow here.
        assertEquals(128L, evalAnsi(add(lit(Byte.MAX_VALUE), lit(1L))));
    }

    @Test
    void divisionByZero() {
        assertNull(eval(div(lit(1), lit(0))));
        assertNull(eval(rem(lit(1L), lit(0L))));
        assertNull(eval(div(lit(1.0), lit(-0.0))));
        assertNull(eval(div(lit(BigDecimal.ONE), lit(new BigDecimal("0.00")))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(div(lit(1f), lit(0f))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(rem(lit(BigDecimal.ONE), lit(BigDecimal.ZERO))));
        // NULL wins over the division by zero.
        assertNull(evalAnsi(div(nullOf(DataType.INT), lit(0))));
    }

    @Test
    void floatingPoint() {
        assertEquals(Double.NaN, eval(mul(lit(0.0), lit(Double.POSITIVE_INFINITY))));
        assertEquals(-0.0, eval(neg(lit(0.0))));
        assertEquals(0.5f, eval(rem(lit(2.5f), lit(1f))));
        assertEquals(1.0E19f, eval(add(lit(1e19f), lit(1L))));
    }

    @Test
    void decimals() {
        assertEquals(new BigDecimal("0.3333333333333333"), eval(div(lit(BigDecimal.ONE), lit(BigDecimal.valueOf(3)))));
        assertEquals(new BigDecimal("1.10"), eval(add(lit(new BigDecimal("1.00")), lit(new BigDecimal("0.1")))));
        assertEquals(new BigDecimal("2.5"), eval(mul(lit(new BigDecimal("0.5")), lit(5))));
        assertEquals(new BigDecimal("0.10"), eval(rem(lit(new BigDecimal("1.10")), lit(new BigDecimal("0.5")))));
    }

    @Test
    void comparisonsOfFloatingPointAsJava() {
        Expression nan = lit(Double.NaN);
        for (ComparisonOp op : ComparisonOp.values()) {
            assertEquals(op == ComparisonOp.NE, eval(cmp(op, nan, nan)), op::toString);
        }
        assertEquals(true, eval(eq(lit(-0.0), lit(0.0f))));
        assertEquals(true, eval(lt(lit(Float.NEGATIVE_INFINITY), lit(Long.MIN_VALUE))));
        assertEquals(true, eval(eq(lit(new BigDecimal("1.0")), lit(1))));
        assertEquals(true, eval(gt(lit("b"), lit("a"))));
        // UTF-16 order: U+FFFF sorts after the surrogates of a supplementary character.
        assertEquals(false, eval(lt(lit("\uFFFF"), lit("\uD834\uDD1E"))));
        assertEquals(true, eval(lt(lit(false), lit(true))));
        assertEquals(true, eval(lt(Expressions.date(-1), Expressions.date(0))));
        // 2^24 + 1 as an int differs from the float it converts to, but compares equal to it.
        assertEquals(true, eval(eq(lit(16_777_217), lit(16_777_216f))));
    }

    @Test
    void shiftsAndBitsAsJava() {
        assertEquals((byte) -1, eval(shift(ShiftOp.UNSIGNED_RIGHT, lit((byte) -1), lit(1))));
        assertEquals((byte) 0, eval(shift(ShiftOp.LEFT, lit((byte) 1), lit(8))));
        assertEquals((byte) 2, eval(shift(ShiftOp.LEFT, lit((byte) 1), lit(33))));
        assertEquals(2L, eval(shift(ShiftOp.LEFT, lit(1L), lit(65))));
        assertEquals((short) -32768, evalAnsi(shift(ShiftOp.LEFT, lit((short) 1), lit(15))));
        assertEquals(Integer.MAX_VALUE, eval(shift(ShiftOp.UNSIGNED_RIGHT, lit(-1), lit(1))));
        assertEquals((byte) 0x7f, eval(bitwise(BitwiseOp.XOR, lit((byte) -1), lit((byte) -128))));
        assertEquals(-1, eval(bitwise(BitwiseOp.AND, lit(-1), lit((byte) -1))));
        assertEquals((short) -1, eval(new Expression.BitNot(lit((short) 0))));
    }

    @Test
    void functions() {
        assertEquals((byte) -128, eval(call(MathFunction.ABS, lit(Byte.MIN_VALUE))));
        assertThrows(ArithmeticException.class, () -> evalAnsi(call(MathFunction.ABS, lit(Integer.MIN_VALUE))));
        assertEquals(Long.MIN_VALUE, eval(call(MathFunction.ABS, lit(Long.MIN_VALUE))));
        assertEquals(0.0, eval(call(MathFunction.ABS, lit(-0.0))));
        assertEquals(Double.NaN, eval(call(MathFunction.SQRT, lit(-1.0))));
        assertEquals(1024.0, eval(call(MathFunction.POWER, lit(2.0), lit(10.0))));
        assertEquals(-3.0, eval(call(MathFunction.FLOOR, lit(-2.5))));
        assertThrows(IllegalArgumentException.class, () -> call(MathFunction.SQRT, lit(1)));

        assertEquals("ab", eval(call(StringFunction.CONCAT, lit("a"), lit("b"))));
        assertNull(eval(call(StringFunction.CONCAT, lit("a"), nullOf(DataType.STRING))));
        assertEquals(2, eval(call(StringFunction.LENGTH, lit("\uD834\uDD1E"))));
        assertEquals("\u00DFX", eval(call(StringFunction.UPPER, lit("\u00DFx"))));
        assertEquals("bc", eval(call(StringFunction.SUBSTRING, lit("abcd"), lit(2), lit(2))));
        // There is no escape character: the backslash matches itself.
        assertEquals(false, eval(Expressions.like(lit("a%c"), lit("a\\%c"))));
        assertEquals(true, eval(Expressions.like(lit("a\\xc"), lit("a\\%c"))));
        // All arguments are evaluated, also after a NULL.
        assertThrows(ArithmeticException.class, () -> evalAnsi(call(StringFunction.SUBSTRING,
                nullOf(DataType.STRING), div(lit(1), lit(0)), lit(1))));
    }
}
