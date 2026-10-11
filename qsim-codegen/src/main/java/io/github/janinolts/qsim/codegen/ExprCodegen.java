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
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.Expression.ArithmeticOp;
import io.github.janinolts.qsim.plan.Expression.ComparisonOp;
import io.github.janinolts.qsim.plan.TypeRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the statements that compute an expression into a null-pair, with
 * the semantics and the order of evaluation of the interpreter: operands from
 * left to right; {@code AND}, {@code OR}, {@code CASE}, {@code IF},
 * {@code COALESCE} and {@code IN} evaluate lazily, everything else evaluates
 * all its operands. A value is only used where its flag is false, so that a
 * {@code null} reference is never dereferenced.
 */
final class ExprCodegen {

    private final CodegenContext ctx;
    private final CodeWriter w;
    private final InputAccess input;
    private final boolean ansi;

    ExprCodegen(CodegenContext ctx, CodeWriter w, InputAccess input) {
        this.ctx = ctx;
        this.w = w;
        this.input = input;
        this.ansi = ctx.ansi();
    }

    /**
     * Writes the code of an expression and returns its null-pair; with the
     * reuse of subexpressions, the null-pair of the same expression over the
     * same input whose variables are still in scope, without new code.
     */
    ExprCode gen(Expression e) {
        if (e instanceof Expression.Literal || e instanceof Expression.Column) {
            return node(e);
        }
        ExprCode reused = ctx.reusable(w, input, e);
        if (reused != null) {
            return reused;
        }
        ExprCode code = node(e);
        ctx.computed(w, input, e, code);
        return code;
    }

    private ExprCode node(Expression e) {
        return switch (e) {
            case Expression.Literal l -> literal(l);
            case Expression.Column c -> input.column(c.index(), c.type(), w, ctx);
            case Expression.Arithmetic a -> arithmetic(a);
            case Expression.Negate n -> negate(n);
            case Expression.Bitwise b -> bitwise(b);
            case Expression.BitNot b -> bitNot(b);
            case Expression.Shift s -> shift(s);
            case Expression.Comparison c -> comparison(c);
            case Expression.And a -> andOr(a.left(), a.right(), true);
            case Expression.Or o -> andOr(o.left(), o.right(), false);
            case Expression.Not n -> not(n);
            case Expression.IsNull n -> isNull(n);
            case Expression.Between b -> between(b);
            case Expression.In in -> in(in);
            case Expression.Case c -> caseWhen(c);
            case Expression.If i -> ifThenElse(i);
            case Expression.Coalesce c -> coalesce(c);
            case Expression.Cast c -> new CastCodegen(ctx, w).cast(gen(c.operand()), c.target());
            case Expression.StringCall s -> stringCall(s);
            case Expression.Like l -> like(l);
            case Expression.MathCall m -> mathCall(m);
            case Expression.Feature f -> new FeatureCodegen(ctx, w).gen(f.feature(), gen(f.operand()));
        };
    }

    /** Declares a null-pair with the given initial flag and the default value. */
    private ExprCode declare(DataType type, String isNullInit) {
        ctx.tag("null-pair");
        String n = ctx.fresh("");
        String isNull = "isNull" + n;
        String value = "value" + n;
        w.line("boolean " + isNull + " = " + isNullInit + ";");
        w.line(JavaTypes.javaType(type) + " " + value + " = " + JavaTypes.defaultValue(type) + ";");
        return new ExprCode(isNull, value, type);
    }

    /** Copies a null-pair into another, converting the value to the type of the target. */
    private void assign(ExprCode target, ExprCode source) {
        w.line(target.isNull() + " = " + source.isNull() + ";");
        if (source.type() == target.type()) {
            w.line(target.value() + " = " + source.value() + ";");
        } else {
            w.open("if (!" + target.isNull() + ")");
            w.line(target.value() + " = " + convert(source, target.type()) + ";");
            w.close();
        }
    }

    /** Returns an expression that converts a value to a wider numeric type, like an implicit conversion. */
    static String convert(ExprCode code, DataType to) {
        DataType from = code.type();
        String v = code.value();
        if (from == to) {
            return v;
        }
        if (from.isIntegral()) {
            return switch (to) {
                case DECIMAL -> "BigDecimal.valueOf(" + v + ")";
                default -> "((" + JavaTypes.javaType(to) + ") " + v + ")";
            };
        }
        if (from == DataType.FLOAT && to == DataType.DOUBLE) {
            return "((double) " + v + ")";
        }
        if (from == DataType.DECIMAL && to == DataType.FLOAT) {
            return v + ".floatValue()";
        }
        if (from == DataType.DECIMAL && to == DataType.DOUBLE) {
            return v + ".doubleValue()";
        }
        throw new IllegalArgumentException("No implicit conversion from " + from + " to " + to);
    }

    private static String or(String a, String b) {
        if (a.equals("false")) {
            return b;
        }
        return b.equals("false") ? a : a + " || " + b;
    }

    private void throwArithmetic(String message) {
        w.line("throw new ArithmeticException(\"" + message + "\");");
    }

    private ExprCode literal(Expression.Literal l) {
        if (l.value() == null) {
            return declare(l.type(), "true");
        }
        String n = ctx.fresh("");
        String value = "value" + n;
        String reference = ctx.constantReference(l.value());
        String init = reference == null ? JavaTypes.literal(l.type(), l.value()) : JavaTypes.unbox(l.type(), reference);
        w.line(JavaTypes.javaType(l.type()) + " " + value + " = " + init + ";");
        return new ExprCode("false", value, l.type());
    }

    private ExprCode arithmetic(Expression.Arithmetic a) {
        ExprCode l = gen(a.left());
        ExprCode r = gen(a.right());
        DataType t = a.type();
        String lv = convert(l, t);
        String rv = convert(r, t);
        ExprCode out = declare(t, or(l.isNull(), r.isNull()));
        w.open("if (!" + out.isNull() + ")");
        boolean division = a.op() == ArithmeticOp.DIVIDE || a.op() == ArithmeticOp.REMAINDER;
        if (division) {
            w.open("if (" + isZero(rv, t) + ")");
            if (ansi) {
                throwArithmetic("Division by zero");
            } else {
                w.line(out.isNull() + " = true;");
            }
            w.reopen("else");
        }
        arithmeticValue(a.op(), t, lv, rv, out.value());
        if (division) {
            w.close();
        }
        w.close();
        return out;
    }

    private static String isZero(String v, DataType t) {
        return switch (t) {
            case FLOAT -> v + " == 0.0f";
            case DOUBLE -> v + " == 0.0";
            case DECIMAL -> v + ".signum() == 0";
            default -> v + " == 0";
        };
    }

    private static String symbol(ArithmeticOp op) {
        return switch (op) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
            case REMAINDER -> "%";
        };
    }

    private void arithmeticValue(ArithmeticOp op, DataType t, String lv, String rv, String target) {
        String jt = JavaTypes.javaType(t);
        String plain = lv + " " + symbol(op) + " " + rv;
        switch (t) {
            case BYTE, SHORT -> {
                ctx.tag("narrow");
                if (ansi) {
                    String result = ctx.fresh("result");
                    w.line("int " + result + " = " + plain + ";");
                    String box = JavaTypes.boxedType(t);
                    w.open("if (" + result + " < " + box + ".MIN_VALUE || " + result + " > " + box + ".MAX_VALUE)");
                    throwArithmetic(t + " overflow");
                    w.close();
                    w.line(target + " = (" + jt + ") " + result + ";");
                } else {
                    w.line(target + " = (" + jt + ") (" + plain + ");");
                }
            }
            case INT, LONG -> {
                String box = JavaTypes.boxedType(t);
                if (ansi && op == ArithmeticOp.ADD) {
                    w.line(target + " = Math.addExact(" + lv + ", " + rv + ");");
                } else if (ansi && op == ArithmeticOp.SUBTRACT) {
                    w.line(target + " = Math.subtractExact(" + lv + ", " + rv + ");");
                } else if (ansi && op == ArithmeticOp.MULTIPLY) {
                    w.line(target + " = Math.multiplyExact(" + lv + ", " + rv + ");");
                } else {
                    if (ansi && op == ArithmeticOp.DIVIDE) {
                        w.open("if (" + lv + " == " + box + ".MIN_VALUE && " + rv + " == -1)");
                        throwArithmetic(t + " overflow");
                        w.close();
                    }
                    w.line(target + " = " + plain + ";");
                }
            }
            case FLOAT, DOUBLE -> w.line(target + " = " + plain + ";");
            case DECIMAL -> {
                ctx.tag("decimal");
                String call = switch (op) {
                    case ADD -> lv + ".add(" + rv + ")";
                    case SUBTRACT -> lv + ".subtract(" + rv + ")";
                    case MULTIPLY -> lv + ".multiply(" + rv + ")";
                    case DIVIDE -> "DecimalFunctions.divide(" + lv + ", " + rv + ")";
                    case REMAINDER -> lv + ".remainder(" + rv + ")";
                };
                w.line(target + " = " + call + ";");
            }
            default -> throw new IllegalStateException("Not numeric: " + t);
        }
    }

    private ExprCode negate(Expression.Negate n) {
        ExprCode o = gen(n.operand());
        DataType t = n.type();
        ExprCode out = declare(t, o.isNull());
        String v = o.value();
        w.open("if (!" + out.isNull() + ")");
        switch (t) {
            case BYTE, SHORT -> {
                if (ansi) {
                    w.open("if (" + v + " == " + JavaTypes.boxedType(t) + ".MIN_VALUE)");
                    throwArithmetic(t + " overflow");
                    w.close();
                }
                w.line(out.value() + " = (" + JavaTypes.javaType(t) + ") (-" + v + ");");
            }
            case INT, LONG -> w.line(out.value() + " = " + (ansi ? "Math.negateExact(" + v + ")" : "-" + v) + ";");
            case FLOAT, DOUBLE -> w.line(out.value() + " = -" + v + ";");
            case DECIMAL -> w.line(out.value() + " = " + v + ".negate();");
            default -> throw new IllegalStateException("Not numeric: " + t);
        }
        w.close();
        return out;
    }

    /** Narrows an {@code int} expression to {@code byte} or {@code short}; other types unchanged. */
    private static String narrow(DataType t, String expression) {
        return t == DataType.BYTE || t == DataType.SHORT
                ? "(" + JavaTypes.javaType(t) + ") (" + expression + ")" : expression;
    }

    private ExprCode bitwise(Expression.Bitwise b) {
        ExprCode l = gen(b.left());
        ExprCode r = gen(b.right());
        DataType t = b.type();
        String symbol = switch (b.op()) {
            case AND -> "&";
            case OR -> "|";
            case XOR -> "^";
        };
        ExprCode out = declare(t, or(l.isNull(), r.isNull()));
        w.open("if (!" + out.isNull() + ")");
        w.line(out.value() + " = " + narrow(t, convert(l, t) + " " + symbol + " " + convert(r, t)) + ";");
        w.close();
        return out;
    }

    private ExprCode bitNot(Expression.BitNot b) {
        ExprCode o = gen(b.operand());
        ExprCode out = declare(b.type(), o.isNull());
        w.open("if (!" + out.isNull() + ")");
        w.line(out.value() + " = " + narrow(b.type(), "~" + o.value()) + ";");
        w.close();
        return out;
    }

    private ExprCode shift(Expression.Shift s) {
        ExprCode v = gen(s.value());
        ExprCode d = gen(s.distance());
        String symbol = switch (s.op()) {
            case LEFT -> "<<";
            case RIGHT -> ">>";
            case UNSIGNED_RIGHT -> ">>>";
        };
        ExprCode out = declare(s.type(), or(v.isNull(), d.isNull()));
        w.open("if (!" + out.isNull() + ")");
        w.line(out.value() + " = " + narrow(s.type(), v.value() + " " + symbol + " " + d.value()) + ";");
        w.close();
        return out;
    }

    private static String symbol(ComparisonOp op) {
        return switch (op) {
            case EQ -> "==";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
        };
    }

    /** Returns a boolean expression that compares two non-null values of the type {@code t}. */
    private static String compare(ComparisonOp op, String lv, String rv, DataType t) {
        return switch (t) {
            case BOOLEAN -> "Boolean.compare(" + lv + ", " + rv + ") " + symbol(op) + " 0";
            case STRING, DECIMAL -> lv + ".compareTo(" + rv + ") " + symbol(op) + " 0";
            default -> lv + " " + symbol(op) + " " + rv;
        };
    }

    private ExprCode comparison(Expression.Comparison c) {
        ExprCode l = gen(c.left());
        ExprCode r = gen(c.right());
        DataType t = TypeRules.common(l.type(), r.type());
        ExprCode out = declare(DataType.BOOLEAN, or(l.isNull(), r.isNull()));
        w.open("if (!" + out.isNull() + ")");
        w.line(out.value() + " = " + compare(c.op(), convert(l, t), convert(r, t), t) + ";");
        w.close();
        return out;
    }

    /**
     * {@code AND} if {@code isAnd}, else {@code OR}: the right operand is only
     * evaluated if the left one does not decide.
     */
    private ExprCode andOr(Expression left, Expression right, boolean isAnd) {
        String decisive = isAnd ? "false" : "true";
        String decides = isAnd ? "!" : "";
        ExprCode l = gen(left);
        ExprCode out = declare(DataType.BOOLEAN, "false");
        w.open("if (!" + l.isNull() + " && " + decides + l.value() + ")");
        w.line(out.value() + " = " + decisive + ";");
        w.reopen("else");
        ExprCode r = gen(right);
        w.open("if (!" + r.isNull() + " && " + decides + r.value() + ")");
        w.line(out.value() + " = " + decisive + ";");
        w.reopen("else if (!" + l.isNull() + " && !" + r.isNull() + ")");
        w.line(out.value() + " = " + !Boolean.parseBoolean(decisive) + ";");
        w.reopen("else");
        w.line(out.isNull() + " = true;");
        w.close();
        w.close();
        return out;
    }

    private ExprCode not(Expression.Not n) {
        ExprCode o = gen(n.operand());
        ExprCode out = declare(DataType.BOOLEAN, o.isNull());
        w.line(out.value() + " = !" + o.value() + ";");
        return out;
    }

    private ExprCode isNull(Expression.IsNull n) {
        ExprCode o = gen(n.operand());
        String n2 = ctx.fresh("");
        String value = "value" + n2;
        w.line("boolean " + value + " = " + (n.negated() ? "!" : "") + o.isNull() + ";");
        return new ExprCode("false", value, DataType.BOOLEAN);
    }

    private ExprCode between(Expression.Between b) {
        ExprCode v = gen(b.value());
        ExprCode lo = gen(b.low());
        ExprCode hi = gen(b.high());
        DataType t = TypeRules.common(List.of(v.type(), lo.type(), hi.type()));
        ExprCode ge = declare(DataType.BOOLEAN, or(v.isNull(), lo.isNull()));
        w.open("if (!" + ge.isNull() + ")");
        w.line(ge.value() + " = " + compare(ComparisonOp.GE, convert(v, t), convert(lo, t), t) + ";");
        w.close();
        ExprCode le = declare(DataType.BOOLEAN, or(v.isNull(), hi.isNull()));
        w.open("if (!" + le.isNull() + ")");
        w.line(le.value() + " = " + compare(ComparisonOp.LE, convert(v, t), convert(hi, t), t) + ";");
        w.close();
        ExprCode out = declare(DataType.BOOLEAN, "false");
        w.open("if ((!" + ge.isNull() + " && !" + ge.value() + ") || (!" + le.isNull() + " && !" + le.value() + "))");
        w.line(out.value() + " = false;");
        w.reopen("else if (" + ge.isNull() + " || " + le.isNull() + ")");
        w.line(out.isNull() + " = true;");
        w.reopen("else");
        w.line(out.value() + " = true;");
        w.close();
        return out;
    }

    private ExprCode in(Expression.In in) {
        ctx.tag("do-while-false");
        ExprCode v = gen(in.value());
        ExprCode out = declare(DataType.BOOLEAN, v.isNull());
        w.open("if (!" + out.isNull() + ")");
        String sawNull = ctx.fresh("sawNull");
        w.line("boolean " + sawNull + " = false;");
        w.open("do");
        for (Expression element : in.list()) {
            ExprCode e = gen(element);
            DataType t = TypeRules.common(v.type(), e.type());
            w.open("if (" + e.isNull() + ")");
            w.line(sawNull + " = true;");
            w.reopen("else if (" + compare(ComparisonOp.EQ, convert(v, t), convert(e, t), t) + ")");
            w.line(out.value() + " = true;");
            w.line("continue;");
            w.close();
        }
        w.close(" while (false);");
        w.line(out.isNull() + " = !" + out.value() + " && " + sawNull + ";");
        w.close();
        return out;
    }

    private ExprCode caseWhen(Expression.Case c) {
        ctx.tag("do-while-false");
        ctx.tag("branching");
        ExprCode out = declare(c.type(), "true");
        w.open("do");
        for (Expression.When when : c.whens()) {
            ExprCode condition = gen(when.condition());
            w.open("if (!" + condition.isNull() + " && " + condition.value() + ")");
            assign(out, gen(when.result()));
            w.line("continue;");
            w.close();
        }
        assign(out, gen(c.otherwise()));
        w.close(" while (false);");
        return out;
    }

    private ExprCode ifThenElse(Expression.If i) {
        ctx.tag("branching");
        ExprCode condition = gen(i.condition());
        ExprCode out = declare(i.type(), "true");
        w.open("if (!" + condition.isNull() + " && " + condition.value() + ")");
        assign(out, gen(i.whenTrue()));
        w.reopen("else");
        assign(out, gen(i.whenFalse()));
        w.close();
        return out;
    }

    private ExprCode coalesce(Expression.Coalesce c) {
        ctx.tag("do-while-false");
        ExprCode out = declare(c.type(), "true");
        w.open("do");
        for (Expression operand : c.operands()) {
            ExprCode o = gen(operand);
            w.open("if (!" + o.isNull() + ")");
            assign(out, o);
            w.line("continue;");
            w.close();
        }
        w.close(" while (false);");
        return out;
    }

    private ExprCode stringCall(Expression.StringCall s) {
        ctx.tag("string");
        List<ExprCode> args = new ArrayList<>();
        String anyNull = "false";
        for (Expression a : s.arguments()) {
            ExprCode code = gen(a);
            args.add(code);
            anyNull = or(anyNull, code.isNull());
        }
        ExprCode out = declare(s.type(), anyNull);
        if (s.function() == Expression.StringFunction.UPPER && ctx.config().inlineStrings()) {
            w.open("if (!" + out.isNull() + ")");
            inlineUpper(args.get(0).value(), out.value());
            w.close();
            return out;
        }
        String value = switch (s.function()) {
            case CONCAT -> {
                List<String> parts = new ArrayList<>();
                for (ExprCode a : args) {
                    parts.add(a.value());
                }
                yield args.size() == 1 ? parts.get(0) : String.join(" + ", parts);
            }
            case SUBSTRING -> "StringFunctions.substring(" + args.get(0).value() + ", " + args.get(1).value() + ", "
                    + args.get(2).value() + ")";
            case UPPER -> "StringFunctions.upper(" + args.get(0).value() + ")";
            case LENGTH -> args.get(0).value() + ".length()";
        };
        w.open("if (!" + out.isNull() + ")");
        w.line(out.value() + " = " + value + ";");
        w.close();
        return out;
    }

    private ExprCode like(Expression.Like l) {
        ExprCode s = gen(l.string());
        ExprCode p = gen(l.pattern());
        ExprCode out = declare(DataType.BOOLEAN, or(s.isNull(), p.isNull()));
        w.open("if (!" + out.isNull() + ")");
        if (ctx.config().inlineStrings()) {
            inlineLike(s.value(), p.value(), out.value());
        } else {
            w.line(out.value() + " = StringFunctions.like(" + s.value() + ", " + p.value() + ");");
        }
        w.close();
        return out;
    }

    /** Writes {@code StringFunctions.upper} as a loop over the {@code char}s of the string. */
    private void inlineUpper(String string, String target) {
        ctx.tag("string");
        String chars = ctx.fresh("chars");
        String i = ctx.fresh("i");
        w.line("char[] " + chars + " = " + string + ".toCharArray();");
        w.open("for (int " + i + " = 0; " + i + " < " + chars + ".length; " + i + "++)");
        w.line(chars + "[" + i + "] = Character.toUpperCase(" + chars + "[" + i + "]);");
        w.close();
        w.line(target + " = new String(" + chars + ");");
    }

    /** Writes {@code StringFunctions.like} as loops over the {@code char}s of the string and the pattern. */
    private void inlineLike(String string, String pattern, String target) {
        ctx.tag("string");
        String n = ctx.fresh("");
        String si = "si" + n;
        String pi = "pi" + n;
        String star = "starPattern" + n;
        String starString = "starString" + n;
        String pc = "pc" + n;
        w.line("int " + si + " = 0;");
        w.line("int " + pi + " = 0;");
        w.line("int " + star + " = -1;");
        w.line("int " + starString + " = 0;");
        w.line(target + " = true;");
        w.open("while (" + si + " < " + string + ".length())");
        w.open("if (" + pi + " < " + pattern + ".length())");
        w.line("char " + pc + " = " + pattern + ".charAt(" + pi + ");");
        w.open("if (" + pc + " == '%')");
        w.line(star + " = " + pi + "++;");
        w.line(starString + " = " + si + ";");
        w.line("continue;");
        w.close();
        w.open("if (" + pc + " == '_' || " + pc + " == " + string + ".charAt(" + si + "))");
        w.line(si + "++;");
        w.line(pi + "++;");
        w.line("continue;");
        w.close();
        w.close();
        w.open("if (" + star + " < 0)");
        w.line(target + " = false;");
        w.line("break;");
        w.close();
        w.line(pi + " = " + star + " + 1;");
        w.line(si + " = ++" + starString + ";");
        w.close();
        w.open("if (" + target + ")");
        w.open("while (" + pi + " < " + pattern + ".length() && " + pattern + ".charAt(" + pi + ") == '%')");
        w.line(pi + "++;");
        w.close();
        w.line(target + " = " + pi + " == " + pattern + ".length();");
        w.close();
    }

    private ExprCode mathCall(Expression.MathCall m) {
        List<ExprCode> args = new ArrayList<>();
        String anyNull = "false";
        for (Expression a : m.arguments()) {
            ExprCode code = gen(a);
            args.add(code);
            anyNull = or(anyNull, code.isNull());
        }
        DataType t = m.type();
        ExprCode out = declare(t, anyNull);
        String v = args.get(0).value();
        w.open("if (!" + out.isNull() + ")");
        switch (m.function()) {
            case ABS -> {
                switch (t) {
                    case BYTE, SHORT, INT, LONG -> {
                        if (ansi) {
                            w.open("if (" + v + " == " + JavaTypes.boxedType(t) + ".MIN_VALUE)");
                            throwArithmetic(t + " overflow");
                            w.close();
                        }
                        w.line(out.value() + " = " + narrow(t, "Math.abs(" + v + ")") + ";");
                    }
                    case DECIMAL -> w.line(out.value() + " = " + v + ".abs();");
                    default -> w.line(out.value() + " = Math.abs(" + v + ");");
                }
            }
            case SQRT -> w.line(out.value() + " = Math.sqrt(" + v + ");");
            case FLOOR -> w.line(out.value() + " = Math.floor(" + v + ");");
            case CEIL -> w.line(out.value() + " = Math.ceil(" + v + ");");
            case POWER -> w.line(out.value() + " = Math.pow(" + v + ", " + args.get(1).value() + ");");
        }
        w.close();
        return out;
    }
}
