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

import io.github.janinolts.qsim.plan.PlanNode.AggregateCall;
import io.github.janinolts.qsim.plan.PlanNode.AggregateFunction;
import io.github.janinolts.qsim.plan.PlanNode.NamedExpression;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Generates random plans: a seed determines the plan and its tables
 * completely. Every scan reads a table of its own, {@code t0}, {@code t1},
 * ..., with a random schema. Weights for the operators, the expressions and
 * the types are configurable, as is the node budget.
 */
public final class RandomPlans {

    /** The operators that the generator places above the scans. */
    public enum Operator { FILTER, PROJECT, AGGREGATE, JOIN, SORT, LIMIT, UNION }

    /**
     * The settings of the generator.
     *
     * @param nodeBudget the maximum number of nodes of a plan, at least 1
     * @param expressionDepth the maximum depth of an expression
     * @param maxColumns the maximum number of columns of a table
     * @param maxJoins the maximum number of joins of a plan
     * @param operators the weight of each operator
     * @param expressions the weights of the expressions and of the types
     *        (also of the columns of the tables)
     * @param pipelineOneIn a plan consists only of filters and projections
     *        over one scan, which the style {@code EXPRESSION} supports, with
     *        the probability {@code 1 / pipelineOneIn}
     */
    public record Settings(int nodeBudget, int expressionDepth, int maxColumns, int maxJoins,
            Map<Operator, Integer> operators, ExpressionGenerator.Weights expressions, int pipelineOneIn) {

        /** The default settings: at most 6 nodes, depth 3, 6 columns, 2 joins. */
        public static final Settings DEFAULT = new Settings(6, 3, 6, 2, defaultOperatorWeights(),
                ExpressionGenerator.Weights.DEFAULT, 4);

        public Settings {
            operators = Map.copyOf(operators);
            Objects.requireNonNull(expressions, "expressions");
            if (nodeBudget < 1 || expressionDepth < 0 || maxColumns < 1 || maxJoins < 0 || pipelineOneIn < 1) {
                throw new IllegalArgumentException("Invalid settings: " + nodeBudget + ", " + expressionDepth + ", "
                        + maxColumns + ", " + maxJoins + ", " + pipelineOneIn);
            }
        }

        private static Map<Operator, Integer> defaultOperatorWeights() {
            Map<Operator, Integer> weights = new EnumMap<>(Operator.class);
            weights.put(Operator.FILTER, 3);
            weights.put(Operator.PROJECT, 4);
            weights.put(Operator.AGGREGATE, 2);
            weights.put(Operator.JOIN, 2);
            weights.put(Operator.SORT, 1);
            weights.put(Operator.LIMIT, 1);
            weights.put(Operator.UNION, 1);
            return weights;
        }
    }

    /**
     * A random plan.
     *
     * @param seed the seed that determined it
     * @param plan the plan
     */
    public record RandomPlan(long seed, PlanNode plan) {

        public RandomPlan {
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

        /** Returns the data of the tables: per table, generated from the seed and the table index. */
        public Map<String, Table> data(int rows) {
            Map<String, Table> result = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<String, Schema> e : tables().entrySet()) {
                result.put(e.getKey(), DataGenerator.withSeed(seed * 31 + i++).generate(e.getValue(), rows));
            }
            return result;
        }
    }

    private final Settings settings;

    public RandomPlans(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Returns the settings. */
    public Settings settings() {
        return settings;
    }

    /** Returns a generator with the default settings. */
    public static RandomPlans withDefaults() {
        return new RandomPlans(Settings.DEFAULT);
    }

    /** Generates the plan of a seed. */
    public RandomPlan generate(long seed) {
        return new RandomPlan(seed, new Run(new SplittableRandom(seed)).plan());
    }

    /** The state of one generation. */
    private final class Run {

        private final SplittableRandom random;
        private final boolean pipelineOnly;
        private int tables;
        private int joins;

        Run(SplittableRandom random) {
            this.random = random;
            this.pipelineOnly = random.nextInt(settings.pipelineOneIn()) == 0;
        }

        PlanNode plan() {
            return node(settings.nodeBudget());
        }

        private PlanNode node(int budget) {
            if (budget <= 1) {
                return scan();
            }
            Operator op = pick();
            if (op == null) {
                return scan();
            }
            return switch (op) {
                case FILTER -> {
                    PlanNode in = node(budget - 1);
                    yield new PlanNode.Filter(in, expression(DataType.BOOLEAN, in.schema()));
                }
                case PROJECT -> project(node(budget - 1));
                case AGGREGATE -> aggregate(node(budget - 1));
                case JOIN -> {
                    joins++;
                    int left = 1 + random.nextInt(Math.max(1, budget - 2));
                    yield join(node(left), node(Math.max(1, budget - 1 - left)));
                }
                case SORT -> sort(node(budget - 1));
                case LIMIT -> new PlanNode.Limit(node(budget - 1), 1 + random.nextInt(20));
                case UNION -> {
                    int left = 1 + random.nextInt(Math.max(1, budget - 2));
                    PlanNode a = node(left);
                    yield union(a, node(Math.max(1, budget - 1 - left)));
                }
            };
        }

        /** Picks an operator by weight, or {@code null} for a scan if no operator is allowed. */
        private Operator pick() {
            List<Operator> candidates = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            int total = 0;
            for (Operator op : Operator.values()) {
                boolean allowed = pipelineOnly ? op == Operator.FILTER || op == Operator.PROJECT
                        : op != Operator.JOIN || joins < settings.maxJoins();
                int w = settings.operators().getOrDefault(op, 0);
                if (allowed && w > 0) {
                    candidates.add(op);
                    weights.add(w);
                    total += w;
                }
            }
            if (total == 0) {
                return null;
            }
            int r = random.nextInt(total);
            for (int i = 0; i < candidates.size(); i++) {
                r -= weights.get(i);
                if (r < 0) {
                    return candidates.get(i);
                }
            }
            throw new IllegalStateException();
        }

        private PlanNode scan() {
            int n = 1 + random.nextInt(settings.maxColumns());
            List<Schema.Field> fields = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fields.add(new Schema.Field("c" + i, settings.expressions().pickType(random), random.nextInt(4) != 0));
            }
            PlanNode.Layout layout = random.nextBoolean() ? PlanNode.Layout.ROWS : PlanNode.Layout.COLUMNS;
            return new PlanNode.Scan("t" + tables++, new Schema(fields), layout);
        }

        private Expression expression(DataType type, Schema schema) {
            return new ExpressionGenerator(random, schema, settings.expressions())
                    .generate(type, settings.expressionDepth());
        }

        private PlanNode project(PlanNode in) {
            Schema schema = in.schema();
            int n = 1 + random.nextInt(4);
            List<NamedExpression> columns = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Expression e;
                if (random.nextInt(3) == 0) {
                    int c = random.nextInt(schema.size());
                    e = new Expression.Column(c, schema.type(c));
                } else {
                    e = expression(settings.expressions().pickType(random), schema);
                }
                columns.add(new NamedExpression("x" + i, e));
            }
            return new PlanNode.Project(in, columns);
        }

        private PlanNode aggregate(PlanNode in) {
            Schema schema = in.schema();
            List<Integer> groups = new ArrayList<>();
            int g = random.nextInt(3);
            for (int i = 0; i < g; i++) {
                int c = random.nextInt(schema.size());
                if (!groups.contains(c)) {
                    groups.add(c);
                }
            }
            List<Integer> numeric = new ArrayList<>();
            for (int c = 0; c < schema.size(); c++) {
                if (schema.type(c).isNumeric()) {
                    numeric.add(c);
                }
            }
            List<AggregateCall> calls = new ArrayList<>();
            int n = 1 + random.nextInt(3);
            for (int i = 0; i < n; i++) {
                AggregateFunction f = AggregateFunction.values()[random.nextInt(AggregateFunction.values().length)];
                if ((f == AggregateFunction.SUM || f == AggregateFunction.AVG) && numeric.isEmpty()) {
                    f = AggregateFunction.COUNT;
                }
                int column = switch (f) {
                    case COUNT_STAR -> -1;
                    case SUM, AVG -> numeric.get(random.nextInt(numeric.size()));
                    default -> random.nextInt(schema.size());
                };
                calls.add(new AggregateCall(f, column, "a" + i));
            }
            return new PlanNode.HashAggregate(in, groups, calls);
        }

        private PlanNode join(PlanNode probe, PlanNode build) {
            Schema p = probe.schema();
            // Keys of type BOOLEAN match half of the rows; prefer other types, so that joins of joins stay small.
            List<Integer> candidates = new ArrayList<>();
            for (int c = 0; c < p.size(); c++) {
                if (p.type(c) != DataType.BOOLEAN) {
                    candidates.add(c);
                }
            }
            if (candidates.isEmpty()) {
                probe = new PlanNode.Project(probe, withExtra(p, expression(DataType.INT, p)));
                p = probe.schema();
                candidates.add(p.size() - 1);
            }
            int keys = random.nextInt(4) == 0 ? 2 : 1;
            List<Integer> probeKeys = new ArrayList<>();
            List<Integer> buildKeys = new ArrayList<>();
            for (int k = 0; k < keys; k++) {
                int probeKey = candidates.get(random.nextInt(candidates.size()));
                DataType type = p.type(probeKey);
                Schema b = build.schema();
                List<Integer> matching = new ArrayList<>();
                for (int c = 0; c < b.size(); c++) {
                    if (b.type(c) == type) {
                        matching.add(c);
                    }
                }
                int buildKey;
                if (matching.isEmpty() || random.nextInt(4) == 0) {
                    build = new PlanNode.Project(build, withExtra(b, expression(type, b)));
                    buildKey = build.schema().size() - 1;
                } else {
                    buildKey = matching.get(random.nextInt(matching.size()));
                }
                probeKeys.add(probeKey);
                buildKeys.add(buildKey);
            }
            PlanNode.JoinType type = random.nextBoolean() ? PlanNode.JoinType.INNER : PlanNode.JoinType.LEFT_OUTER;
            return new PlanNode.HashJoin(probe, build, probeKeys, buildKeys, type);
        }

        /** The columns of a schema, passed through, followed by one more. */
        private List<NamedExpression> withExtra(Schema schema, Expression extra) {
            List<NamedExpression> columns = new ArrayList<>();
            for (int c = 0; c < schema.size(); c++) {
                columns.add(new NamedExpression("c" + c, new Expression.Column(c, schema.type(c))));
            }
            columns.add(new NamedExpression("k" + schema.size(), extra));
            return columns;
        }

        private PlanNode sort(PlanNode in) {
            Schema schema = in.schema();
            List<PlanNode.SortKey> keys = new ArrayList<>();
            List<Integer> used = new ArrayList<>();
            int n = 1 + random.nextInt(3);
            for (int i = 0; i < n; i++) {
                int c = random.nextInt(schema.size());
                if (!used.contains(c)) {
                    used.add(c);
                    keys.add(new PlanNode.SortKey(c, random.nextBoolean(), random.nextBoolean()));
                }
            }
            return new PlanNode.Sort(in, keys);
        }

        /** A union of two inputs; the second is projected to the types of the first. */
        private PlanNode union(PlanNode a, PlanNode b) {
            Schema target = a.schema();
            Schema source = b.schema();
            List<NamedExpression> columns = new ArrayList<>();
            for (int c = 0; c < target.size(); c++) {
                columns.add(new NamedExpression("u" + c, expression(target.type(c), source)));
            }
            return new PlanNode.Union(List.of(a, new PlanNode.Project(b, columns)));
        }
    }

    /**
     * Places a language feature into a plan. A feature that passes its
     * operand through wraps a column of a projection or the condition of a
     * filter; otherwise, and for a feature with a fixed operand type, a
     * projection at the top passes all columns through and adds the feature
     * over a column of the operand type, or over an expression of it.
     */
    public static PlanNode withFeature(PlanNode plan, LanguageFeature feature, SplittableRandom random,
            ExpressionGenerator.Weights weights) {
        if (feature.operandType() == null) {
            List<PlanNode> candidates = new ArrayList<>();
            for (PlanNode node : Plans.nodes(plan)) {
                if (node instanceof PlanNode.Project || node instanceof PlanNode.Filter) {
                    candidates.add(node);
                }
            }
            if (!candidates.isEmpty()) {
                PlanNode target = candidates.get(random.nextInt(candidates.size()));
                List<Expression> expressions = Plans.expressions(target);
                int index = random.nextInt(expressions.size());
                int[] k = {0};
                PlanNode wrapped = Plans.mapExpressions(target,
                        e -> k[0]++ == index ? new Expression.Feature(feature, e) : e);
                return Plans.replace(plan, target, wrapped);
            }
        }
        Schema schema = plan.schema();
        DataType type = feature.operandType();
        List<Integer> matching = new ArrayList<>();
        List<Integer> nullable = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            if (type == null || schema.type(c) == type) {
                matching.add(c);
                if (schema.nullable(c)) {
                    nullable.add(c);
                }
            }
        }
        Expression operand;
        if (!matching.isEmpty()) {
            List<Integer> from = nullable.isEmpty() ? matching : nullable;
            int c = from.get(random.nextInt(from.size()));
            operand = new Expression.Column(c, schema.type(c));
        } else {
            operand = new ExpressionGenerator(random, schema, weights).generate(type, 2);
        }
        List<NamedExpression> columns = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            columns.add(new NamedExpression(schema.fields().get(c).name(), new Expression.Column(c, schema.type(c))));
        }
        columns.add(new NamedExpression("feature", new Expression.Feature(feature, operand)));
        return new PlanNode.Project(plan, columns);
    }
}
