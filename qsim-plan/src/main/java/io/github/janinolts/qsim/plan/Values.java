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

/**
 * Helpers for the boxed values of integral types.
 */
final class Values {

    private Values() {
    }

    /** Returns the smallest value of an integral type ({@code DATE} as {@code INT}). */
    static long min(DataType type) {
        return switch (type) {
            case BYTE -> Byte.MIN_VALUE;
            case SHORT -> Short.MIN_VALUE;
            case INT, DATE -> Integer.MIN_VALUE;
            case LONG -> Long.MIN_VALUE;
            default -> throw new IllegalArgumentException("Not integral: " + type);
        };
    }

    /** Returns the largest value of an integral type ({@code DATE} as {@code INT}). */
    static long max(DataType type) {
        return switch (type) {
            case BYTE -> Byte.MAX_VALUE;
            case SHORT -> Short.MAX_VALUE;
            case INT, DATE -> Integer.MAX_VALUE;
            case LONG -> Long.MAX_VALUE;
            default -> throw new IllegalArgumentException("Not integral: " + type);
        };
    }

    /**
     * Returns the value unchanged if it is in the range of the type.
     *
     * @throws ArithmeticException otherwise
     */
    static long checkRange(long value, DataType type) {
        if (value < min(type) || value > max(type)) {
            throw new ArithmeticException("Out of the range of " + type + ": " + value);
        }
        return value;
    }

    /** Narrows a value to an integral type as a Java cast does, and boxes it. */
    static Object integral(long value, DataType type) {
        return switch (type) {
            case BYTE -> (byte) value;
            case SHORT -> (short) value;
            case INT, DATE -> (int) value;
            case LONG -> value;
            default -> throw new IllegalArgumentException("Not integral: " + type);
        };
    }
}
