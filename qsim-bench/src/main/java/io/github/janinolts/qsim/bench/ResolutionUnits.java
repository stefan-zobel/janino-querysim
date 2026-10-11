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

package io.github.janinolts.qsim.bench;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.UnitKey;
import io.github.janinolts.qsim.runtime.AggregateHandler;
import io.github.janinolts.qsim.runtime.BufferedRowIterator;
import io.github.janinolts.qsim.runtime.ColumnBatch;
import io.github.janinolts.qsim.runtime.Conversions;
import io.github.janinolts.qsim.runtime.DecimalFunctions;
import io.github.janinolts.qsim.runtime.Evaluator;
import io.github.janinolts.qsim.runtime.GeneratedClass;
import io.github.janinolts.qsim.runtime.Identity;
import io.github.janinolts.qsim.runtime.KeyFunction;
import io.github.janinolts.qsim.runtime.Keys;
import io.github.janinolts.qsim.runtime.Position;
import io.github.janinolts.qsim.runtime.RowComparator;
import io.github.janinolts.qsim.runtime.RowCondition;
import io.github.janinolts.qsim.runtime.RowFunction;
import io.github.janinolts.qsim.runtime.RowKey;
import io.github.janinolts.qsim.runtime.StringFunctions;
import io.github.janinolts.qsim.runtime.linq.Bindable;
import io.github.janinolts.qsim.runtime.linq.DataContext;
import io.github.janinolts.qsim.runtime.linq.Enumerable;
import io.github.janinolts.qsim.runtime.linq.Enumerables;
import io.github.janinolts.qsim.runtime.linq.Enumerator;
import io.github.janinolts.qsim.runtime.linq.Function0;
import io.github.janinolts.qsim.runtime.linq.Function1;
import io.github.janinolts.qsim.runtime.linq.Function2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The units of P6, the class resolution: a class whose only method refers to
 * a given number of distinct runtime classes, or makes a given number of
 * calls of overloaded methods. Janino resolves every referenced type through
 * the parent class loader, and chooses among the overloads by the argument
 * types. These units do not come from plans; they are compiled through the
 * API path {@code CLASS_BODY} and never run.
 */
public final class ResolutionUnits {

    /** The levels of the series {@code classes}. */
    public static final List<Integer> CLASSES = List.of(1, 4, 16, 64);

    /** The levels of the series {@code overloads}. */
    public static final List<Integer> OVERLOADS = List.of(4, 16, 64, 256);

    /**
     * The referenced classes, alternately of the runtime of qsim and of the
     * JDK (Java 8), so that every level mixes both.
     */
    static final List<String> REFERENCED;

    static {
        List<String> runtime = List.of(AggregateHandler.class.getName(), BufferedRowIterator.class.getName(),
                ColumnBatch.class.getName(), Conversions.class.getName(), DecimalFunctions.class.getName(),
                Evaluator.class.getName(), GeneratedClass.class.getName(), Identity.class.getName(),
                KeyFunction.class.getName(), Keys.class.getName(), Position.class.getName(),
                RowComparator.class.getName(), RowCondition.class.getName(), RowFunction.class.getName(),
                RowKey.class.getName(), StringFunctions.class.getName(), Bindable.class.getName(),
                DataContext.class.getName(), Enumerable.class.getName(), Enumerables.class.getName(),
                Enumerator.class.getName(), Function0.class.getName(), Function1.class.getName(),
                Function2.class.getName());
        List<String> jdk = List.of("java.lang.Integer", "java.util.ArrayList", "java.math.BigDecimal",
                "java.util.HashMap", "java.lang.StringBuilder", "java.util.TreeMap", "java.lang.Number",
                "java.util.LinkedList", "java.io.Serializable", "java.util.HashSet", "java.lang.CharSequence",
                "java.util.TreeSet", "java.math.BigInteger", "java.util.ArrayDeque", "java.lang.Comparable",
                "java.util.PriorityQueue", "java.util.concurrent.ConcurrentHashMap", "java.util.BitSet",
                "java.lang.Iterable", "java.util.Optional", "java.util.Random", "java.util.UUID",
                "java.util.Locale", "java.util.Date", "java.lang.Runnable", "java.util.Calendar",
                "java.util.concurrent.atomic.AtomicLong", "java.util.Collection", "java.io.File",
                "java.util.Comparator", "java.nio.ByteBuffer", "java.util.Deque", "java.text.DecimalFormat",
                "java.util.Iterator", "java.util.concurrent.TimeUnit", "java.util.Vector", "java.io.InputStream",
                "java.util.Hashtable", "java.lang.Appendable", "java.util.StringJoiner");
        List<String> all = new ArrayList<>();
        for (int i = 0; all.size() < runtime.size() + jdk.size(); i++) {
            if (i < jdk.size()) {
                all.add(jdk.get(i));
            }
            if (i < runtime.size()) {
                all.add(runtime.get(i));
            }
        }
        REFERENCED = List.copyOf(all);
    }

    /** The arguments of the method of {@link #overloads}, by name; the calls cycle through them. */
    private static final List<String> ARGUMENTS = List.of("i", "l", "d", "f", "c", "z", "s", "o", "sh", "b");

    /** The numeric arguments, for {@code Math.max} and {@code Math.abs}. */
    private static final List<String> NUMBERS = List.of("i", "l", "d", "f", "sh", "b", "c");

    private ResolutionUnits() {
    }

    /** Returns the unit that refers to the first {@code n} classes of {@link #REFERENCED}. */
    public static GeneratedQuery classes(int n) {
        if (n < 1 || n > REFERENCED.size()) {
            throw new IllegalArgumentException("classes: " + n);
        }
        StringBuilder body = new StringBuilder("public int resolve(Object o) {\n    int h = 0;\n");
        for (int i = 0; i < n; i++) {
            body.append("    if (o instanceof ").append(REFERENCED.get(i)).append(") {\n        h += ").append(i + 1)
                    .append(";\n    }\n");
        }
        body.append("    return h;\n}\n");
        return query("Classes" + n, body.toString());
    }

    /**
     * Returns the unit with {@code n} calls of overloaded methods of the JDK:
     * {@code StringBuilder.append}, {@code String.valueOf},
     * {@code Math.max}, {@code Math.abs} and {@code Objects.hash}, with
     * arguments of changing types.
     */
    public static GeneratedQuery overloads(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("overloads: " + n);
        }
        StringBuilder body = new StringBuilder("public int resolve(int i, long l, double d, float f, char c,"
                + " boolean z, String s, Object o, short sh, byte b) {\n"
                + "    int h = 0;\n    StringBuilder sb = new StringBuilder();\n");
        for (int k = 0; k < n; k++) {
            String x = ARGUMENTS.get(k % ARGUMENTS.size());
            String p = NUMBERS.get(k % NUMBERS.size());
            String q = NUMBERS.get((k / NUMBERS.size() + k + 1) % NUMBERS.size());
            body.append(switch (k % 5) {
                case 0 -> "    sb.append(" + x + ");\n";
                case 1 -> "    h += String.valueOf(" + x + ").length();\n";
                case 2 -> "    h += Math.max(" + p + ", " + q + ");\n";
                case 3 -> "    h += Math.abs(" + p + ");\n";
                default -> "    h += java.util.Objects.hash(" + x + ", " + q + ");\n";
            });
        }
        body.append("    return h + sb.length();\n}\n");
        return query("Overloads" + n, body.toString());
    }

    /** Returns the unit key of a level, e.g. {@code resolution:classes:16/CLASS/STRICT/<hash>}. */
    public static UnitKey key(String series, int level, GeneratedQuery query) {
        return new UnitKey("resolution:" + series + ":" + level, "CLASS", "STRICT", hash(query.unit().body()));
    }

    private static GeneratedQuery query(String className, String body) {
        GeneratedUnit unit = GeneratedUnit.ofClass("qsim.generated", className, body).build();
        return new GeneratedQuery(List.of(unit), ApiPath.CLASS_BODY, new GeneratedQuery.Driver.Enumerable(),
                List.of(), false);
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
