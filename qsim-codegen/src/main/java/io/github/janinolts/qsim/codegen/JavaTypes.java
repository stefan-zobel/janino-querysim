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

package io.github.janinolts.qsim.codegen;

import io.github.janinolts.qsim.plan.DataType;

/**
 * The Java side of the types of the simulated engine, as generated code
 * spells it. {@code BigDecimal} is used by its simple name: every unit imports
 * it.
 */
final class JavaTypes {

    private JavaTypes() {
    }

    /** Returns the Java type of a value, e.g. {@code int} for {@code DATE}. */
    static String javaType(DataType type) {
        return switch (type) {
            case BOOLEAN -> "boolean";
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT, DATE -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case STRING -> "String";
            case DECIMAL -> "BigDecimal";
        };
    }

    /** Returns the wrapper class of a value, e.g. {@code Integer}. */
    static String boxedType(DataType type) {
        return switch (type) {
            case BOOLEAN -> "Boolean";
            case BYTE -> "Byte";
            case SHORT -> "Short";
            case INT, DATE -> "Integer";
            case LONG -> "Long";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            case STRING -> "String";
            case DECIMAL -> "BigDecimal";
        };
    }

    /** Tells whether the Java type of a value is a reference type. */
    static boolean isReference(DataType type) {
        return type == DataType.STRING || type == DataType.DECIMAL;
    }

    /** Returns the value of the variable that holds {@code NULL}: -1, as in Spark, or {@code null}. */
    static String defaultValue(DataType type) {
        return switch (type) {
            case BOOLEAN -> "false";
            case BYTE -> "((byte) -1)";
            case SHORT -> "((short) -1)";
            case INT, DATE -> "-1";
            case LONG -> "-1L";
            case FLOAT -> "-1.0f";
            case DOUBLE -> "-1.0";
            case STRING, DECIMAL -> "null";
        };
    }

    /** Returns an expression that boxes a value, e.g. {@code Integer.valueOf(v)}. */
    static String box(DataType type, String value) {
        return isReference(type) ? value : boxedType(type) + ".valueOf(" + value + ")";
    }

    /** Returns an expression that unboxes an {@code Object}, e.g. {@code ((Integer) o).intValue()}. */
    static String unbox(DataType type, String object) {
        if (isReference(type)) {
            return "((" + javaType(type) + ") " + object + ")";
        }
        return "((" + boxedType(type) + ") " + object + ")." + javaType(type) + "Value()";
    }

    /** Returns the element type of the value array of a column in a {@code ColumnBatch}. */
    static String arrayType(DataType type) {
        return javaType(type) + "[]";
    }

    /** Returns a Java literal for a non-null value. */
    static String literal(DataType type, Object value) {
        return switch (type) {
            case BOOLEAN -> value.toString();
            case BYTE -> "((byte) " + intLiteral((Byte) value) + ")";
            case SHORT -> "((short) " + intLiteral((Short) value) + ")";
            case INT, DATE -> intLiteral((Integer) value);
            case LONG -> longLiteral((Long) value);
            case FLOAT -> floatLiteral((Float) value);
            case DOUBLE -> doubleLiteral((Double) value);
            case STRING -> stringLiteral((String) value);
            case DECIMAL -> "new BigDecimal(\"" + value + "\")";
        };
    }

    private static String intLiteral(int v) {
        return v == Integer.MIN_VALUE ? "Integer.MIN_VALUE" : v < 0 ? "(" + v + ")" : Integer.toString(v);
    }

    private static String longLiteral(long v) {
        return v == Long.MIN_VALUE ? "Long.MIN_VALUE" : v < 0 ? "(" + v + "L)" : v + "L";
    }

    private static String floatLiteral(float v) {
        if (Float.isNaN(v)) {
            return "Float.NaN";
        }
        if (Float.isInfinite(v)) {
            return v > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY";
        }
        String s = Float.toString(v) + "f";
        return s.startsWith("-") ? "(" + s + ")" : s;
    }

    private static String doubleLiteral(double v) {
        if (Double.isNaN(v)) {
            return "Double.NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY";
        }
        String s = Double.toString(v);
        return s.startsWith("-") ? "(" + s + ")" : s;
    }

    /** Returns a string literal with every character outside printable ASCII escaped. */
    static String stringLiteral(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c >= 0x20 && c < 0x7f) {
                sb.append(c);
            } else if (c < 0x80) {
                // An octal escape: a Unicode escape of a line terminator would end the literal.
                sb.append(String.format("\\%03o", (int) c));
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.append('"').toString();
    }
}
