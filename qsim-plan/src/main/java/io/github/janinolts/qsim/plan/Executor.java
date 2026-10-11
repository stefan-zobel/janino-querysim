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
import io.github.janinolts.qsim.runtime.DecimalFunctions;
import io.github.janinolts.qsim.runtime.Position;
import io.github.janinolts.qsim.runtime.RowKey;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The execution of plans by the interpreter: every operator is a cursor that
 * pulls the rows of its inputs one at a time (the Volcano model). An operator
 * that collects its input does so on its first pull: an aggregation, a sort, a
 * union, and the build side of a join, whose build input is consumed before
 * its probe input is pulled. Sources record their current row in a
 * {@link Position}: a scan its table and row index, an operator that collects
 * its input {@code node:<id>} and the index of its output row.
 */
final class Executor {

    /** A cursor: returns the next row, or {@code null} at the end. */
    private interface Cursor {
        Object[] next();
    }

    private final Interpreter interpreter;
    private final boolean ansi;
    private final Map<String, Table> tables;
    private final PlanIds ids;
    private final Position position = new Position();

    Executor(Interpreter interpreter, boolean ansi, Map<String, Table> tables, PlanIds ids) {
        this.interpreter = interpreter;
        this.ansi = ansi;
        this.tables = tables;
        this.ids = ids;
    }

    /** Runs the plan; a row that throws ends the run with a {@link RowException}. */
    List<Object[]> run(PlanNode plan) {
        Cursor cursor = cursor(plan);
        List<Object[]> output = new ArrayList<>();
        try {
            for (Object[] row = cursor.next(); row != null; row = cursor.next()) {
                output.add(row);
            }
        } catch (RuntimeException e) {
            throw new RowException(position.source(), position.row(), e);
        }
        return output;
    }

    private Cursor cursor(PlanNode node) {
        return switch (node) {
            case PlanNode.Scan s -> scan(s);
            case PlanNode.Filter f -> {
                Cursor in = cursor(f.input());
                yield () -> {
                    for (Object[] row = in.next(); row != null; row = in.next()) {
                        if (Boolean.TRUE.equals(interpreter.evaluate(f.condition(), row))) {
                            return row;
                        }
                    }
                    return null;
                };
            }
            case PlanNode.Project p -> {
                Cursor in = cursor(p.input());
                yield () -> {
                    Object[] row = in.next();
                    if (row == null) {
                        return null;
                    }
                    Object[] result = new Object[p.columns().size()];
                    for (int c = 0; c < result.length; c++) {
                        result[c] = interpreter.evaluate(p.columns().get(c).expression(), row);
                    }
                    return result;
                };
            }
            case PlanNode.Limit l -> {
                Cursor in = cursor(l.input());
                int[] count = {0};
                yield () -> {
                    if (count[0] >= l.count()) {
                        return null;
                    }
                    Object[] row = in.next();
                    if (row != null) {
                        count[0]++;
                    }
                    return row;
                };
            }
            case PlanNode.HashAggregate a -> collected(a, () -> aggregate(a));
            case PlanNode.Sort s -> collected(s, () -> sort(s));
            case PlanNode.Union u -> collected(u, () -> {
                List<Object[]> all = new ArrayList<>();
                for (PlanNode input : u.inputs()) {
                    all.addAll(drain(cursor(input)));
                }
                return all;
            });
            case PlanNode.HashJoin j -> join(j);
        };
    }

    private Cursor scan(PlanNode.Scan s) {
        Table table = Objects.requireNonNull(tables.get(s.table()), () -> "No table " + s.table());
        if (!table.schema().equals(s.schema())) {
            throw new IllegalArgumentException("Table " + s.table() + " has the schema " + table.schema()
                    + ", the scan expects " + s.schema());
        }
        int[] index = {-1};
        return () -> {
            if (index[0] + 1 >= table.size()) {
                return null;
            }
            index[0]++;
            position.set(s.table(), index[0]);
            return table.rows().get(index[0]);
        };
    }

    private interface Collector {
        List<Object[]> collect();
    }

    /** An operator that collects its input on the first pull, then emits rows as a source. */
    private Cursor collected(PlanNode node, Collector collector) {
        String label = ids.sourceLabel(node);
        Object[][] state = {null};
        int[] index = {-1};
        return () -> {
            if (state[0] == null) {
                state[0] = collector.collect().toArray();
            }
            if (index[0] + 1 >= state[0].length) {
                return null;
            }
            index[0]++;
            position.set(label, index[0]);
            return (Object[]) state[0][index[0]];
        };
    }

    private static List<Object[]> drain(Cursor cursor) {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row = cursor.next(); row != null; row = cursor.next()) {
            rows.add(row);
        }
        return rows;
    }

    private Cursor join(PlanNode.HashJoin j) {
        Cursor probe = cursor(j.probe());
        Cursor build = cursor(j.build());
        int buildWidth = j.build().schema().size();
        return new Cursor() {
            private Map<RowKey, List<Object[]>> table;
            private Object[] probeRow;
            private List<Object[]> matches;
            private int next;
            private boolean pendingOuter;

            @Override
            public Object[] next() {
                if (table == null) {
                    table = new HashMap<>();
                    for (Object[] row = build.next(); row != null; row = build.next()) {
                        RowKey key = key(row, j.buildKeys());
                        if (!key.hasNull()) {
                            table.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                        }
                    }
                }
                while (true) {
                    if (matches != null && next < matches.size()) {
                        return concat(probeRow, matches.get(next++));
                    }
                    if (pendingOuter) {
                        pendingOuter = false;
                        return concat(probeRow, new Object[buildWidth]);
                    }
                    probeRow = probe.next();
                    if (probeRow == null) {
                        return null;
                    }
                    RowKey key = key(probeRow, j.probeKeys());
                    matches = key.hasNull() ? null : table.get(key);
                    next = 0;
                    pendingOuter = j.type() == PlanNode.JoinType.LEFT_OUTER && matches == null;
                }
            }
        };
    }

    private static RowKey key(Object[] row, List<Integer> columns) {
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row[columns.get(i)];
        }
        return new RowKey(values);
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] result = new Object[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private List<Object[]> sort(PlanNode.Sort s) {
        List<Object[]> rows = drain(cursor(s.input()));
        Schema schema = s.input().schema();
        Comparator<Object[]> comparator = (a, b) -> {
            for (PlanNode.SortKey k : s.keys()) {
                Object x = a[k.column()];
                Object y = b[k.column()];
                int c;
                if (x == null || y == null) {
                    c = x == null && y == null ? 0 : (x == null) == k.nullsFirst() ? -1 : 1;
                } else {
                    c = compareValues(x, y, schema.type(k.column()));
                    c = k.ascending() ? c : -c;
                }
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
        rows.sort(comparator);
        return rows;
    }

    /** The total order of two non-null values of a type, for sorting and for MIN and MAX. */
    static int compareValues(Object x, Object y, DataType type) {
        return switch (type) {
            case BOOLEAN -> Boolean.compare((Boolean) x, (Boolean) y);
            case FLOAT -> Float.compare((Float) x, (Float) y);
            case DOUBLE -> Double.compare((Double) x, (Double) y);
            case STRING -> ((String) x).compareTo((String) y);
            case DECIMAL -> ((BigDecimal) x).compareTo((BigDecimal) y);
            default -> Long.compare(((Number) x).longValue(), ((Number) y).longValue());
        };
    }

    private List<Object[]> aggregate(PlanNode.HashAggregate a) {
        Cursor in = cursor(a.input());
        Schema schema = a.input().schema();
        Map<RowKey, Accumulator[]> groups = new LinkedHashMap<>();
        for (Object[] row = in.next(); row != null; row = in.next()) {
            RowKey key = key(row, a.groupColumns());
            Accumulator[] accumulators = groups.computeIfAbsent(key, k -> newAccumulators(a, schema));
            for (Accumulator acc : accumulators) {
                acc.add(row);
            }
        }
        if (groups.isEmpty() && a.groupColumns().isEmpty()) {
            groups.put(new RowKey(new Object[0]), newAccumulators(a, schema));
        }
        List<Object[]> output = new ArrayList<>();
        for (Map.Entry<RowKey, Accumulator[]> group : groups.entrySet()) {
            Object[] keyValues = group.getKey().values();
            Object[] row = new Object[keyValues.length + a.aggregates().size()];
            System.arraycopy(keyValues, 0, row, 0, keyValues.length);
            for (int i = 0; i < group.getValue().length; i++) {
                row[keyValues.length + i] = group.getValue()[i].result();
            }
            output.add(row);
        }
        return output;
    }

    private Accumulator[] newAccumulators(PlanNode.HashAggregate a, Schema schema) {
        Accumulator[] result = new Accumulator[a.aggregates().size()];
        for (int i = 0; i < result.length; i++) {
            AggregateCall call = a.aggregates().get(i);
            result[i] = new Accumulator(call, call.function() == PlanNode.AggregateFunction.COUNT_STAR ? null
                    : schema.type(call.column()));
        }
        return result;
    }

    /** The state of one aggregate function in one group. */
    private final class Accumulator {

        private final AggregateCall call;
        private final DataType type;
        private long count;
        private long longSum;
        private double doubleSum;
        private BigDecimal decimalSum = BigDecimal.ZERO;
        private Object extreme;

        Accumulator(AggregateCall call, DataType type) {
            this.call = call;
            this.type = type;
        }

        void add(Object[] row) {
            if (call.function() == PlanNode.AggregateFunction.COUNT_STAR) {
                count++;
                return;
            }
            Object v = row[call.column()];
            if (v == null) {
                return;
            }
            count++;
            switch (call.function()) {
                case SUM, AVG -> {
                    if (type == DataType.DECIMAL) {
                        decimalSum = decimalSum.add((BigDecimal) v);
                    } else if (call.function() == PlanNode.AggregateFunction.SUM && type.isIntegral()) {
                        long x = ((Number) v).longValue();
                        longSum = ansi ? Math.addExact(longSum, x) : longSum + x;
                    } else {
                        doubleSum += ((Number) v).doubleValue();
                    }
                }
                case MIN -> extreme = extreme == null || compareValues(v, extreme, type) < 0 ? v : extreme;
                case MAX -> extreme = extreme == null || compareValues(v, extreme, type) > 0 ? v : extreme;
                default -> {
                }
            }
        }

        Object result() {
            return switch (call.function()) {
                case COUNT_STAR, COUNT -> count;
                case SUM -> count == 0 ? null : type == DataType.DECIMAL ? decimalSum
                        : type.isIntegral() ? (Object) longSum : (Object) doubleSum;
                case AVG -> count == 0 ? null : type == DataType.DECIMAL
                        ? DecimalFunctions.divide(decimalSum, BigDecimal.valueOf(count)) : (Object) (doubleSum / count);
                case MIN, MAX -> extreme;
            };
        }
    }
}
