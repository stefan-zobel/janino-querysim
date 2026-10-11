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

package io.github.janinolts.qsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;

class ConversionsTest {

    @Test
    void parseTrims() {
        assertEquals(42, Conversions.parseInt(" 42\t"));
        assertEquals((byte) -128, Conversions.parseByte("-128"));
        assertThrows(NumberFormatException.class, () -> Conversions.parseByte("128"));
        assertThrows(NumberFormatException.class, () -> Conversions.parseLong(""));
        assertEquals(new BigDecimal("1.50"), Conversions.parseDecimal(" 1.50 "));
        assertEquals(Double.NEGATIVE_INFINITY, Conversions.parseDouble("-Infinity"));
    }

    @Test
    void booleans() {
        assertEquals(true, Conversions.parseBoolean("TRUE"));
        assertEquals(false, Conversions.parseBoolean(" false "));
        assertThrows(IllegalArgumentException.class, () -> Conversions.parseBoolean("1"));
    }

    @Test
    void dates() {
        assertEquals(0, Conversions.parseDate("1970-01-01"));
        assertEquals("2026-10-11", Conversions.formatDate(Conversions.parseDate("2026-10-11")));
        assertThrows(DateTimeParseException.class, () -> Conversions.parseDate("2026-02-30"));
        assertThrows(ArithmeticException.class, () -> Conversions.parseDate("+999999999-12-31"));
        assertEquals("+5881580-07-11", Conversions.formatDate(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, Conversions.parseDate(Conversions.formatDate(Integer.MAX_VALUE)));
    }

    @Test
    void decimals() {
        assertEquals("1000", Conversions.formatDecimal(new BigDecimal("1E+3")));
        assertEquals(new BigDecimal("0.3333333333333333"),
                DecimalFunctions.divide(BigDecimal.ONE, BigDecimal.valueOf(3)));
        assertThrows(ArithmeticException.class, () -> DecimalFunctions.divide(BigDecimal.ONE, BigDecimal.ZERO));
    }
}
