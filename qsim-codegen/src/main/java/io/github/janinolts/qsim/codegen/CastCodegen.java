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
 * Generates the code of {@code CAST}, with the semantics of the interpreter
 * (see its class {@code Casts}): without ANSI, a string that cannot be parsed
 * gives {@code NULL} through a {@code try}/{@code catch}; with ANSI, the
 * exception propagates, and out-of-range values throw an
 * {@link ArithmeticException}.
 */
final class CastCodegen {

    private final CodegenContext ctx;
    private final CodeWriter w;
    private final boolean ansi;

    CastCodegen(CodegenContext ctx, CodeWriter w) {
        this.ctx = ctx;
        this.w = w;
        this.ansi = ctx.ansi();
    }

    /** Writes the conversion of a null-pair and returns the converted one. */
    ExprCode cast(ExprCode operand, DataType to) {
        DataType from = operand.type();
        if (from == to) {
            return operand;
        }
        ctx.tag("cast");
        ctx.tag("null-pair");
        String n = ctx.fresh("");
        ExprCode out = new ExprCode("isNull" + n, "value" + n, to);
        w.line("boolean " + out.isNull() + " = " + operand.isNull() + ";");
        w.line(JavaTypes.javaType(to) + " " + out.value() + " = " + JavaTypes.defaultValue(to) + ";");
        w.open("if (!" + out.isNull() + ")");
        String v = operand.value();
        if (to == DataType.STRING) {
            w.line(out.value() + " = " + format(v, from) + ";");
        } else if (from == DataType.STRING) {
            parse(v, to, out);
        } else if (from == DataType.BOOLEAN) {
            w.line(out.value() + " = " + fromBoolean(v, to) + ";");
        } else if (to == DataType.BOOLEAN) {
            w.line(out.value() + " = " + (from == DataType.DECIMAL ? v + ".signum() != 0" : v + " != 0") + ";");
        } else {
            numeric(v, numericType(from), numericType(to), out);
        }
        w.close();
        return out;
    }

    private static DataType numericType(DataType type) {
        return type == DataType.DATE ? DataType.INT : type;
    }

    private static String format(String v, DataType from) {
        return switch (from) {
            case DECIMAL -> "Conversions.formatDecimal(" + v + ")";
            case DATE -> "Conversions.formatDate(" + v + ")";
            default -> "String.valueOf(" + v + ")";
        };
    }

    private void parse(String v, DataType to, ExprCode out) {
        String method = switch (to) {
            case BOOLEAN -> "parseBoolean";
            case BYTE -> "parseByte";
            case SHORT -> "parseShort";
            case INT -> "parseInt";
            case LONG -> "parseLong";
            case FLOAT -> "parseFloat";
            case DOUBLE -> "parseDouble";
            case DECIMAL -> "parseDecimal";
            case DATE -> "parseDate";
            case STRING -> throw new IllegalStateException();
        };
        String assignment = out.value() + " = Conversions." + method + "(" + v + ");";
        if (ansi) {
            w.line(assignment);
            return;
        }
        ctx.tag("try");
        String e = ctx.fresh("e");
        w.open("try");
        w.line(assignment);
        w.reopen("catch (RuntimeException " + e + ")");
        w.line(out.isNull() + " = true;");
        w.close();
    }

    private static String fromBoolean(String v, DataType to) {
        return switch (to) {
            case BYTE -> "(byte) (" + v + " ? 1 : 0)";
            case SHORT -> "(short) (" + v + " ? 1 : 0)";
            case INT, DATE -> "(" + v + " ? 1 : 0)";
            case LONG -> "(" + v + " ? 1L : 0L)";
            case FLOAT -> "(" + v + " ? 1.0f : 0.0f)";
            case DOUBLE -> "(" + v + " ? 1.0 : 0.0)";
            case DECIMAL -> "(" + v + " ? BigDecimal.ONE : BigDecimal.ZERO)";
            default -> throw new IllegalStateException("Not numeric: " + to);
        };
    }

    private void throwArithmetic(String message) {
        w.line("throw new ArithmeticException(\"" + message + "\");");
    }

    /** Converts between numeric types; {@code DATE} is passed as {@code INT}. */
    private void numeric(String v, DataType from, DataType to, ExprCode out) {
        String target = out.value();
        if (from.isIntegral()) {
            switch (to) {
                case FLOAT, DOUBLE -> w.line(target + " = (" + JavaTypes.javaType(to) + ") " + v + ";");
                case DECIMAL -> w.line(target + " = BigDecimal.valueOf(" + v + ");");
                default -> {
                    if (ansi && width(to) < width(from)) {
                        String box = JavaTypes.boxedType(to);
                        w.open("if (" + v + " < " + box + ".MIN_VALUE || " + v + " > " + box + ".MAX_VALUE)");
                        throwArithmetic("Out of the range of " + to);
                        w.close();
                    }
                    w.line(target + " = (" + JavaTypes.javaType(to) + ") " + v + ";");
                }
            }
            return;
        }
        if (from.isFloatingPoint()) {
            switch (to) {
                case FLOAT -> {
                    if (ansi) {
                        w.line(target + " = (float) " + v + ";");
                        w.open("if (Float.isInfinite(" + target + ") && !Double.isInfinite(" + v + "))");
                        throwArithmetic("Out of the range of FLOAT");
                        w.close();
                    } else {
                        w.line(target + " = (float) " + v + ";");
                    }
                }
                case DOUBLE -> w.line(target + " = (double) " + v + ";");
                case DECIMAL -> {
                    w.open("if (Double.isNaN(" + v + ") || Double.isInfinite(" + v + "))");
                    if (ansi) {
                        throwArithmetic("Not a DECIMAL");
                    } else {
                        w.line(out.isNull() + " = true;");
                    }
                    w.reopen("else");
                    w.line(target + " = " + (from == DataType.FLOAT ? "new BigDecimal(Float.toString(" + v + "))"
                            : "BigDecimal.valueOf(" + v + ")") + ";");
                    w.close();
                }
                default -> {
                    if (ansi) {
                        String truncated = ctx.fresh("truncated");
                        w.line("double " + truncated + " = " + v + " < 0 ? Math.ceil(" + v + ") : Math.floor(" + v + ");");
                        String bound = to == DataType.LONG
                                ? truncated + " >= 9.223372036854775808E18"
                                : truncated + " > " + JavaTypes.boxedType(to) + ".MAX_VALUE";
                        w.open("if (Double.isNaN(" + v + ") || " + truncated + " < " + JavaTypes.boxedType(to)
                                + ".MIN_VALUE || " + bound + ")");
                        throwArithmetic("Out of the range of " + to);
                        w.close();
                    }
                    String cast = switch (to) {
                        case BYTE -> "(byte) (int) " + v;
                        case SHORT -> "(short) (int) " + v;
                        case INT -> "(int) " + v;
                        case LONG -> "(long) " + v;
                        default -> throw new IllegalStateException("Not integral: " + to);
                    };
                    w.line(target + " = " + cast + ";");
                }
            }
            return;
        }
        // DECIMAL
        switch (to) {
            case FLOAT, DOUBLE -> {
                w.line(target + " = " + v + "." + JavaTypes.javaType(to) + "Value();");
                if (ansi) {
                    w.open("if (" + JavaTypes.boxedType(to) + ".isInfinite(" + target + "))");
                    throwArithmetic("Out of the range of " + to);
                    w.close();
                }
            }
            default -> {
                if (ansi) {
                    String truncated = ctx.fresh("truncated");
                    String box = JavaTypes.boxedType(to);
                    w.line("BigDecimal " + truncated + " = " + v + ".setScale(0, RoundingMode.DOWN);");
                    w.open("if (" + truncated + ".compareTo(BigDecimal.valueOf(" + box + ".MIN_VALUE)) < 0 || "
                            + truncated + ".compareTo(BigDecimal.valueOf(" + box + ".MAX_VALUE)) > 0)");
                    throwArithmetic("Out of the range of " + to);
                    w.close();
                }
                String longValue = v + ".longValue()";
                w.line(target + " = " + (to == DataType.LONG ? longValue
                        : "(" + JavaTypes.javaType(to) + ") " + longValue) + ";");
            }
        }
    }

    /** The width in bytes of an integral type. */
    private static int width(DataType type) {
        return switch (type) {
            case BYTE -> 1;
            case SHORT -> 2;
            case INT -> 4;
            case LONG -> 8;
            default -> throw new IllegalArgumentException("Not integral: " + type);
        };
    }
}
