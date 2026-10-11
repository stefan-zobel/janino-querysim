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

package io.github.janinolts.qsim.runtime.linq;

import io.github.janinolts.qsim.runtime.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The operators. Each is lazy: it does its work when its enumerator is moved
 * for the first time. An operator that collects its input (group, sort,
 * union) consumes all of it then; a hash join first consumes its build input
 * completely, then pulls its probe input. Sources record their rows in the
 * {@link Position}.
 */
public final class Enumerables {

    private Enumerables() {
    }

    /** The rows of a table; records each row as a row of the table. */
    public static Enumerable scan(final Position position, final String table, final Object[][] rows) {
        return new Enumerable() {
            public Enumerator enumerator() {
                return new Enumerator() {
                    private int index = -1;

                    public boolean moveNext() {
                        if (index + 1 < rows.length) {
                            index++;
                            position.set(table, index);
                            return true;
                        }
                        return false;
                    }

                    public Object current() {
                        return rows[index];
                    }
                };
            }
        };
    }

    /** The elements for which the predicate holds. */
    public static Enumerable where(final Enumerable input, final Predicate1 predicate) {
        return new Enumerable() {
            public Enumerator enumerator() {
                final Enumerator in = input.enumerator();
                return new Enumerator() {
                    public boolean moveNext() {
                        while (in.moveNext()) {
                            if (predicate.apply(in.current())) {
                                return true;
                            }
                        }
                        return false;
                    }

                    public Object current() {
                        return in.current();
                    }
                };
            }
        };
    }

    /** The results of a function of each element. */
    public static Enumerable select(final Enumerable input, final Function1 selector) {
        return new Enumerable() {
            public Enumerator enumerator() {
                final Enumerator in = input.enumerator();
                return new Enumerator() {
                    private Object current;

                    public boolean moveNext() {
                        if (in.moveNext()) {
                            current = selector.apply(in.current());
                            return true;
                        }
                        return false;
                    }

                    public Object current() {
                        return current;
                    }
                };
            }
        };
    }

    /** The first {@code count} elements; pulls no element of its input after the last one. */
    public static Enumerable take(final Enumerable input, final int count) {
        return new Enumerable() {
            public Enumerator enumerator() {
                final Enumerator in = input.enumerator();
                return new Enumerator() {
                    private int taken;

                    public boolean moveNext() {
                        if (taken >= count || !in.moveNext()) {
                            return false;
                        }
                        taken++;
                        return true;
                    }

                    public Object current() {
                        return in.current();
                    }
                };
            }
        };
    }

    /**
     * Groups the elements by key and aggregates each group. The keys must
     * implement {@code equals} and {@code hashCode}; the groups come out in
     * the order of their first element.
     *
     * @param label the label of this operator in the position
     * @param keySelector the key of an element
     * @param accumulatorInit creates the accumulator of a new group
     * @param accumulatorAdder adds an element to an accumulator and returns it
     * @param resultSelector computes the output row from a key and an accumulator
     * @param global whether there are no grouping keys: then an empty input
     *        gives one group with the key {@code emptyKey}
     * @param emptyKey the key of that group
     */
    public static Enumerable groupBy(final Position position, final String label, final Enumerable input,
            final Function1 keySelector, final Function0 accumulatorInit, final Function2 accumulatorAdder,
            final Function2 resultSelector, final boolean global, final Object emptyKey) {
        return new Enumerable() {
            public Enumerator enumerator() {
                return new Enumerator() {
                    private List<Map.Entry<Object, Object>> groups;
                    private int index = -1;
                    private Object current;

                    public boolean moveNext() {
                        if (groups == null) {
                            Map<Object, Object> map = new LinkedHashMap<Object, Object>();
                            Enumerator in = input.enumerator();
                            while (in.moveNext()) {
                                Object element = in.current();
                                Object key = keySelector.apply(element);
                                Object accumulator = map.containsKey(key) ? map.get(key) : accumulatorInit.apply();
                                map.put(key, accumulatorAdder.apply(accumulator, element));
                            }
                            if (map.isEmpty() && global) {
                                map.put(emptyKey, accumulatorInit.apply());
                            }
                            groups = new ArrayList<Map.Entry<Object, Object>>(map.entrySet());
                        }
                        if (index + 1 < groups.size()) {
                            index++;
                            position.set(label, index);
                            Map.Entry<Object, Object> group = groups.get(index);
                            current = resultSelector.apply(group.getKey(), group.getValue());
                            return true;
                        }
                        return false;
                    }

                    public Object current() {
                        return current;
                    }
                };
            }
        };
    }

    /**
     * A hash join: consumes the build input completely, then pulls the probe
     * input. A key selector returns {@code null} for a key with a {@code NULL}
     * value, which matches nothing. The matches of a probe element come out
     * in the order of the build input.
     *
     * @param resultSelector the output row of a probe element and a build
     *        element, which is {@code null} for an unmatched probe element of a
     *        left outer join
     * @param leftOuter whether an unmatched probe element produces a row
     */
    public static Enumerable hashJoin(final Enumerable probe, final Enumerable build, final Function1 probeKey,
            final Function1 buildKey, final Function2 resultSelector, final boolean leftOuter) {
        return new Enumerable() {
            public Enumerator enumerator() {
                return new Enumerator() {
                    private Map<Object, List<Object>> table;
                    private Enumerator probeElements;
                    private Object probeElement;
                    private List<Object> matches;
                    private int next;
                    private boolean pendingOuter;
                    private Object current;

                    public boolean moveNext() {
                        if (table == null) {
                            table = new HashMap<Object, List<Object>>();
                            Enumerator in = build.enumerator();
                            while (in.moveNext()) {
                                Object element = in.current();
                                Object key = buildKey.apply(element);
                                if (key != null) {
                                    List<Object> list = table.get(key);
                                    if (list == null) {
                                        list = new ArrayList<Object>();
                                        table.put(key, list);
                                    }
                                    list.add(element);
                                }
                            }
                            probeElements = probe.enumerator();
                        }
                        while (true) {
                            if (matches != null && next < matches.size()) {
                                current = resultSelector.apply(probeElement, matches.get(next++));
                                return true;
                            }
                            if (pendingOuter) {
                                pendingOuter = false;
                                current = resultSelector.apply(probeElement, null);
                                return true;
                            }
                            if (!probeElements.moveNext()) {
                                return false;
                            }
                            probeElement = probeElements.current();
                            Object key = probeKey.apply(probeElement);
                            matches = key == null ? null : table.get(key);
                            next = 0;
                            pendingOuter = leftOuter && matches == null;
                        }
                    }

                    public Object current() {
                        return current;
                    }
                };
            }
        };
    }

    /** Sorts all elements, stably, with the comparator. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Enumerable orderBy(final Position position, final String label, final Enumerable input,
            final Comparator comparator) {
        return new Enumerable() {
            public Enumerator enumerator() {
                return new Enumerator() {
                    private List<Object> sorted;
                    private int index = -1;

                    public boolean moveNext() {
                        if (sorted == null) {
                            sorted = collect(input);
                            Collections.sort(sorted, comparator);
                        }
                        if (index + 1 < sorted.size()) {
                            index++;
                            position.set(label, index);
                            return true;
                        }
                        return false;
                    }

                    public Object current() {
                        return sorted.get(index);
                    }
                };
            }
        };
    }

    /** All elements of the inputs, in order; collects them first. */
    public static Enumerable union(final Position position, final String label, final Enumerable[] inputs) {
        return new Enumerable() {
            public Enumerator enumerator() {
                return new Enumerator() {
                    private List<Object> all;
                    private int index = -1;

                    public boolean moveNext() {
                        if (all == null) {
                            all = new ArrayList<Object>();
                            for (Enumerable input : inputs) {
                                all.addAll(collect(input));
                            }
                        }
                        if (index + 1 < all.size()) {
                            index++;
                            position.set(label, index);
                            return true;
                        }
                        return false;
                    }

                    public Object current() {
                        return all.get(index);
                    }
                };
            }
        };
    }

    /** Returns all elements in a list. */
    public static List<Object> collect(Enumerable input) {
        List<Object> list = new ArrayList<Object>();
        Enumerator in = input.enumerator();
        while (in.moveNext()) {
            list.add(in.current());
        }
        return list;
    }
}
