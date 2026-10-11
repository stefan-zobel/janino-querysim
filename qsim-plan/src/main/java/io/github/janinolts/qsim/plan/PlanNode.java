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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An operator of a query plan. Plans are trees; every node checks, when it is
 * created, that its expressions and column references fit the schema of its
 * input. Grouping, join and sort keys and the arguments of aggregate functions
 * are column references; a {@link Project} below computes them.
 * <p>
 * Scans, filters, projections, joins on their probe side, and limits process
 * one row at a time and form pipelines. A pipeline ends where an operator
 * collects its whole input before it produces a row: an aggregation, the build
 * side of a join, a sort and a union.
 */
public sealed interface PlanNode {

    /** Returns the schema of the output. */
    Schema schema();

    /** Returns the inputs. */
    List<PlanNode> children();

    /** How a scan reads its table. */
    enum Layout {
        /** Row by row, each row an array of values. */
        ROWS,
        /** Column by column, each column an array of values and an array of null flags. */
        COLUMNS
    }

    /**
     * Reads a table.
     *
     * @param table the name of the table
     * @param schema the schema of the table
     * @param layout how the table is read
     */
    record Scan(String table, Schema schema, Layout layout) implements PlanNode {
        public Scan {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(layout, "layout");
        }

        @Override
        public List<PlanNode> children() {
            return List.of();
        }
    }

    /**
     * Keeps the rows for which the condition is true; {@code NULL} counts as
     * false.
     *
     * @param input the input
     * @param condition a {@code BOOLEAN} expression over the input
     */
    record Filter(PlanNode input, Expression condition) implements PlanNode {
        public Filter {
            TypeRules.require(DataType.BOOLEAN, condition.type(), "The condition of a filter");
            input.schema().check(condition);
        }

        @Override
        public Schema schema() {
            return input.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /** The type of a join. */
    enum JoinType { INNER, LEFT_OUTER }

    /** The aggregate functions. */
    enum AggregateFunction { COUNT_STAR, COUNT, SUM, MIN, MAX, AVG }

    /**
     * An aggregate function applied to a column of the input.
     *
     * @param function the function
     * @param column the column, or -1 for {@code COUNT_STAR}
     * @param name the name of the output column
     */
    record AggregateCall(AggregateFunction function, int column, String name) {
        public AggregateCall {
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(name, "name");
            if ((function == AggregateFunction.COUNT_STAR) != (column < 0)) {
                throw new IllegalArgumentException(function + " with column " + column);
            }
        }

        /**
         * Returns the type of the result: {@code LONG} for the counts and for
         * the sum of an integral type, {@code DOUBLE} for the sum of a
         * floating-point type and the average of a non-decimal type, the type
         * of the column for {@code MIN} and {@code MAX}, and {@code DECIMAL}
         * for the sum and the average of decimals.
         */
        public DataType type(Schema input) {
            if (function == AggregateFunction.COUNT_STAR || function == AggregateFunction.COUNT) {
                return DataType.LONG;
            }
            DataType t = input.type(column);
            return switch (function) {
                case SUM -> t.isIntegral() ? DataType.LONG : t == DataType.DECIMAL ? DataType.DECIMAL : DataType.DOUBLE;
                case AVG -> t == DataType.DECIMAL ? DataType.DECIMAL : DataType.DOUBLE;
                default -> t;
            };
        }

        void check(Schema input) {
            if (column >= input.size()) {
                throw new IllegalArgumentException("No column " + column + " for " + function);
            }
            if ((function == AggregateFunction.SUM || function == AggregateFunction.AVG)
                    && !input.type(column).isNumeric()) {
                throw new IllegalArgumentException(function + " of " + input.type(column));
            }
        }
    }

    /**
     * Groups the rows by the values of some columns and aggregates each group.
     * Rows whose keys are equal after {@code Keys.canonical} form one group,
     * also with {@code NULL}; the output shows the canonical key values,
     * followed by the aggregates. Without grouping columns, an empty input
     * gives one row.
     *
     * @param input the input
     * @param groupColumns the grouping columns
     * @param aggregates the aggregate functions
     */
    record HashAggregate(PlanNode input, List<Integer> groupColumns, List<AggregateCall> aggregates)
            implements PlanNode {
        public HashAggregate {
            groupColumns = List.copyOf(groupColumns);
            aggregates = List.copyOf(aggregates);
            for (int c : groupColumns) {
                checkColumn(input.schema(), c);
            }
            for (AggregateCall a : aggregates) {
                a.check(input.schema());
            }
        }

        @Override
        public Schema schema() {
            Schema in = input.schema();
            List<Schema.Field> fields = new ArrayList<>();
            for (int c : groupColumns) {
                fields.add(in.fields().get(c));
            }
            for (AggregateCall a : aggregates) {
                boolean nullable = a.function() != AggregateFunction.COUNT_STAR
                        && a.function() != AggregateFunction.COUNT;
                fields.add(new Schema.Field(a.name(), a.type(in), nullable));
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /**
     * A hash join: collects the build input into a hash table, then pulls the
     * probe input and emits, per probe row, the probe row followed by each
     * matching build row, in the order of the build input. Keys match if
     * they are equal after {@code Keys.canonical}; a key with a {@code NULL}
     * value matches nothing. A left outer join emits an unmatched probe row
     * with {@code NULL} for the build columns.
     *
     * @param probe the probe input
     * @param build the build input
     * @param probeKeys the key columns of the probe input
     * @param buildKeys the key columns of the build input, of the same types
     * @param type the join type
     */
    record HashJoin(PlanNode probe, PlanNode build, List<Integer> probeKeys, List<Integer> buildKeys, JoinType type)
            implements PlanNode {
        public HashJoin {
            probeKeys = List.copyOf(probeKeys);
            buildKeys = List.copyOf(buildKeys);
            Objects.requireNonNull(type, "type");
            if (probeKeys.isEmpty() || probeKeys.size() != buildKeys.size()) {
                throw new IllegalArgumentException("Join keys " + probeKeys + " / " + buildKeys);
            }
            for (int i = 0; i < probeKeys.size(); i++) {
                checkColumn(probe.schema(), probeKeys.get(i));
                checkColumn(build.schema(), buildKeys.get(i));
                TypeRules.require(probe.schema().type(probeKeys.get(i)), build.schema().type(buildKeys.get(i)),
                        "Join key " + i);
            }
        }

        @Override
        public Schema schema() {
            List<Schema.Field> fields = new ArrayList<>(probe.schema().fields());
            for (Schema.Field f : build.schema().fields()) {
                fields.add(type == JoinType.LEFT_OUTER ? new Schema.Field(f.name(), f.type(), true) : f);
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return List.of(probe, build);
        }
    }

    /**
     * A sort key.
     *
     * @param column the column
     * @param ascending whether ascending
     * @param nullsFirst whether {@code NULL} comes before all values
     */
    record SortKey(int column, boolean ascending, boolean nullsFirst) {
    }

    /**
     * Sorts its whole input, stably, by keys in a total order: floating-point
     * values as {@code Double.compare} orders them ({@code -0.0 < 0.0}, NaN
     * last), decimals by value, strings by {@code String.compareTo},
     * {@code false < true}.
     *
     * @param input the input
     * @param keys the sort keys, the most significant first
     */
    record Sort(PlanNode input, List<SortKey> keys) implements PlanNode {
        public Sort {
            keys = List.copyOf(keys);
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("Sort without keys");
            }
            for (SortKey k : keys) {
                checkColumn(input.schema(), k.column());
            }
        }

        @Override
        public Schema schema() {
            return input.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /**
     * The first rows of its input; after the last one, it pulls no more rows.
     *
     * @param input the input
     * @param count the number of rows, at least 1
     */
    record Limit(PlanNode input, int count) implements PlanNode {
        public Limit {
            Objects.requireNonNull(input, "input");
            if (count < 1) {
                throw new IllegalArgumentException("count: " + count);
            }
        }

        @Override
        public Schema schema() {
            return input.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /**
     * {@code UNION ALL}: collects all rows of its inputs, in the order of the
     * inputs, then emits them.
     *
     * @param inputs the inputs, with the same column types
     */
    record Union(List<PlanNode> inputs) implements PlanNode {
        public Union {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Union without inputs");
            }
            Schema first = inputs.get(0).schema();
            for (PlanNode in : inputs) {
                if (in.schema().size() != first.size()) {
                    throw new IllegalArgumentException("Union of different widths");
                }
                for (int i = 0; i < first.size(); i++) {
                    TypeRules.require(first.type(i), in.schema().type(i), "Union column " + i);
                }
            }
        }

        @Override
        public Schema schema() {
            Schema first = inputs.get(0).schema();
            List<Schema.Field> fields = new ArrayList<>();
            for (int i = 0; i < first.size(); i++) {
                boolean nullable = false;
                for (PlanNode in : inputs) {
                    nullable |= in.schema().nullable(i);
                }
                fields.add(new Schema.Field(first.fields().get(i).name(), first.type(i), nullable));
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return inputs;
        }
    }

    private static void checkColumn(Schema schema, int column) {
        if (column < 0 || column >= schema.size()) {
            throw new IllegalArgumentException("No column " + column + " in " + schema);
        }
    }

    /**
     * An output column of a {@link Project}.
     *
     * @param name the name of the column
     * @param expression the value
     */
    record NamedExpression(String name, Expression expression) {
        public NamedExpression {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /**
     * Computes one output row per input row.
     *
     * @param input the input
     * @param columns the output columns, expressions over the input
     */
    record Project(PlanNode input, List<NamedExpression> columns) implements PlanNode {
        public Project {
            columns = List.copyOf(columns);
            for (NamedExpression c : columns) {
                input.schema().check(c.expression());
            }
        }

        @Override
        public Schema schema() {
            List<Schema.Field> fields = new ArrayList<>();
            for (NamedExpression c : columns) {
                fields.add(new Schema.Field(c.name(), c.expression().type()));
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }
}
