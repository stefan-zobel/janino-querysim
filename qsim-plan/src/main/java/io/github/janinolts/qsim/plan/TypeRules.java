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

import java.util.List;

/**
 * The typing rules of the simulated engine.
 * <p>
 * Numeric operands are converted to a common type, the wider of the two in
 * the order {@code BYTE < SHORT < INT < LONG < DECIMAL < FLOAT < DOUBLE}, with
 * one exception: {@code DECIMAL} and {@code FLOAT} give {@code DOUBLE}. Unlike
 * in Java, narrow types are not promoted to {@code int}: {@code BYTE + BYTE} is
 * a {@code BYTE}, as in Apache Spark. The generated code therefore narrows the
 * results of arithmetic, which is where many fixes of Janino since 3.1.12 lie.
 */
public final class TypeRules {

    private static final List<DataType> NUMERIC_ORDER = List.of(
            DataType.BYTE, DataType.SHORT, DataType.INT, DataType.LONG,
            DataType.DECIMAL, DataType.FLOAT, DataType.DOUBLE);

    private TypeRules() {
    }

    /**
     * Returns the common type of two numeric types.
     *
     * @throws IllegalArgumentException if a type is not numeric
     */
    public static DataType numeric(DataType a, DataType b) {
        requireNumeric(a);
        requireNumeric(b);
        if ((a == DataType.DECIMAL && b == DataType.FLOAT) || (a == DataType.FLOAT && b == DataType.DECIMAL)) {
            return DataType.DOUBLE;
        }
        return NUMERIC_ORDER.indexOf(a) >= NUMERIC_ORDER.indexOf(b) ? a : b;
    }

    /**
     * Returns the common type of two integral types.
     *
     * @throws IllegalArgumentException if a type is not integral
     */
    public static DataType integral(DataType a, DataType b) {
        if (!a.isIntegral() || !b.isIntegral()) {
            throw new IllegalArgumentException("Not integral: " + a + ", " + b);
        }
        return numeric(a, b);
    }

    /**
     * Returns the type in which two values are compared or united, e.g. in a
     * comparison, a {@code CASE} or a {@code COALESCE}: the common numeric type,
     * or the type itself if both are the same.
     *
     * @throws IllegalArgumentException if the types are incompatible
     */
    public static DataType common(DataType a, DataType b) {
        if (a == b) {
            return a;
        }
        if (a.isNumeric() && b.isNumeric()) {
            return numeric(a, b);
        }
        throw new IllegalArgumentException("Incompatible types: " + a + ", " + b);
    }

    /** Returns the common type of one or more types, see {@link #common(DataType, DataType)}. */
    public static DataType common(List<DataType> types) {
        if (types.isEmpty()) {
            throw new IllegalArgumentException("No types");
        }
        DataType result = types.get(0);
        for (DataType t : types.subList(1, types.size())) {
            result = common(result, t);
        }
        return result;
    }

    static void requireNumeric(DataType type) {
        if (!type.isNumeric()) {
            throw new IllegalArgumentException("Not numeric: " + type);
        }
    }

    static void require(DataType expected, DataType actual, String what) {
        if (expected != actual) {
            throw new IllegalArgumentException(what + " must be " + expected + ", is " + actual);
        }
    }
}
