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

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.codegen.CodegenConfig;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.codegen.Generator;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.harness.UnitCase;
import io.github.janinolts.qsim.harness.Workloads;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The measurement points of P1 to P10. A point is one unit and what one
 * operation does with it; each point is measured in each mode. The points of
 * a measurement are the same in every JVM, so that a fork can find a point by
 * its ID.
 */
public final class Measurements {

    /** The measurements in the order of the reports. */
    public static final List<String> ALL = List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10");

    /** The rows of the data of P9, the runtime of the generated code. */
    public static final int RUN_ROWS = 1024;

    /** The thread counts of P5, up to the number of cores. */
    public static final List<Integer> THREADS = List.of(1, 2, 4, 8, 16);

    /**
     * The scenarios of the measurements that take a few points by default
     * (P3, P4, P5, P7): one per style that is the main style of a scenario
     * besides {@code EXPRESSION}; P4 adds {@code Q_TINY}, P5 takes only the
     * first.
     */
    static final List<String> REPRESENTATIVE = List.of("Q_FILTER_PROJECT", "Q_JOIN_AGG", "Q_STRING");

    /** The main style of each scenario, for the default extent of P1: every style occurs. */
    static final Map<String, Style> MAIN_STYLES = Map.of(
            "Q_TINY", Style.EXPRESSION,
            "Q_FILTER_PROJECT", Style.WHOLE_STAGE,
            "Q_AGG", Style.WHOLE_STAGE,
            "Q_JOIN_AGG", Style.ENUMERABLE,
            "Q_CASE_HEAVY", Style.WHOLE_STAGE,
            "Q_STRING", Style.OPERATOR_CLASSES,
            "Q_DECIMAL", Style.ENUMERABLE,
            "Q_SORT", Style.OPERATOR_CLASSES,
            "Q_ANSI", Style.WHOLE_STAGE);

    /** A phase of a compilation, for P3. */
    public enum Phase {
        PARSE, COMPILE, LOAD, INSTANTIATE;

        /** Returns the name in point IDs, e.g. {@code parse}. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The debugging information of P7. */
    public enum Debug {
        NONE(false, false, false), LINES(false, true, false), ALL(true, true, true);

        private final boolean source;
        private final boolean lines;
        private final boolean vars;

        Debug(boolean source, boolean lines, boolean vars) {
            this.source = source;
            this.lines = lines;
            this.vars = vars;
        }

        /** Returns the name in point IDs, e.g. {@code lines}. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Returns the options with this debugging information. */
        public CompileOptions apply(CompileOptions options) {
            return options.withDebugging(source, lines, vars);
        }
    }

    /** What one operation of a point does. */
    public sealed interface Work {

        /** Compile, load and initialize, with the given debugging information: P1, P2, P6, P7. */
        record Compile(Debug debug) implements Work {
        }

        /** One phase of the compilation: P3. */
        record OnePhase(Phase phase) implements Work {
        }

        /** Compile, load and initialize in each of the threads, each with compiler instances of its own: P5. */
        record Threads(int threads) implements Work {
        }

        /** The first compilation in a fresh JVM, up to the first instance: P4. */
        record ColdStart() implements Work {
        }

        /** Run the code compiled by each arm over the data: P9. */
        record Run() implements Work {
        }

        /**
         * Load the class files of the baseline with verification: as shipped
         * in arm A, with target version 8 in the others: P10.
         */
        record Load() implements Work {
        }
    }

    /**
     * A measurement point.
     *
     * @param measurement the measurement, e.g. {@code P1}
     * @param id the point within it, e.g. {@code Q_TINY:ROWS/EXPRESSION}
     * @param unitKey the key of the unit, for the ledger
     * @param work what one operation does
     * @param generator generates the code of the unit, once
     * @param data creates the data, once; used by P9
     * @param attributes the parameters of the point for the reports, e.g.
     *        the series and the level of P2
     */
    public record Point(String measurement, String id, String unitKey, Work work, Supplier<GeneratedQuery> generator,
            Supplier<Map<String, Table>> data, Map<String, String> attributes) {

        public Point {
            Objects.requireNonNull(measurement, "measurement");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(unitKey, "unitKey");
            Objects.requireNonNull(work, "work");
            generator = memoize(generator);
            data = memoize(data);
            attributes = Map.copyOf(attributes);
        }

        /** Returns the generated code of the unit. */
        public GeneratedQuery generate() {
            return generator.get();
        }

        /** Returns the data. */
        public Map<String, Table> tables() {
            return data.get();
        }

        /** Returns the length of the generated source text, all units together. */
        public int sourceChars() {
            return generate().units().stream().mapToInt(u -> u.body().length()).sum();
        }
    }

    private Measurements() {
    }

    /**
     * Returns the points of a measurement.
     *
     * @param extended whether to take the full extent instead of the default
     * @param patterns {@code null} for all points, or comma-separated
     *        patterns of point IDs in which {@code *} matches any text
     */
    public static List<Point> of(String measurement, boolean extended, String patterns) {
        List<Point> points = switch (measurement) {
            case "P1" -> p1(extended);
            case "P2" -> p2();
            case "P3" -> p3(extended);
            case "P4" -> p4(extended);
            case "P5" -> p5(extended);
            case "P6" -> p6();
            case "P7" -> p7(extended);
            case "P8" -> p8(extended);
            case "P9" -> p9(extended);
            case "P10" -> p10(extended);
            default -> throw new IllegalArgumentException("Unknown measurement: " + measurement + " (P1 to P10)");
        };
        if (patterns == null) {
            return points;
        }
        List<Pattern> regexes = new ArrayList<>();
        for (String p : patterns.split(",")) {
            if (!p.isBlank()) {
                regexes.add(Pattern.compile(Pattern.quote(p.strip()).replace("*", "\\E.*\\Q")));
            }
        }
        return points.stream().filter(p -> regexes.stream().anyMatch(r -> r.matcher(p.id()).matches())).toList();
    }

    /**
     * Returns the points of P1, the compile time per scenario, without ANSI
     * ({@code Q_ANSI} with ANSI): by default each scenario in its main style
     * in size M; extended, every fitting style in every size.
     */
    public static List<Point> p1(boolean extended) {
        return scenarioPoints("P1", extended, null, new Work.Compile(Debug.NONE), Map.of());
    }

    /** Returns the points of P2: every level of every scaling series. */
    public static List<Point> p2() {
        List<Point> points = new ArrayList<>();
        for (Series series : Series.values()) {
            for (int level : series.levels()) {
                points.add(seriesPoint("P2", series, level, new Work.Compile(Debug.NONE), Series.ROWS));
            }
        }
        return points;
    }

    /** Returns the points of P3: each phase of the representative scenarios; extended, of the points of P1. */
    public static List<Point> p3(boolean extended) {
        List<Point> points = new ArrayList<>();
        for (Point base : extended ? p1(false) : representative("P3", false)) {
            for (Phase phase : Phase.values()) {
                points.add(derived(base, "P3", "/" + phase.label(), new Work.OnePhase(phase),
                        Map.of("phase", phase.label())));
            }
        }
        return points;
    }

    /** Returns the points of P4: {@code Q_TINY} and the representative scenarios; extended, the points of P1. */
    public static List<Point> p4(boolean extended) {
        List<Point> points = new ArrayList<>();
        for (Point base : extended ? p1(false) : representative("P4", true)) {
            points.add(derived(base, "P4", "", new Work.ColdStart(), Map.of()));
        }
        return points;
    }

    /** Returns the points of P5: 1 to 16 threads, up to the cores, for the first representative scenario. */
    public static List<Point> p5(boolean extended) {
        List<Point> bases = extended ? representative("P5", false) : representative("P5", false).subList(0, 1);
        int cores = Runtime.getRuntime().availableProcessors();
        List<Point> points = new ArrayList<>();
        for (Point base : bases) {
            for (int threads : THREADS) {
                if (threads <= Math.max(1, cores)) {
                    points.add(derived(base, "P5", "/threads=" + threads, new Work.Threads(threads),
                            Map.of("threads", Integer.toString(threads))));
                }
            }
        }
        return points;
    }

    /** Returns the points of P6: the referenced classes and the overloaded calls. */
    public static List<Point> p6() {
        List<Point> points = new ArrayList<>();
        for (int n : ResolutionUnits.CLASSES) {
            GeneratedQuery q = ResolutionUnits.classes(n);
            points.add(new Point("P6", "classes:" + n, ResolutionUnits.key("classes", n, q).toString(),
                    new Work.Compile(Debug.NONE), () -> q, Map::of, Map.of("series", "classes", "level",
                    Integer.toString(n))));
        }
        for (int n : ResolutionUnits.OVERLOADS) {
            GeneratedQuery q = ResolutionUnits.overloads(n);
            points.add(new Point("P6", "overloads:" + n, ResolutionUnits.key("overloads", n, q).toString(),
                    new Work.Compile(Debug.NONE), () -> q, Map::of, Map.of("series", "overloads", "level",
                    Integer.toString(n))));
        }
        return points;
    }

    /** Returns the points of P7: each debugging setting for the representative scenarios; extended, for P1. */
    public static List<Point> p7(boolean extended) {
        List<Point> points = new ArrayList<>();
        for (Point base : extended ? p1(false) : representative("P7", false)) {
            for (Debug debug : Debug.values()) {
                points.add(derived(base, "P7", "/debug=" + debug.label(), new Work.Compile(debug),
                        Map.of("debug", debug.label())));
            }
        }
        return points;
    }

    /** Returns the points of P8, the class files: the units of P1 and P2. */
    public static List<Point> p8(boolean extended) {
        List<Point> points = new ArrayList<>();
        for (Point p : p1(extended)) {
            points.add(derived(p, "P8", "", p.work(), Map.of()));
        }
        for (Point p : p2()) {
            points.add(derived(p, "P8", "", p.work(), p.attributes()));
        }
        return points;
    }

    /**
     * Returns the candidates of P9, the runtime of the generated code: the
     * units of P1 and P2 with more rows. Only those whose method bodies differ
     * between baseline and candidate (level L3) are measured; the command
     * {@code bench} selects them.
     */
    public static List<Point> p9(boolean extended) {
        List<Point> points = new ArrayList<>(scenarioPoints("P9", extended, null, new Work.Run(),
                Map.of("rows", Integer.toString(RUN_ROWS))));
        for (Series series : Series.values()) {
            for (int level : series.levels()) {
                points.add(seriesPoint("P9", series, level, new Work.Run(), RUN_ROWS));
            }
        }
        return points;
    }

    /**
     * Returns the candidates of P10, the target version: the points of P1.
     * Only units whose class files of the baseline load in both
     * configurations are measured; the command {@code bench} selects them.
     */
    public static List<Point> p10(boolean extended) {
        List<Point> points = new ArrayList<>();
        for (Point p : p1(extended)) {
            points.add(derived(p, "P10", "", new Work.Load(), Map.of()));
        }
        return points;
    }

    private static List<Point> representative(String measurement, boolean withTiny) {
        List<Point> points = new ArrayList<>();
        for (Point p : p1(false)) {
            String scenario = p.id().substring(0, p.id().indexOf(':'));
            if (REPRESENTATIVE.contains(scenario) || withTiny && scenario.equals("Q_TINY")) {
                points.add(p);
            }
        }
        if (points.isEmpty()) {
            throw new IllegalStateException("No representative points for " + measurement);
        }
        return points;
    }

    private static List<Point> scenarioPoints(String measurement, boolean extended, String only, Work work,
            Map<String, String> attributes) {
        List<Point> points = new ArrayList<>();
        PlanNode.Layout layout = PlanNode.Layout.ROWS;
        int rows = work instanceof Work.Run ? RUN_ROWS : 16;
        for (String name : Scenario.NAMES) {
            if (only != null && !only.equals(name)) {
                continue;
            }
            List<Scenario.Size> sizes = name.equals("Q_TINY") ? List.of(Scenario.Size.M)
                    : extended ? List.of(Scenario.Size.values()) : List.of(Scenario.Size.M);
            for (Scenario.Size size : sizes) {
                Scenario scenario = Scenario.of(name, size, layout);
                for (Style style : Workloads.STYLES) {
                    if (Generator.supports(style, scenario.plan()) && (extended || MAIN_STYLES.get(name) == style)) {
                        boolean ansi = scenario.name().equals("Q_ANSI");
                        CodegenConfig config = Workloads.config(scenario.name(), style, ansi,
                                Workloads.SCENARIO_SEED);
                        String workload = Workloads.workload(name, size, layout);
                        // The data do not change the code; the unit gets none, the point creates them when needed.
                        UnitCase unit = UnitCase.of(workload, scenario.plan(), Map.of(), config);
                        points.add(new Point(measurement, workload + "/" + style, unit.key().toString(), work,
                                unit::generate, () -> scenario.data(Workloads.SCENARIO_SEED, rows), attributes));
                    }
                }
            }
        }
        return points;
    }

    private static Point seriesPoint(String measurement, Series series, int level, Work work, int rows) {
        UnitCase unit = series.unit(level);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("series", series.label());
        attributes.put("level", Integer.toString(level));
        return new Point(measurement, series.label() + ":" + level + "/" + series.style(), unit.key().toString(), work,
                unit::generate, () -> series.scenario(level).data(Workloads.SCENARIO_SEED, rows), attributes);
    }

    private static Point derived(Point base, String measurement, String suffix, Work work,
            Map<String, String> attributes) {
        Map<String, String> all = new LinkedHashMap<>(base.attributes());
        all.putAll(attributes);
        return new Point(measurement, base.id() + suffix, base.unitKey(), work, base.generator(), base.data(), all);
    }

    private static <T> Supplier<T> memoize(Supplier<T> supplier) {
        Objects.requireNonNull(supplier);
        if (supplier instanceof Memo<T>) {
            return supplier;
        }
        return new Memo<>(supplier);
    }

    /** A supplier that calls its delegate once. */
    private static final class Memo<T> implements Supplier<T> {

        private final Supplier<T> delegate;
        private T value;

        Memo(Supplier<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized T get() {
            if (value == null) {
                value = Objects.requireNonNull(delegate.get());
            }
            return value;
        }
    }
}
