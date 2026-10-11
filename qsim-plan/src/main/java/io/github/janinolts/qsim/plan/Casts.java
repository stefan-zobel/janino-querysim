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

import io.github.janinolts.qsim.runtime.Conversions;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The semantics of {@code CAST} and of the implicit widening conversions.
 * <p>
 * Without ANSI, a cast never fails: integral narrowing wraps around and a
 * floating-point value converts to an integral type as a Java cast does
 * ({@code NaN} to 0, saturation at the bounds); only a string that cannot be
 * parsed and a {@code NaN} or infinite value converted to {@code DECIMAL} give
 * {@code NULL}. With ANSI, each of these cases throws: an out-of-range value an
 * {@link ArithmeticException}, a string the exception of the parse method in
 * {@link Conversions}. A {@code DATE} converts like the {@code INT} of its
 * days, except to and from {@code STRING}, where it is an ISO date.
 */
final class Casts {

    private Casts() {
    }

    /** Converts a value; see the class description. */
    static Object cast(Object value, DataType from, DataType to, boolean ansi) {
        if (value == null || from == to) {
            return value;
        }
        if (to == DataType.STRING) {
            return format(value, from);
        }
        if (from == DataType.STRING) {
            return parse((String) value, to, ansi);
        }
        if (from == DataType.BOOLEAN) {
            return numeric((Boolean) value ? 1L : 0L, DataType.LONG, numericType(to), ansi);
        }
        if (to == DataType.BOOLEAN) {
            return isNonZero(value, numericType(from));
        }
        return numeric(value, numericType(from), numericType(to), ansi);
    }

    /** Converts a numeric value to a wider or equal numeric type, as for the operands of arithmetic. */
    static Object widen(Object value, DataType from, DataType to) {
        if (value == null || from == to) {
            return value;
        }
        return numeric(value, from, to, false);
    }

    /** The numeric type that stands for a type in a conversion: {@code INT} for {@code DATE}. */
    private static DataType numericType(DataType type) {
        return type == DataType.DATE ? DataType.INT : type;
    }

    private static String format(Object value, DataType from) {
        return switch (from) {
            case DECIMAL -> Conversions.formatDecimal((BigDecimal) value);
            case DATE -> Conversions.formatDate((Integer) value);
            default -> String.valueOf(value);
        };
    }

    private static Object parse(String s, DataType to, boolean ansi) {
        try {
            return switch (to) {
                case BOOLEAN -> Conversions.parseBoolean(s);
                case BYTE -> Conversions.parseByte(s);
                case SHORT -> Conversions.parseShort(s);
                case INT -> Conversions.parseInt(s);
                case LONG -> Conversions.parseLong(s);
                case FLOAT -> Conversions.parseFloat(s);
                case DOUBLE -> Conversions.parseDouble(s);
                case DECIMAL -> Conversions.parseDecimal(s);
                case DATE -> Conversions.parseDate(s);
                case STRING -> s;
            };
        } catch (RuntimeException e) {
            if (ansi) {
                throw e;
            }
            return null;
        }
    }

    private static boolean isNonZero(Object value, DataType from) {
        return switch (from) {
            case FLOAT, DOUBLE -> ((Number) value).doubleValue() != 0;
            case DECIMAL -> ((BigDecimal) value).signum() != 0;
            default -> ((Number) value).longValue() != 0;
        };
    }

    /** Converts between numeric types (not {@code DATE}). */
    private static Object numeric(Object value, DataType from, DataType to, boolean ansi) {
        if (from.isIntegral()) {
            long x = ((Number) value).longValue();
            return switch (to) {
                case FLOAT -> (float) x;
                case DOUBLE -> (double) x;
                case DECIMAL -> BigDecimal.valueOf(x);
                default -> Values.integral(ansi ? Values.checkRange(x, to) : x, to);
            };
        }
        if (from.isFloatingPoint()) {
            double d = ((Number) value).doubleValue();
            return switch (to) {
                case FLOAT -> {
                    float f = (float) d;
                    if (ansi && Float.isInfinite(f) && !Double.isInfinite(d)) {
                        throw new ArithmeticException("Out of the range of FLOAT: " + d);
                    }
                    yield f;
                }
                case DOUBLE -> d;
                case DECIMAL -> {
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        if (ansi) {
                            throw new ArithmeticException("Not a DECIMAL: " + d);
                        }
                        yield null;
                    }
                    yield from == DataType.FLOAT ? new BigDecimal(Float.toString((Float) value))
                            : BigDecimal.valueOf(d);
                }
                default -> floatingToIntegral(d, to, ansi);
            };
        }
        BigDecimal bd = (BigDecimal) value;
        return switch (to) {
            case FLOAT -> {
                float f = bd.floatValue();
                if (ansi && Float.isInfinite(f)) {
                    throw new ArithmeticException("Out of the range of FLOAT: " + bd);
                }
                yield f;
            }
            case DOUBLE -> {
                double d = bd.doubleValue();
                if (ansi && Double.isInfinite(d)) {
                    throw new ArithmeticException("Out of the range of DOUBLE: " + bd);
                }
                yield d;
            }
            default -> {
                if (ansi) {
                    BigDecimal truncated = bd.setScale(0, RoundingMode.DOWN);
                    if (truncated.compareTo(BigDecimal.valueOf(Values.min(to))) < 0
                            || truncated.compareTo(BigDecimal.valueOf(Values.max(to))) > 0) {
                        throw new ArithmeticException("Out of the range of " + to + ": " + bd);
                    }
                }
                yield Values.integral(bd.longValue(), to);
            }
        };
    }

    /** A Java cast from {@code double}, or with ANSI a truncation that must be in range. */
    private static Object floatingToIntegral(double d, DataType to, boolean ansi) {
        if (ansi) {
            double truncated = d < 0 ? Math.ceil(d) : Math.floor(d);
            if (Double.isNaN(d) || truncated < Values.min(to) || truncated > Values.max(to)
                    || (to == DataType.LONG && truncated == 0x1p63)) {
                throw new ArithmeticException("Out of the range of " + to + ": " + d);
            }
        }
        return switch (to) {
            case BYTE -> (byte) (int) d;
            case SHORT -> (short) (int) d;
            case INT -> (int) d;
            case LONG -> (long) d;
            default -> throw new IllegalArgumentException("Not integral: " + to);
        };
    }
}
