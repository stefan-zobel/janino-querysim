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

import static io.github.janinolts.qsim.plan.Expressions.add;
import static io.github.janinolts.qsim.plan.Expressions.and;
import static io.github.janinolts.qsim.plan.Expressions.between;
import static io.github.janinolts.qsim.plan.Expressions.call;
import static io.github.janinolts.qsim.plan.Expressions.caseWhen;
import static io.github.janinolts.qsim.plan.Expressions.cast;
import static io.github.janinolts.qsim.plan.Expressions.coalesce;
import static io.github.janinolts.qsim.plan.Expressions.col;
import static io.github.janinolts.qsim.plan.Expressions.div;
import static io.github.janinolts.qsim.plan.Expressions.eq;
import static io.github.janinolts.qsim.plan.Expressions.gt;
import static io.github.janinolts.qsim.plan.Expressions.iff;
import static io.github.janinolts.qsim.plan.Expressions.isNull;
import static io.github.janinolts.qsim.plan.Expressions.like;
import static io.github.janinolts.qsim.plan.Expressions.lit;
import static io.github.janinolts.qsim.plan.Expressions.lt;
import static io.github.janinolts.qsim.plan.Expressions.mul;
import static io.github.janinolts.qsim.plan.Expressions.neg;
import static io.github.janinolts.qsim.plan.Expressions.not;
import static io.github.janinolts.qsim.plan.Expressions.or;
import static io.github.janinolts.qsim.plan.Expressions.rem;
import static io.github.janinolts.qsim.plan.Expressions.sub;
import static io.github.janinolts.qsim.plan.Expressions.when;

import io.github.janinolts.qsim.plan.Expression.MathFunction;
import io.github.janinolts.qsim.plan.Expression.StringFunction;
import io.github.janinolts.qsim.plan.PlanNode.AggregateCall;
import io.github.janinolts.qsim.plan.PlanNode.AggregateFunction;
import io.github.janinolts.qsim.plan.PlanNode.NamedExpression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A fixed, hand-written query, in one of four sizes.
 *
 * @param name the name, e.g. {@code Q_TINY}
 * @param plan the plan
 */
public record Scenario(String name, PlanNode plan) {

    /** The name of the main table of every scenario. */
    public static final String TABLE = "t";

    /** The name of the second table of {@code Q_JOIN_AGG}. */
    public static final String DIMENSION = "d";

    /** The names of the scenarios. */
    public static final List<String> NAMES = List.of("Q_TINY", "Q_FILTER_PROJECT", "Q_AGG", "Q_JOIN_AGG",
            "Q_CASE_HEAVY", "Q_STRING", "Q_DECIMAL", "Q_SORT", "Q_ANSI");

    /**
     * The sizes of a scenario; each scenario scales its own parameter: the
     * columns of {@code Q_FILTER_PROJECT}, the aggregates of {@code Q_AGG} and
     * {@code Q_JOIN_AGG}, the branches of {@code Q_CASE_HEAVY}, the
     * expressions of the others.
     */
    public enum Size {
        S(10, 8, 8), M(40, 32, 64), L(160, 128, 512), XL(640, 256, 2048);

        private final int columns;
        private final int count;
        private final int branches;

        Size(int columns, int count, int branches) {
            this.columns = columns;
            this.count = count;
            this.branches = branches;
        }

        /** Returns the number of columns of {@code Q_FILTER_PROJECT}: 10, 40, 160, 640. */
        public int columns() {
            return columns;
        }

        /** Returns the number of aggregates or expressions: 8, 32, 128, 256. */
        public int count() {
            return count;
        }

        /** Returns the number of branches of a {@code CASE}: 8, 64, 512, 2048. */
        public int branches() {
            return branches;
        }
    }

    public Scenario {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(plan, "plan");
    }

    /** Returns the scanned tables and their schemas, in the order of the scans. */
    public Map<String, Schema> tables() {
        Map<String, Schema> result = new LinkedHashMap<>();
        for (PlanNode.Scan s : new PlanIds(plan).scans()) {
            result.put(s.table(), s.schema());
        }
        return result;
    }

    /** Returns the schema of the main table. */
    public Schema tableSchema() {
        return tables().get(TABLE);
    }

    /** Returns a scenario by name and size; {@code Q_TINY} ignores the size. */
    public static Scenario of(String name, Size size, PlanNode.Layout layout) {
        return switch (name) {
            case "Q_TINY" -> tiny(layout);
            case "Q_FILTER_PROJECT" -> filterProject(size, layout);
            case "Q_AGG" -> aggregate(size, layout);
            case "Q_JOIN_AGG" -> joinAggregate(size, layout);
            case "Q_CASE_HEAVY" -> caseHeavy(size, layout);
            case "Q_STRING" -> strings(size, layout);
            case "Q_DECIMAL" -> decimals(size, layout);
            case "Q_SORT" -> sort(size, layout);
            case "Q_ANSI" -> ansi(size, layout);
            default -> throw new IllegalArgumentException("Unknown scenario: " + name);
        };
    }

    private static PlanNode scan(String table, Schema schema, PlanNode.Layout layout) {
        return new PlanNode.Scan(table, schema, layout);
    }

    private static PlanNode project(PlanNode input, List<Expression> expressions) {
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            columns.add(new NamedExpression("p" + i, expressions.get(i)));
        }
        return new PlanNode.Project(input, columns);
    }

    /**
     * {@code Q_TINY}: the expression {@code a * 3 + b} over two {@code INT}
     * columns without {@code NULL}; the fixed costs of a compiler instance.
     */
    public static Scenario tiny(PlanNode.Layout layout) {
        Schema schema = Schema.notNull(DataType.INT, DataType.INT);
        Expression e = add(mul(col(0, DataType.INT), lit(3)), col(1, DataType.INT));
        return new Scenario("Q_TINY", project(scan(TABLE, schema, layout), List.of(e)));
    }

    /** {@code Q_FILTER_PROJECT} in one of the sizes, see {@link #filterProject(int, PlanNode.Layout)}. */
    public static Scenario filterProject(Size size, PlanNode.Layout layout) {
        return filterProject(size.columns(), layout);
    }

    /** The order in which {@link #filterProject} assigns types to columns. */
    private static final List<DataType> COLUMN_TYPES = List.of(
            DataType.INT, DataType.LONG, DataType.DOUBLE, DataType.STRING, DataType.DECIMAL,
            DataType.BYTE, DataType.DATE, DataType.BOOLEAN, DataType.SHORT, DataType.FLOAT);

    /**
     * {@code Q_FILTER_PROJECT}: a scan of nullable columns of all types, a
     * filter with three-valued logic, and a projection with one expression per
     * column; null-pairs, many local variables, narrow types and casts.
     *
     * @param columns the number of columns, at least 2; the types repeat
     */
    public static Scenario filterProject(int columns, PlanNode.Layout layout) {
        if (columns < 2) {
            throw new IllegalArgumentException("columns: " + columns);
        }
        DataType[] types = new DataType[columns];
        for (int i = 0; i < columns; i++) {
            types[i] = COLUMN_TYPES.get(i % COLUMN_TYPES.size());
        }
        Expression c0 = col(0, DataType.INT);
        Expression condition = or(isNull(c0), and(gt(c0, lit(-500)), not(eq(col(1, DataType.LONG), lit(0L)))));
        List<Expression> projection = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            projection.add(projectColumn(col(i, types[i])));
        }
        return new Scenario("Q_FILTER_PROJECT",
                project(new PlanNode.Filter(scan(TABLE, Schema.of(types), layout), condition), projection));
    }

    private static Expression projectColumn(Expression c) {
        return switch (c.type()) {
            case INT -> caseWhen(List.of(when(gt(c, lit(0)), mul(c, lit(2)))), neg(c));
            case LONG -> add(div(c, lit(3L)), lit(1L));
            case DOUBLE -> iff(gt(c, lit(0.0)), call(MathFunction.SQRT, c), div(c, lit(2.0)));
            case STRING -> call(StringFunction.CONCAT, call(StringFunction.UPPER, c),
                    cast(call(StringFunction.LENGTH, c), DataType.STRING));
            case DECIMAL -> add(mul(c, lit(2)), lit(new BigDecimal("0.5")));
            case BYTE -> add(c, lit((byte) 1));
            case DATE -> cast(c, DataType.STRING);
            case BOOLEAN -> or(not(c), isNull(c));
            case SHORT -> cast(c, DataType.BYTE);
            case FLOAT -> mul(coalesce(c, lit(0.0f)), lit(2.0f));
        };
    }

    private static final List<AggregateFunction> FUNCTIONS = List.of(AggregateFunction.SUM, AggregateFunction.COUNT,
            AggregateFunction.MIN, AggregateFunction.MAX, AggregateFunction.AVG, AggregateFunction.COUNT_STAR);

    /**
     * {@code Q_AGG}: a projection that computes two grouping keys and passes
     * value columns of many types, then a hash aggregation with the given
     * number of aggregates; compound assignments, hash codes, state fields.
     */
    public static Scenario aggregate(Size size, PlanNode.Layout layout) {
        return aggregate(size.count(), layout);
    }

    /** {@code Q_AGG} with the given number of aggregates, at least 1. */
    public static Scenario aggregate(int count, PlanNode.Layout layout) {
        if (count < 1) {
            throw new IllegalArgumentException("count: " + count);
        }
        Schema schema = Schema.of(DataType.INT, DataType.STRING, DataType.BYTE, DataType.SHORT, DataType.LONG,
                DataType.DOUBLE, DataType.DECIMAL, DataType.FLOAT, DataType.DATE, DataType.BOOLEAN);
        List<Expression> projection = new ArrayList<>();
        projection.add(rem(col(0, DataType.INT), lit(7)));
        projection.add(call(StringFunction.UPPER, call(StringFunction.SUBSTRING, col(1, DataType.STRING), lit(1),
                lit(1))));
        for (int c = 2; c < schema.size(); c++) {
            projection.add(col(c, schema.type(c)));
        }
        projection.add(mul(col(4, DataType.LONG), lit(3L)));
        PlanNode input = project(scan(TABLE, schema, layout), projection);
        return new Scenario("Q_AGG", new PlanNode.HashAggregate(input, List.of(0, 1),
                aggregates(input.schema(), 2, count)));
    }

    /** Aggregates over the columns from {@code firstValue} on, cycling through functions and columns. */
    private static List<AggregateCall> aggregates(Schema input, int firstValue, int count) {
        List<AggregateCall> result = new ArrayList<>();
        int column = firstValue;
        for (int i = 0; i < count; i++) {
            AggregateFunction f = FUNCTIONS.get(i % FUNCTIONS.size());
            if (f == AggregateFunction.COUNT_STAR) {
                result.add(new AggregateCall(f, -1, "a" + i));
                continue;
            }
            int c = column;
            while ((f == AggregateFunction.SUM || f == AggregateFunction.AVG) && !input.type(c).isNumeric()) {
                c = c + 1 < input.size() ? c + 1 : firstValue;
            }
            result.add(new AggregateCall(f, c, "a" + i));
            column = c + 1 < input.size() ? c + 1 : firstValue;
        }
        return result;
    }

    /**
     * {@code Q_JOIN_AGG}: an inner hash join of a fact table with a dimension
     * table on a computed key, a left outer join with a filtered copy of the
     * dimension, and an aggregation by a dimension attribute; several
     * pipelines, references, inner classes.
     */
    public static Scenario joinAggregate(Size size, PlanNode.Layout layout) {
        Schema facts = Schema.of(DataType.INT, DataType.DOUBLE, DataType.LONG, DataType.DECIMAL, DataType.STRING);
        Schema dimension = Schema.of(DataType.INT, DataType.STRING, DataType.SHORT);
        PlanNode probe = project(scan(TABLE, facts, layout), List.of(rem(col(0, DataType.INT), lit(16)),
                col(1, DataType.DOUBLE), col(2, DataType.LONG), col(3, DataType.DECIMAL), col(4, DataType.STRING)));
        PlanNode build = project(scan(DIMENSION, dimension, layout), List.of(rem(col(0, DataType.INT), lit(16)),
                call(StringFunction.SUBSTRING, col(1, DataType.STRING), lit(1), lit(2)), col(2, DataType.SHORT)));
        PlanNode inner = new PlanNode.HashJoin(probe, build, List.of(0), List.of(0), PlanNode.JoinType.INNER);
        PlanNode filtered = project(new PlanNode.Filter(scan(DIMENSION, dimension, layout),
                gt(col(2, DataType.SHORT), lit((short) 0))), List.of(rem(col(0, DataType.INT), lit(16)),
                col(2, DataType.SHORT)));
        PlanNode outer = new PlanNode.HashJoin(inner, filtered, List.of(0), List.of(0),
                PlanNode.JoinType.LEFT_OUTER);
        // Columns: 0 k, 1 b, 2 c, 3 e, 4 s, 5 k, 6 name, 7 w, 8 k, 9 w
        List<AggregateCall> aggregates = new ArrayList<>();
        int[] values = {1, 2, 3, 7, 9};
        for (int i = 0; i < size.count() / 2; i++) {
            AggregateFunction f = FUNCTIONS.get(i % FUNCTIONS.size());
            aggregates.add(new AggregateCall(f, f == AggregateFunction.COUNT_STAR ? -1 : values[i % values.length],
                    "a" + i));
        }
        return new Scenario("Q_JOIN_AGG", new PlanNode.HashAggregate(outer, List.of(6), aggregates));
    }

    /**
     * {@code Q_CASE_HEAVY}: {@code CASE} expressions with many branches and
     * long {@code IN} lists; branching, jump distances.
     */
    public static Scenario caseHeavy(Size size, PlanNode.Layout layout) {
        return caseHeavy(size.branches(), layout);
    }

    /** {@code Q_CASE_HEAVY} with the given number of branches and list elements, at least 1. */
    public static Scenario caseHeavy(int n, PlanNode.Layout layout) {
        if (n < 1) {
            throw new IllegalArgumentException("branches: " + n);
        }
        Schema schema = Schema.of(DataType.INT, DataType.DOUBLE, DataType.STRING, DataType.LONG);
        Expression x = col(0, DataType.INT);
        Expression y = col(1, DataType.DOUBLE);
        Expression s = col(2, DataType.STRING);
        List<Expression.When> byValue = new ArrayList<>();
        List<Expression.When> byRange = new ArrayList<>();
        List<Expression> ints = new ArrayList<>();
        List<Expression> strings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int value = (i * 37) % 2001 - 1000;
            byValue.add(when(eq(x, lit(value)), lit("v" + i)));
            byRange.add(when(lt(y, lit(-1000.0 + 2000.0 * i / n)), lit(i)));
            ints.add(lit(value));
            strings.add(lit(Character.toString((char) ('a' + i % 26)) + (i / 26 == 0 ? "" : Integer.toString(i / 26))));
        }
        List<Expression> projection = List.of(
                caseWhen(byValue, lit("other")),
                caseWhen(byRange, Expressions.nullOf(DataType.INT)),
                Expressions.in(x, ints.toArray(new Expression[0])),
                Expressions.in(s, strings.toArray(new Expression[0])),
                between(col(3, DataType.LONG), lit(-100L), lit(100L)));
        return new Scenario("Q_CASE_HEAVY", project(scan(TABLE, schema, layout), projection));
    }

    private static final List<String> PATTERNS = List.of("a%", "%b", "%_%", "_", "", "%a%b%", "A_");

    /**
     * {@code Q_STRING}: string functions, {@code LIKE}, and casts from and to
     * strings; method resolution, overloads, loops over {@code char}.
     */
    public static Scenario strings(Size size, PlanNode.Layout layout) {
        Schema schema = Schema.of(DataType.STRING, DataType.STRING, DataType.INT, DataType.DATE, DataType.DOUBLE);
        Expression a = col(0, DataType.STRING);
        Expression b = col(1, DataType.STRING);
        Expression n = col(2, DataType.INT);
        List<Expression> projection = new ArrayList<>();
        for (int i = 0; i < size.count(); i++) {
            projection.add(switch (i % 8) {
                case 0 -> call(StringFunction.UPPER, a);
                case 1 -> call(StringFunction.CONCAT, a, lit("-"), b);
                case 2 -> call(StringFunction.SUBSTRING, a, rem(n, lit(5)), lit(3));
                case 3 -> call(StringFunction.LENGTH, b);
                case 4 -> like(a, lit(PATTERNS.get(i / 8 % PATTERNS.size())));
                case 5 -> cast(n, DataType.STRING);
                case 6 -> cast(b, DataType.INT);
                default -> call(StringFunction.CONCAT, cast(col(3, DataType.DATE), DataType.STRING),
                        cast(col(4, DataType.DOUBLE), DataType.STRING));
            });
        }
        return new Scenario("Q_STRING", project(scan(TABLE, schema, layout), projection));
    }

    /**
     * {@code Q_DECIMAL}: decimal arithmetic, casts and comparisons, then
     * sums and averages of decimals per group; method calls on objects,
     * boxing.
     */
    public static Scenario decimals(Size size, PlanNode.Layout layout) {
        Schema schema = Schema.of(DataType.DECIMAL, DataType.DECIMAL, DataType.INT, DataType.DOUBLE, DataType.LONG);
        Expression a = col(0, DataType.DECIMAL);
        Expression b = col(1, DataType.DECIMAL);
        List<Expression> projection = new ArrayList<>();
        projection.add(rem(col(2, DataType.INT), lit(5)));
        for (int i = 0; i < size.count(); i++) {
            projection.add(switch (i % 8) {
                case 0 -> add(a, b);
                case 1 -> mul(a, b);
                case 2 -> div(a, b);
                case 3 -> rem(a, lit(new BigDecimal("0.7")));
                case 4 -> call(MathFunction.ABS, neg(a));
                case 5 -> cast(col(3, DataType.DOUBLE), DataType.DECIMAL);
                case 6 -> sub(a, col(4, DataType.LONG));
                default -> iff(gt(a, b), a, b);
            });
        }
        PlanNode input = project(scan(TABLE, schema, layout), projection);
        List<AggregateCall> aggregates = new ArrayList<>();
        for (int c = 1; c < input.schema().size(); c++) {
            aggregates.add(new AggregateCall(c % 2 == 0 ? AggregateFunction.SUM : AggregateFunction.AVG, c, "s" + c));
        }
        aggregates.add(new AggregateCall(AggregateFunction.MIN, 1, "min"));
        aggregates.add(new AggregateCall(AggregateFunction.MAX, 1, "max"));
        return new Scenario("Q_DECIMAL", new PlanNode.HashAggregate(input, List.of(0), aggregates));
    }

    /**
     * {@code Q_SORT}: a projection, a sort over several keys of different
     * types and directions, and a limit; a generated comparator, conditional
     * expressions.
     */
    public static Scenario sort(Size size, PlanNode.Layout layout) {
        Schema schema = Schema.of(DataType.STRING, DataType.DOUBLE, DataType.INT, DataType.DECIMAL, DataType.BOOLEAN,
                DataType.DATE, DataType.FLOAT, DataType.BYTE);
        List<Expression> projection = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            projection.add(col(c, schema.type(c)));
        }
        projection.add(call(StringFunction.UPPER, call(StringFunction.SUBSTRING, col(0, DataType.STRING), lit(1),
                lit(1))));
        projection.add(rem(col(2, DataType.INT), lit(3)));
        PlanNode input = project(scan(TABLE, schema, layout), projection);
        int[] order = {8, 9, 1, 3, 4, 5, 6, 7, 0, 2};
        int keys = Math.min(order.length, 2 + size.count() / 4);
        List<PlanNode.SortKey> sortKeys = new ArrayList<>();
        for (int i = 0; i < keys; i++) {
            sortKeys.add(new PlanNode.SortKey(order[i], i % 2 == 0, i % 3 == 0));
        }
        return new Scenario("Q_SORT", new PlanNode.Limit(new PlanNode.Sort(input, sortKeys), 100));
    }

    /**
     * {@code Q_ANSI}: checked arithmetic on narrow and wide types, each
     * operation guarded by a range check, so that most rows produce a result
     * also with ANSI; meant for the ANSI mode and nested {@code try} blocks.
     */
    public static Scenario ansi(Size size, PlanNode.Layout layout) {
        return ansi(size.count(), layout);
    }

    /** {@code Q_ANSI} with the given number of projected expressions, at least 1. */
    public static Scenario ansi(int count, PlanNode.Layout layout) {
        if (count < 1) {
            throw new IllegalArgumentException("count: " + count);
        }
        Schema schema = Schema.of(DataType.BYTE, DataType.SHORT, DataType.INT, DataType.LONG, DataType.DOUBLE);
        Expression b = col(0, DataType.BYTE);
        Expression s = col(1, DataType.SHORT);
        Expression i = col(2, DataType.INT);
        Expression l = col(3, DataType.LONG);
        Expression d = col(4, DataType.DOUBLE);
        List<Expression> projection = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            projection.add(switch (k % 8) {
                case 0 -> iff(between(b, lit((byte) -60), lit((byte) 60)), add(b, b), lit((byte) 0));
                case 1 -> iff(between(s, lit((short) -100), lit((short) 100)), mul(s, s), lit((short) 0));
                case 2 -> iff(between(i, lit(-1000), lit(1000)), mul(i, lit(1000)), lit(0));
                case 3 -> iff(between(l, lit(-1_000_000L), lit(1_000_000L)), add(mul(l, l), lit((long) k)), lit(0L));
                case 4 -> iff(between(d, lit(-100.0), lit(100.0)), cast(d, DataType.BYTE), lit((byte) 0));
                case 5 -> iff(between(i, lit(-30_000), lit(30_000)), cast(i, DataType.SHORT), lit((short) 0));
                case 6 -> iff(eq(s, lit((short) 0)), lit(0), div(i, cast(s, DataType.INT)));
                default -> iff(between(l, lit(-1000L), lit(1000L)), neg(cast(l, DataType.INT)), lit(0));
            });
        }
        return new Scenario("Q_ANSI", project(scan(TABLE, schema, layout), projection));
    }

    /** The name of the plan of {@link #expressionDepth}. */
    public static final String EXPRESSION_DEPTH = "Q_EXPR_DEPTH";

    /** The name of the plan of {@link #operatorChain}. */
    public static final String OPERATOR_CHAIN = "Q_OPERATOR_CHAIN";

    /**
     * A projection of four expressions of the given depth, two over
     * {@code LONG} and two over {@code DOUBLE}: each level wraps the level
     * below in an addition, a multiplication, a conditional or a subtraction
     * with a column or a literal, so the code grows linearly with the depth.
     *
     * @param depth the height of each expression tree, at least 1
     */
    public static Scenario expressionDepth(int depth, PlanNode.Layout layout) {
        if (depth < 1) {
            throw new IllegalArgumentException("depth: " + depth);
        }
        Schema schema = Schema.of(DataType.INT, DataType.LONG, DataType.DOUBLE, DataType.INT, DataType.LONG,
                DataType.DOUBLE);
        Expression x = col(0, DataType.INT);
        List<Expression> projection = new ArrayList<>();
        for (int e = 0; e < 4; e++) {
            boolean wide = e % 2 == 0;
            DataType type = wide ? DataType.LONG : DataType.DOUBLE;
            Expression other = col(wide ? 4 : 5, type);
            Expression chain = col(wide ? 1 : 2, type);
            for (int level = 1; level < depth; level++) {
                int k = level + e;
                chain = switch (k % 4) {
                    case 0 -> add(chain, other);
                    case 1 -> mul(chain, wide ? lit(3L) : lit(0.5));
                    case 2 -> iff(gt(x, lit(k * 7 % 101 - 50)), chain, wide ? lit((long) k) : lit((double) k));
                    default -> sub(chain, cast(col(3, DataType.INT), type));
                };
            }
            projection.add(chain);
        }
        return new Scenario(EXPRESSION_DEPTH, project(scan(TABLE, schema, layout), projection));
    }

    /**
     * A chain of the given number of operators over one scan, alternately a
     * projection, which changes the first two columns, and a filter, which
     * keeps nearly every row; in the style {@code OPERATOR_CLASSES} each
     * operator is a class of its own.
     *
     * @param operators the number of operators, at least 1
     */
    public static Scenario operatorChain(int operators, PlanNode.Layout layout) {
        if (operators < 1) {
            throw new IllegalArgumentException("operators: " + operators);
        }
        Schema schema = Schema.of(DataType.INT, DataType.LONG, DataType.DOUBLE, DataType.STRING);
        PlanNode node = scan(TABLE, schema, layout);
        for (int i = 0; i < operators; i++) {
            if (i % 2 == 0) {
                node = project(node, List.of(add(col(0, DataType.INT), lit(i + 1)),
                        mul(col(1, DataType.LONG), lit(i % 3 == 0 ? -1L : 1L)), col(2, DataType.DOUBLE),
                        col(3, DataType.STRING)));
            } else {
                node = new PlanNode.Filter(node, or(isNull(col(1, DataType.LONG)),
                        not(eq(col(1, DataType.LONG), lit((long) i)))));
            }
        }
        return new Scenario(OPERATOR_CHAIN, node);
    }

    /** Tells whether the output of a plan has a defined order: a sort at the top, below filters, projections, limits. */
    public static boolean ordered(PlanNode plan) {
        return switch (plan) {
            case PlanNode.Sort s -> true;
            case PlanNode.Limit l -> ordered(l.input());
            case PlanNode.Filter f -> ordered(f.input());
            case PlanNode.Project p -> ordered(p.input());
            default -> false;
        };
    }

    /** Returns the data of the scenario's tables: per table, generated with the seed and the table index. */
    public Map<String, Table> data(long seed, int rows) {
        Map<String, Table> result = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Schema> e : tables().entrySet()) {
            result.put(e.getKey(), DataGenerator.withSeed(seed + 1000L * i++).generate(e.getValue(), rows));
        }
        return result;
    }
}
