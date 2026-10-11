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

/**
 * The types of the simulated engine. Every type is nullable. The interpreter
 * holds a value as an instance of {@link #javaClass()}, or {@code null}.
 */
public enum DataType {

    BOOLEAN(Boolean.class, "boolean"),
    BYTE(Byte.class, "byte"),
    SHORT(Short.class, "short"),
    INT(Integer.class, "int"),
    LONG(Long.class, "long"),
    FLOAT(Float.class, "float"),
    DOUBLE(Double.class, "double"),
    STRING(String.class, "java.lang.String"),
    /** A {@link BigDecimal} of any precision and scale. */
    DECIMAL(BigDecimal.class, "java.math.BigDecimal"),
    /** The number of days since 1970-01-01, as an {@code int}. */
    DATE(Integer.class, "int");

    private final Class<?> javaClass;
    private final String javaType;

    DataType(Class<?> javaClass, String javaType) {
        this.javaClass = javaClass;
        this.javaType = javaType;
    }

    /** Returns the class of a value of this type in the interpreter. */
    public Class<?> javaClass() {
        return javaClass;
    }

    /** Returns the Java type that generated code uses for a value, e.g. {@code int} for {@code DATE}. */
    public String javaType() {
        return javaType;
    }

    /** {@code BYTE}, {@code SHORT}, {@code INT} or {@code LONG}. */
    public boolean isIntegral() {
        return this == BYTE || this == SHORT || this == INT || this == LONG;
    }

    /** {@code FLOAT} or {@code DOUBLE}. */
    public boolean isFloatingPoint() {
        return this == FLOAT || this == DOUBLE;
    }

    /** An integral, floating-point or decimal type. */
    public boolean isNumeric() {
        return isIntegral() || isFloatingPoint() || this == DECIMAL;
    }

    /** Tells whether a value, which may be {@code null}, is a valid value of this type. */
    public boolean accepts(Object value) {
        return value == null || javaClass.isInstance(value);
    }
}
