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

import io.github.janinolts.qsim.runtime.AggregateHandler;
import io.github.janinolts.qsim.runtime.KeyFunction;
import io.github.janinolts.qsim.runtime.RowComparator;
import io.github.janinolts.qsim.runtime.RowCondition;
import io.github.janinolts.qsim.runtime.RowFunction;
import io.github.janinolts.qsim.runtime.RowKey;
import io.github.janinolts.qsim.runtime.linq.DataContext;
import io.github.janinolts.qsim.runtime.linq.Enumerable;
import io.github.janinolts.qsim.runtime.linq.Enumerables;

import java.util.List;

/**
 * The operators of a query in the style of operator classes, and how they are
 * combined with the generated functions: like a runtime of Flink, the
 * operators are fixed code (here the operators of the runtime library), the
 * functions are generated classes, one per function.
 */
public final class OperatorGraph {

    private OperatorGraph() {
    }

    /** An operator; the integers are indexes of units of the query. */
    public sealed interface Node {
    }

    /**
     * Reads the table of a scan.
     *
     * @param scan the index of the scan
     * @param table the name of the table
     */
    public record Scan(int scan, String table) implements Node {
    }

    /** Keeps the rows for which a {@code RowCondition} holds. */
    public record Filter(Node input, int condition) implements Node {
    }

    /** Maps each row with a {@code RowFunction}. */
    public record Project(Node input, int function) implements Node {
    }

    /** The first rows. */
    public record Limit(Node input, int count) implements Node {
    }

    /** Collects all rows of the inputs. */
    public record Union(List<Node> inputs, String label) implements Node {
        public Union {
            inputs = List.copyOf(inputs);
        }
    }

    /** Sorts with a {@code RowComparator}. */
    public record Sort(Node input, int comparator, String label) implements Node {
    }

    /**
     * A hash join with two {@code KeyFunction}s.
     *
     * @param buildWidth the number of columns of the build input, for the
     *        {@code NULL}s of an unmatched probe row
     */
    public record Join(Node probe, Node build, int probeKey, int buildKey, boolean leftOuter, int buildWidth)
            implements Node {
    }

    /** Groups with a {@code KeyFunction} and aggregates with an {@code AggregateHandler}. */
    public record Aggregate(Node input, int key, int handler, String label, boolean global) implements Node {
    }

    /**
     * Combines the operators of a graph with the instances of the generated
     * functions.
     *
     * @param functions the instances, by the index of their unit
     */
    public static Enumerable build(Node node, Object[] functions, DataContext root) {
        return switch (node) {
            case Scan s -> Enumerables.scan(root.position(), s.table(), root.table(s.scan()));
            case Filter f -> {
                RowCondition condition = (RowCondition) functions[f.condition()];
                yield Enumerables.where(build(f.input(), functions, root), a -> condition.test((Object[]) a));
            }
            case Project p -> {
                RowFunction function = (RowFunction) functions[p.function()];
                yield Enumerables.select(build(p.input(), functions, root), a -> function.apply((Object[]) a));
            }
            case Limit l -> Enumerables.take(build(l.input(), functions, root), l.count());
            case Union u -> {
                Enumerable[] inputs = new Enumerable[u.inputs().size()];
                for (int i = 0; i < inputs.length; i++) {
                    inputs[i] = build(u.inputs().get(i), functions, root);
                }
                yield Enumerables.union(root.position(), u.label(), inputs);
            }
            case Sort s -> {
                RowComparator comparator = (RowComparator) functions[s.comparator()];
                yield Enumerables.orderBy(root.position(), s.label(), build(s.input(), functions, root),
                        (a, b) -> comparator.compare((Object[]) a, (Object[]) b));
            }
            case Join j -> {
                KeyFunction probeKey = (KeyFunction) functions[j.probeKey()];
                KeyFunction buildKey = (KeyFunction) functions[j.buildKey()];
                yield Enumerables.hashJoin(build(j.probe(), functions, root), build(j.build(), functions, root),
                        a -> joinKey(probeKey.key((Object[]) a)), a -> joinKey(buildKey.key((Object[]) a)),
                        (l, r) -> concat((Object[]) l, r == null ? new Object[j.buildWidth()] : (Object[]) r),
                        j.leftOuter());
            }
            case Aggregate a -> {
                KeyFunction key = (KeyFunction) functions[a.key()];
                AggregateHandler handler = (AggregateHandler) functions[a.handler()];
                yield Enumerables.groupBy(root.position(), a.label(), build(a.input(), functions, root),
                        row -> key.key((Object[]) row), handler::createAccumulator,
                        (accumulator, row) -> {
                            handler.accumulate(accumulator, (Object[]) row);
                            return accumulator;
                        },
                        (k, accumulator) -> concat(((RowKey) k).values(), handler.getValue(accumulator)),
                        a.global(), new RowKey(new Object[0]));
            }
        };
    }

    /** A join key with a {@code NULL} value matches nothing. */
    private static RowKey joinKey(RowKey key) {
        return key.hasNull() ? null : key;
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] result = new Object[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
