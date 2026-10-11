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

import static io.github.janinolts.qsim.plan.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;

class CastTest {

    private static Object cast(Object value, DataType from, DataType to) {
        return new Interpreter(false).evaluate(Expressions.cast(lit(from, value), to), new Object[0]);
    }

    private static Object castAnsi(Object value, DataType from, DataType to) {
        return new Interpreter(true).evaluate(Expressions.cast(lit(from, value), to), new Object[0]);
    }

    @Test
    void everyPairOfTypesIsDefinedForEveryEdgeValue() {
        for (DataType from : DataType.values()) {
            for (DataType to : DataType.values()) {
                for (Object v : EdgeValues.of(from)) {
                    Object result = cast(v, from, to);
                    assertTrue(to.accepts(result), from + " -> " + to + ": " + v + " gave " + result);
                    try {
                        Object ansi = castAnsi(v, from, to);
                        assertTrue(to.accepts(ansi));
                        if (ansi != null) {
                            assertEquals(result, ansi, from + " -> " + to + ": " + v);
                        }
                    } catch (RuntimeException e) {
                        // ANSI may reject what the non-ANSI mode converts.
                    }
                }
                assertNull(cast(null, from, to));
                assertNull(castAnsi(null, from, to));
            }
        }
    }

    @Test
    void integralNarrowingWrapsOrThrows() {
        assertEquals((byte) -128, cast(128, DataType.INT, DataType.BYTE));
        assertEquals((short) -1, cast(65535, DataType.INT, DataType.SHORT));
        assertEquals(0, cast(1L << 32, DataType.LONG, DataType.INT));
        assertThrows(ArithmeticException.class, () -> castAnsi(128, DataType.INT, DataType.BYTE));
        assertEquals((byte) 127, castAnsi(127, DataType.INT, DataType.BYTE));
    }

    @Test
    void floatingPointToIntegralAsJavaOrThrows() {
        assertEquals(0, cast(Double.NaN, DataType.DOUBLE, DataType.INT));
        assertEquals(Integer.MAX_VALUE, cast(1e19, DataType.DOUBLE, DataType.INT));
        assertEquals(Long.MAX_VALUE, cast(1e19, DataType.DOUBLE, DataType.LONG));
        // To a type narrower than int: first to int, then narrowed, as in Java.
        assertEquals((byte) -1, cast(1e10f, DataType.FLOAT, DataType.BYTE));
        assertEquals(-2, cast(-2.9, DataType.DOUBLE, DataType.INT));
        assertThrows(ArithmeticException.class, () -> castAnsi(Double.NaN, DataType.DOUBLE, DataType.INT));
        assertThrows(ArithmeticException.class, () -> castAnsi(0x1p63, DataType.DOUBLE, DataType.LONG));
        assertEquals(Long.MIN_VALUE, castAnsi(-0x1p63, DataType.DOUBLE, DataType.LONG));
        assertEquals((byte) -128, castAnsi(-128.99, DataType.DOUBLE, DataType.BYTE));
        assertThrows(ArithmeticException.class, () -> castAnsi(-129.0, DataType.DOUBLE, DataType.BYTE));
    }

    @Test
    void decimals() {
        assertEquals(new BigDecimal("0.1"), cast(0.1f, DataType.FLOAT, DataType.DECIMAL));
        assertEquals(new BigDecimal("0.1"), cast(0.1, DataType.DOUBLE, DataType.DECIMAL));
        assertNull(cast(Double.NaN, DataType.DOUBLE, DataType.DECIMAL));
        assertThrows(ArithmeticException.class, () -> castAnsi(Double.NaN, DataType.DOUBLE, DataType.DECIMAL));
        assertEquals(BigDecimal.valueOf(-5), cast((byte) -5, DataType.BYTE, DataType.DECIMAL));
        assertEquals(-1L, cast(new BigDecimal("-1.9"), DataType.DECIMAL, DataType.LONG));
        // Beyond long: the low-order 64 bits, as BigDecimal.longValue().
        assertEquals(Long.MIN_VALUE, cast(new BigDecimal("9223372036854775808"), DataType.DECIMAL, DataType.LONG));
        assertThrows(ArithmeticException.class,
                () -> castAnsi(new BigDecimal("9223372036854775808"), DataType.DECIMAL, DataType.LONG));
        assertEquals(Long.MAX_VALUE, castAnsi(new BigDecimal("9223372036854775807.9"), DataType.DECIMAL,
                DataType.LONG));
        assertEquals("10000000000", cast(new BigDecimal("1E+10"), DataType.DECIMAL, DataType.STRING));
        assertEquals("0.00", cast(new BigDecimal("0.00"), DataType.DECIMAL, DataType.STRING));
    }

    @Test
    void strings() {
        assertEquals(42, cast(" 42 ", DataType.STRING, DataType.INT));
        assertNull(cast("4x", DataType.STRING, DataType.INT));
        assertThrows(NumberFormatException.class, () -> castAnsi("4x", DataType.STRING, DataType.INT));
        assertNull(cast("128", DataType.STRING, DataType.BYTE));
        assertEquals(Double.NaN, cast("NaN", DataType.STRING, DataType.DOUBLE));
        assertEquals(true, cast(" TRUE", DataType.STRING, DataType.BOOLEAN));
        assertNull(cast("yes", DataType.STRING, DataType.BOOLEAN));
        assertThrows(IllegalArgumentException.class, () -> castAnsi("yes", DataType.STRING, DataType.BOOLEAN));
        assertEquals("-0.0", cast(-0.0, DataType.DOUBLE, DataType.STRING));
        assertEquals("1.0E10", cast(1e10f, DataType.FLOAT, DataType.STRING));
        assertEquals("NaN", cast(Float.NaN, DataType.FLOAT, DataType.STRING));
    }

    @Test
    void dates() {
        assertEquals("1970-01-01", cast(0, DataType.DATE, DataType.STRING));
        assertEquals("1969-12-31", cast(-1, DataType.DATE, DataType.STRING));
        assertEquals(11016, cast("2000-02-29", DataType.STRING, DataType.DATE));
        assertNull(cast("2026-13-01", DataType.STRING, DataType.DATE));
        assertThrows(DateTimeParseException.class, () -> castAnsi("2026-13-01", DataType.STRING, DataType.DATE));
        assertEquals(11016L, cast(11016, DataType.DATE, DataType.LONG));
        assertEquals(1, cast(1.9, DataType.DOUBLE, DataType.DATE));
        assertEquals((short) -1, cast(Integer.MAX_VALUE, DataType.DATE, DataType.SHORT));
    }

    @Test
    void booleans() {
        assertEquals((byte) 1, cast(true, DataType.BOOLEAN, DataType.BYTE));
        assertEquals(0.0f, cast(false, DataType.BOOLEAN, DataType.FLOAT));
        assertEquals(BigDecimal.ONE, cast(true, DataType.BOOLEAN, DataType.DECIMAL));
        assertEquals(1, cast(true, DataType.BOOLEAN, DataType.DATE));
        assertEquals(true, cast(Double.NaN, DataType.DOUBLE, DataType.BOOLEAN));
        assertEquals(false, cast(-0.0f, DataType.FLOAT, DataType.BOOLEAN));
        assertEquals(false, cast(new BigDecimal("0.00"), DataType.DECIMAL, DataType.BOOLEAN));
        assertEquals(true, cast(Long.MIN_VALUE, DataType.LONG, DataType.BOOLEAN));
        assertEquals("true", cast(true, DataType.BOOLEAN, DataType.STRING));
    }

    @Test
    void floatingPointNarrowing() {
        assertEquals(Float.POSITIVE_INFINITY, cast(1e300, DataType.DOUBLE, DataType.FLOAT));
        assertThrows(ArithmeticException.class, () -> castAnsi(1e300, DataType.DOUBLE, DataType.FLOAT));
        assertEquals(Float.POSITIVE_INFINITY, castAnsi(Double.POSITIVE_INFINITY, DataType.DOUBLE, DataType.FLOAT));
        assertEquals(16_777_216f, cast(16_777_217, DataType.INT, DataType.FLOAT));
    }

    @Test
    void castExpressionHasTheTargetType() {
        assertEquals(DataType.DATE, Expressions.cast(lit(1), DataType.DATE).type());
    }
}
