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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The conversions between strings and the other types of the simulated
 * engine, for {@code CAST}. Parsing trims the string first ({@link String#trim()})
 * and throws on invalid input; in the non-ANSI mode, the caller turns the
 * exception into {@code NULL}. A {@code DATE} is the number of days since
 * 1970-01-01.
 */
public final class Conversions {

    private Conversions() {
    }

    /** @throws NumberFormatException if the string is not a {@code byte} */
    public static byte parseByte(String s) {
        return Byte.parseByte(s.trim());
    }

    /** @throws NumberFormatException if the string is not a {@code short} */
    public static short parseShort(String s) {
        return Short.parseShort(s.trim());
    }

    /** @throws NumberFormatException if the string is not an {@code int} */
    public static int parseInt(String s) {
        return Integer.parseInt(s.trim());
    }

    /** @throws NumberFormatException if the string is not a {@code long} */
    public static long parseLong(String s) {
        return Long.parseLong(s.trim());
    }

    /** Parses like {@link Float#parseFloat}, e.g. also {@code NaN} and {@code -Infinity}. */
    public static float parseFloat(String s) {
        return Float.parseFloat(s.trim());
    }

    /** Parses like {@link Double#parseDouble}, e.g. also {@code NaN} and {@code -Infinity}. */
    public static double parseDouble(String s) {
        return Double.parseDouble(s.trim());
    }

    /** @throws NumberFormatException if the string is not a decimal number */
    public static BigDecimal parseDecimal(String s) {
        return new BigDecimal(s.trim());
    }

    /**
     * Parses {@code true} or {@code false}, ignoring case.
     *
     * @throws IllegalArgumentException for any other string
     */
    public static boolean parseBoolean(String s) {
        String t = s.trim();
        if (t.equalsIgnoreCase("true")) {
            return true;
        }
        if (t.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Not a boolean: \"" + s + "\"");
    }

    /**
     * Parses an ISO date, e.g. {@code 2026-10-11}.
     *
     * @throws java.time.format.DateTimeParseException if the string is not an
     *         ISO date
     * @throws ArithmeticException if the date is out of the range of
     *         {@code int} days
     */
    public static int parseDate(String s) {
        return Math.toIntExact(LocalDate.parse(s.trim()).toEpochDay());
    }

    /** Formats a date as an ISO date, e.g. {@code 2026-10-11}. */
    public static String formatDate(int days) {
        return LocalDate.ofEpochDay(days).toString();
    }

    /** Formats a decimal without an exponent, e.g. {@code 1000} for {@code 1E+3}. */
    public static String formatDecimal(BigDecimal d) {
        return d.toPlainString();
    }
}
