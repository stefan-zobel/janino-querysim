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

/**
 * The equality of grouping and join keys. A key value is first brought into
 * its canonical form: {@code -0.0} becomes {@code 0.0}, every NaN the one NaN
 * of {@link Double#NaN} or {@link Float#NaN}, and a decimal loses its trailing
 * zeros, so that {@code 1.0} and {@code 1.00} are one key. Canonical values are
 * equal if {@link Object#equals} says so. The output of a group shows the
 * canonical values of its key.
 */
public final class Keys {

    private Keys() {
    }

    /** Returns the canonical form of a boxed key value; {@code null} stays {@code null}. */
    public static Object canonical(Object value) {
        if (value instanceof Double) {
            return Double.valueOf(canonical(((Double) value).doubleValue()));
        }
        if (value instanceof Float) {
            return Float.valueOf(canonical(((Float) value).floatValue()));
        }
        if (value instanceof BigDecimal) {
            return canonical((BigDecimal) value);
        }
        return value;
    }

    /** Returns the canonical form of a {@code double} key value. */
    public static double canonical(double value) {
        if (value == 0.0) {
            return 0.0;
        }
        return Double.isNaN(value) ? Double.NaN : value;
    }

    /** Returns the canonical form of a {@code float} key value. */
    public static float canonical(float value) {
        if (value == 0.0f) {
            return 0.0f;
        }
        return Float.isNaN(value) ? Float.NaN : value;
    }

    /** Returns the canonical form of a decimal key value, or {@code null}. */
    public static BigDecimal canonical(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    /** Tells whether two canonical {@code double} key values are equal. */
    public static boolean equal(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    /** Tells whether two canonical {@code float} key values are equal. */
    public static boolean equal(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    /** Tells whether two canonical reference key values are equal; both may be {@code null}. */
    public static boolean equalObjects(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Returns the hash code of a canonical reference key value, 0 for {@code null}. */
    public static int hash(Object value) {
        return value == null ? 0 : value.hashCode();
    }
}
