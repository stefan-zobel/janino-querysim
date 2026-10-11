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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The fixed edge values of each type, apart from {@code NULL}: the bounds,
 * zero, minus one, the values just beyond the next narrower type, the special
 * floating-point values, and strings that are empty, blank, outside the Basic
 * Multilingual Plane, or parseable as other types.
 */
public final class EdgeValues {

    private EdgeValues() {
    }

    /** Returns the edge values of a type, without {@code NULL}. */
    public static List<Object> of(DataType type) {
        return switch (type) {
            case BOOLEAN -> List.of(true, false);
            case BYTE -> List.of(Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 0, (byte) -1, (byte) 1);
            case SHORT -> List.of(Short.MIN_VALUE, Short.MAX_VALUE, (short) 0, (short) -1, (short) 1,
                    (short) 127, (short) 128, (short) -129);
            case INT -> List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1, 32767, 32768, -32769, 65535);
            case LONG -> List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L,
                    (long) Integer.MAX_VALUE + 1, (long) Integer.MIN_VALUE - 1);
            case FLOAT -> List.of(Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f, -0.0f, -1.0f,
                    0.5f, -0.5f, 1e10f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
            case DOUBLE -> List.of(Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 0.0, -0.0, -1.0,
                    0.5, -0.5, 1e19, 0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
            case STRING -> List.of("", " ", "a", "A", "\u00DF", "\uD834\uDD1E", "%", "_", "123", " 42 ",
                    "-0", "NaN", "true", "1970-01-01", "2026-13-01");
            case DECIMAL -> List.of(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE.negate(),
                    new BigDecimal("0.1"), new BigDecimal("0.00"), new BigDecimal("0.5"),
                    new BigDecimal("-0.5"), new BigDecimal("1E+10"), new BigDecimal("9223372036854775808"),
                    new BigDecimal("123456789.123456789"));
            case DATE -> List.of(0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    (int) LocalDate.of(2000, 2, 29).toEpochDay(), (int) LocalDate.of(9999, 12, 31).toEpochDay());
        };
    }
}
