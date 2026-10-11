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

import io.github.janinolts.qsim.plan.Expression.ArithmeticOp;
import io.github.janinolts.qsim.plan.Expression.ComparisonOp;
import io.github.janinolts.qsim.runtime.DecimalFunctions;
import io.github.janinolts.qsim.runtime.StringFunctions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The reference interpreter: a direct tree interpreter over boxed values. It
 * defines the semantics of the simulated engine; generated code must compute
 * the same results. It shares only the helper functions of
 * {@code qsim-runtime} with the generated code, no code of the generator.
 *
 * <h2>Semantics</h2>
 * <ul>
 * <li>{@code NULL} propagates through every operator and function, except
 * {@code AND}, {@code OR}, {@code IS NULL}, {@code IN}, {@code CASE},
 * {@code IF} and {@code COALESCE}.</li>
 * <li>Integral arithmetic is computed in the result type: without ANSI it wraps
 * around as in Java; with ANSI, a result out of range throws an
 * {@link ArithmeticException}. Division of integral types truncates;
 * {@code MIN / -1} overflows.</li>
 * <li>Division and remainder by zero give {@code NULL} without ANSI and throw an
 * {@link ArithmeticException} with ANSI, for every numeric type, also
 * {@code FLOAT} and {@code DOUBLE} (whose divisor {@code -0.0} is zero, too).</li>
 * <li>{@code FLOAT} and {@code DOUBLE} compare as Java's primitive operators:
 * every comparison with {@code NaN} is false (also {@code NaN = NaN}), and
 * {@code -0.0 = 0.0}. {@code DECIMAL} compares by value, ignoring the scale;
 * {@code STRING} by {@link String#compareTo}; {@code BOOLEAN} with
 * {@code false < true}.</li>
 * <li>Shifts and bitwise operators work on the value promoted as in Java
 * ({@code int} for {@code BYTE}, {@code SHORT}, {@code INT}) and narrow the
 * result to the type of the expression; they never throw.</li>
 * <li>{@code CAST}: see {@link Casts}.</li>
 * <li>A language feature: see {@link LanguageFeature#evaluate}, as javac or
 * as Janino compiles it.</li>
 * </ul>
 *
 * <h2>Order of evaluation</h2>
 * Operands are evaluated from left to right, which matters for which exception
 * a row throws. {@code AND} does not evaluate its right operand if the left one
 * is false, {@code OR} if the left one is true. {@code CASE}, {@code IF} and
 * {@code COALESCE} evaluate only what they need: the conditions in order up to
 * the first true one, then only the selected result; the operands of
 * {@code COALESCE} up to the first one that is not {@code NULL}. {@code IN}
 * evaluates the value and, unless it is {@code NULL}, the elements in order up
 * to the first one that equals it. Every other operator evaluates all its
 * operands, also when one of them is {@code NULL}. Rows flow
 * through a pipeline one at a time, so the first row that throws in any
 * operator ends the execution. The order in which the operators of a plan run
 * is described in {@link Executor}.
 */
public final class Interpreter {

    private final boolean ansi;
    private final LanguageFeature.Semantics semantics;

    /**
     * Creates an interpreter that computes language features as javac
     * compiles them.
     *
     * @param ansi whether overflows, division by zero and invalid casts throw
     *        instead of wrapping around or giving {@code NULL}
     */
    public Interpreter(boolean ansi) {
        this(ansi, LanguageFeature.Semantics.JAVAC);
    }

    /**
     * Creates an interpreter.
     *
     * @param ansi whether overflows, division by zero and invalid casts throw
     *        instead of wrapping around or giving {@code NULL}
     * @param semantics whose result a {@link Expression.Feature} has
     */
    public Interpreter(boolean ansi, LanguageFeature.Semantics semantics) {
        this.ansi = ansi;
        this.semantics = semantics;
    }

    /**
     * Executes a plan.
     *
     * @param plan the plan
     * @param tables the tables, by name
     * @return the output rows
     * @throws RowException if a row throws; it carries the source of the
     *         pipeline and the index of the row in it
     */
    public List<Object[]> execute(PlanNode plan, Map<String, Table> tables) {
        return new Executor(this, ansi, tables, new PlanIds(plan)).run(plan);
    }

    /**
     * Evaluates an expression over a row.
     *
     * @return the value, boxed, or {@code null} for {@code NULL}
     * @throws RuntimeException what the expression throws, e.g. an
     *         {@link ArithmeticException} with ANSI
     */
    public Object evaluate(Expression e, Object[] row) {
        return switch (e) {
            case Expression.Literal l -> l.value();
            case Expression.Column c -> row[c.index()];
            case Expression.Arithmetic a -> arithmetic(a, row);
            case Expression.Negate n -> negate(n.type(), evaluate(n.operand(), row));
            case Expression.Bitwise b -> bitwise(b, row);
            case Expression.BitNot b -> {
                Object v = evaluate(b.operand(), row);
                yield v == null ? null : Values.integral(~((Number) v).longValue(), b.type());
            }
            case Expression.Shift s -> shift(s, row);
            case Expression.Comparison c -> {
                DataType t = TypeRules.common(c.left().type(), c.right().type());
                Object l = Casts.widen(evaluate(c.left(), row), c.left().type(), t);
                Object r = Casts.widen(evaluate(c.right(), row), c.right().type(), t);
                yield compare(c.op(), l, r, t);
            }
            case Expression.And a -> {
                Boolean l = (Boolean) evaluate(a.left(), row);
                if (Boolean.FALSE.equals(l)) {
                    yield false;
                }
                Boolean r = (Boolean) evaluate(a.right(), row);
                yield Boolean.FALSE.equals(r) ? Boolean.FALSE : l == null || r == null ? null : Boolean.TRUE;
            }
            case Expression.Or o -> {
                Boolean l = (Boolean) evaluate(o.left(), row);
                if (Boolean.TRUE.equals(l)) {
                    yield true;
                }
                Boolean r = (Boolean) evaluate(o.right(), row);
                yield Boolean.TRUE.equals(r) ? Boolean.TRUE : l == null || r == null ? null : Boolean.FALSE;
            }
            case Expression.Not n -> {
                Boolean v = (Boolean) evaluate(n.operand(), row);
                yield v == null ? null : !v;
            }
            case Expression.IsNull n -> (evaluate(n.operand(), row) == null) != n.negated();
            case Expression.Between b -> between(b, row);
            case Expression.In in -> in(in, row);
            case Expression.Case c -> {
                DataType t = c.type();
                for (Expression.When w : c.whens()) {
                    if (Boolean.TRUE.equals(evaluate(w.condition(), row))) {
                        yield Casts.widen(evaluate(w.result(), row), w.result().type(), t);
                    }
                }
                yield Casts.widen(evaluate(c.otherwise(), row), c.otherwise().type(), t);
            }
            case Expression.If i -> {
                Expression selected = Boolean.TRUE.equals(evaluate(i.condition(), row)) ? i.whenTrue() : i.whenFalse();
                yield Casts.widen(evaluate(selected, row), selected.type(), i.type());
            }
            case Expression.Coalesce c -> {
                for (Expression operand : c.operands()) {
                    Object v = evaluate(operand, row);
                    if (v != null) {
                        yield Casts.widen(v, operand.type(), c.type());
                    }
                }
                yield null;
            }
            case Expression.Cast c -> Casts.cast(evaluate(c.operand(), row), c.operand().type(), c.target(), ansi);
            case Expression.StringCall s -> stringCall(s, row);
            case Expression.Like l -> {
                String s = (String) evaluate(l.string(), row);
                String p = (String) evaluate(l.pattern(), row);
                yield s == null || p == null ? null : StringFunctions.like(s, p);
            }
            case Expression.MathCall m -> mathCall(m, row);
            case Expression.Feature f -> f.feature().evaluate(evaluate(f.operand(), row), semantics);
        };
    }

    private Object arithmetic(Expression.Arithmetic a, Object[] row) {
        DataType t = a.type();
        Object l = Casts.widen(evaluate(a.left(), row), a.left().type(), t);
        Object r = Casts.widen(evaluate(a.right(), row), a.right().type(), t);
        if (l == null || r == null) {
            return null;
        }
        boolean division = a.op() == ArithmeticOp.DIVIDE || a.op() == ArithmeticOp.REMAINDER;
        if (division && isZero(r, t)) {
            if (ansi) {
                throw new ArithmeticException("Division by zero");
            }
            return null;
        }
        return switch (t) {
            case BYTE, SHORT, INT -> {
                long x = ((Number) l).longValue();
                long y = ((Number) r).longValue();
                long result = switch (a.op()) {
                    case ADD -> x + y;
                    case SUBTRACT -> x - y;
                    case MULTIPLY -> x * y;
                    case DIVIDE -> x / y;
                    case REMAINDER -> x % y;
                };
                yield Values.integral(ansi ? Values.checkRange(result, t) : result, t);
            }
            case LONG -> {
                long x = (Long) l;
                long y = (Long) r;
                yield switch (a.op()) {
                    case ADD -> ansi ? Math.addExact(x, y) : x + y;
                    case SUBTRACT -> ansi ? Math.subtractExact(x, y) : x - y;
                    case MULTIPLY -> ansi ? Math.multiplyExact(x, y) : x * y;
                    case DIVIDE -> {
                        if (ansi && x == Long.MIN_VALUE && y == -1) {
                            throw new ArithmeticException("long overflow");
                        }
                        yield x / y;
                    }
                    case REMAINDER -> x % y;
                };
            }
            case FLOAT -> {
                float x = (Float) l;
                float y = (Float) r;
                yield switch (a.op()) {
                    case ADD -> x + y;
                    case SUBTRACT -> x - y;
                    case MULTIPLY -> x * y;
                    case DIVIDE -> x / y;
                    case REMAINDER -> x % y;
                };
            }
            case DOUBLE -> {
                double x = (Double) l;
                double y = (Double) r;
                yield switch (a.op()) {
                    case ADD -> x + y;
                    case SUBTRACT -> x - y;
                    case MULTIPLY -> x * y;
                    case DIVIDE -> x / y;
                    case REMAINDER -> x % y;
                };
            }
            case DECIMAL -> {
                BigDecimal x = (BigDecimal) l;
                BigDecimal y = (BigDecimal) r;
                yield switch (a.op()) {
                    case ADD -> x.add(y);
                    case SUBTRACT -> x.subtract(y);
                    case MULTIPLY -> x.multiply(y);
                    case DIVIDE -> DecimalFunctions.divide(x, y);
                    case REMAINDER -> x.remainder(y);
                };
            }
            default -> throw new IllegalStateException("Not numeric: " + t);
        };
    }

    private static boolean isZero(Object value, DataType type) {
        return switch (type) {
            case FLOAT, DOUBLE -> ((Number) value).doubleValue() == 0;
            case DECIMAL -> ((BigDecimal) value).signum() == 0;
            default -> ((Number) value).longValue() == 0;
        };
    }

    private Object negate(DataType t, Object v) {
        if (v == null) {
            return null;
        }
        return switch (t) {
            case BYTE, SHORT, INT -> {
                long result = -((Number) v).longValue();
                yield Values.integral(ansi ? Values.checkRange(result, t) : result, t);
            }
            case LONG -> ansi ? Math.negateExact((Long) v) : -(Long) v;
            case FLOAT -> -(Float) v;
            case DOUBLE -> -(Double) v;
            case DECIMAL -> ((BigDecimal) v).negate();
            default -> throw new IllegalStateException("Not numeric: " + t);
        };
    }

    private Object bitwise(Expression.Bitwise b, Object[] row) {
        Object l = evaluate(b.left(), row);
        Object r = evaluate(b.right(), row);
        if (l == null || r == null) {
            return null;
        }
        long x = ((Number) l).longValue();
        long y = ((Number) r).longValue();
        long result = switch (b.op()) {
            case AND -> x & y;
            case OR -> x | y;
            case XOR -> x ^ y;
        };
        return Values.integral(result, b.type());
    }

    private Object shift(Expression.Shift s, Object[] row) {
        Object v = evaluate(s.value(), row);
        Object d = evaluate(s.distance(), row);
        if (v == null || d == null) {
            return null;
        }
        int n = (Integer) d;
        if (s.type() == DataType.LONG) {
            long x = (Long) v;
            return switch (s.op()) {
                case LEFT -> x << n;
                case RIGHT -> x >> n;
                case UNSIGNED_RIGHT -> x >>> n;
            };
        }
        int x = ((Number) v).intValue();
        int result = switch (s.op()) {
            case LEFT -> x << n;
            case RIGHT -> x >> n;
            case UNSIGNED_RIGHT -> x >>> n;
        };
        return Values.integral(result, s.type());
    }

    /** Compares two values of the same type; {@code NULL} if either is {@code NULL}. */
    private static Boolean compare(ComparisonOp op, Object l, Object r, DataType t) {
        if (l == null || r == null) {
            return null;
        }
        if (t.isFloatingPoint()) {
            double x = ((Number) l).doubleValue();
            double y = ((Number) r).doubleValue();
            return switch (op) {
                case EQ -> x == y;
                case NE -> x != y;
                case LT -> x < y;
                case LE -> x <= y;
                case GT -> x > y;
                case GE -> x >= y;
            };
        }
        int c = switch (t) {
            case BOOLEAN -> Boolean.compare((Boolean) l, (Boolean) r);
            case STRING -> ((String) l).compareTo((String) r);
            case DECIMAL -> ((BigDecimal) l).compareTo((BigDecimal) r);
            default -> Long.compare(((Number) l).longValue(), ((Number) r).longValue());
        };
        return switch (op) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
        };
    }

    private Object between(Expression.Between b, Object[] row) {
        DataType t = TypeRules.common(List.of(b.value().type(), b.low().type(), b.high().type()));
        Object v = Casts.widen(evaluate(b.value(), row), b.value().type(), t);
        Object lo = Casts.widen(evaluate(b.low(), row), b.low().type(), t);
        Object hi = Casts.widen(evaluate(b.high(), row), b.high().type(), t);
        Boolean ge = compare(ComparisonOp.GE, v, lo, t);
        Boolean le = compare(ComparisonOp.LE, v, hi, t);
        if (Boolean.FALSE.equals(ge) || Boolean.FALSE.equals(le)) {
            return false;
        }
        return ge == null || le == null ? null : Boolean.TRUE;
    }

    private Object in(Expression.In in, Object[] row) {
        Object v = evaluate(in.value(), row);
        if (v == null) {
            return null;
        }
        boolean sawNull = false;
        for (Expression element : in.list()) {
            Object e = evaluate(element, row);
            if (e == null) {
                sawNull = true;
                continue;
            }
            DataType t = TypeRules.common(in.value().type(), element.type());
            if (compare(ComparisonOp.EQ, Casts.widen(v, in.value().type(), t), Casts.widen(e, element.type(), t),
                    t)) {
                return true;
            }
        }
        return sawNull ? null : Boolean.FALSE;
    }

    private Object stringCall(Expression.StringCall s, Object[] row) {
        List<Object> args = new ArrayList<>();
        for (Expression a : s.arguments()) {
            Object v = evaluate(a, row);
            if (v == null) {
                // Evaluate the remaining arguments, too: they may throw.
                for (Expression rest : s.arguments().subList(args.size() + 1, s.arguments().size())) {
                    evaluate(rest, row);
                }
                return null;
            }
            args.add(v);
        }
        return switch (s.function()) {
            case CONCAT -> {
                StringBuilder sb = new StringBuilder();
                for (Object a : args) {
                    sb.append((String) a);
                }
                yield sb.toString();
            }
            case SUBSTRING -> StringFunctions.substring((String) args.get(0), (Integer) args.get(1),
                    (Integer) args.get(2));
            case UPPER -> StringFunctions.upper((String) args.get(0));
            case LENGTH -> ((String) args.get(0)).length();
        };
    }

    private Object mathCall(Expression.MathCall m, Object[] row) {
        if (m.function() == Expression.MathFunction.ABS) {
            Object v = evaluate(m.arguments().get(0), row);
            if (v == null) {
                return null;
            }
            DataType t = m.type();
            return switch (t) {
                case BYTE, SHORT, INT -> {
                    long result = Math.abs(((Number) v).longValue());
                    yield Values.integral(ansi ? Values.checkRange(result, t) : result, t);
                }
                case LONG -> ansi ? Math.absExact((Long) v) : Math.abs((Long) v);
                case FLOAT -> Math.abs((Float) v);
                case DOUBLE -> Math.abs((Double) v);
                case DECIMAL -> ((BigDecimal) v).abs();
                default -> throw new IllegalStateException("Not numeric: " + t);
            };
        }
        Double x = (Double) evaluate(m.arguments().get(0), row);
        Double y = m.arguments().size() > 1 ? (Double) evaluate(m.arguments().get(1), row) : null;
        if (x == null || (m.arguments().size() > 1 && y == null)) {
            return null;
        }
        return switch (m.function()) {
            case SQRT -> Math.sqrt(x);
            case FLOOR -> Math.floor(x);
            case CEIL -> Math.ceil(x);
            case POWER -> Math.pow(x, y);
            case ABS -> throw new IllegalStateException();
        };
    }
}
