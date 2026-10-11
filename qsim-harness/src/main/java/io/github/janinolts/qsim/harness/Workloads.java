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

package io.github.janinolts.qsim.harness;

import io.github.janinolts.qsim.codegen.CodegenConfig;
import io.github.janinolts.qsim.codegen.Generator;
import io.github.janinolts.qsim.codegen.NegativePrograms;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.RandomPlans;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Table;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The units of the comparison suites.
 */
public final class Workloads {

    /** The seed of the data of the fixed scenarios. */
    public static final long SCENARIO_SEED = 4711;

    /** The number of rows of a correctness run. */
    public static final int ROWS = 256;

    /** The styles, in the order of the reports. */
    public static final List<Style> STYLES = List.of(Style.WHOLE_STAGE, Style.OPERATOR_CLASSES, Style.ENUMERABLE,
            Style.EXPRESSION);

    /** The depth of the nested {@code try} statements of {@code Q_ANSI}. */
    public static final int ANSI_TRY_NESTING = 2;

    private Workloads() {
    }

    /**
     * Returns the suite {@code smoke}: the fixed scenarios in size S, in every
     * style that fits them, both layouts and both modes, in the profile
     * {@code STRICT}; {@code Q_ANSI} only with ANSI.
     */
    public static List<UnitCase> smoke() {
        return scenarios(Scenario.Size.S);
    }

    /** Returns the fixed scenarios in one size, as for {@link #smoke()}. */
    public static List<UnitCase> scenarios(Scenario.Size size) {
        List<UnitCase> units = new ArrayList<>();
        for (String name : Scenario.NAMES) {
            for (PlanNode.Layout layout : PlanNode.Layout.values()) {
                Scenario s = Scenario.of(name, size, layout);
                Map<String, Table> data = s.data(SCENARIO_SEED, ROWS);
                for (Style style : STYLES) {
                    if (!Generator.supports(style, s.plan())) {
                        continue;
                    }
                    for (boolean ansi : new boolean[] {false, true}) {
                        if (name.equals("Q_ANSI") && !ansi) {
                            continue;
                        }
                        units.add(UnitCase.of(workload(name, size, layout), s.plan(), data,
                                config(name, style, ansi, SCENARIO_SEED)));
                    }
                }
            }
        }
        return units;
    }

    /** Returns the configuration of the generator for a scenario: {@code Q_ANSI} nests {@code try} statements, {@code Q_STRING} inlines string loops in the whole-stage style. */
    public static CodegenConfig config(String scenario, Style style, boolean ansi, long seed) {
        CodegenConfig config = CodegenConfig.of(style, ansi, seed);
        if (scenario.equals("Q_ANSI")) {
            config = config.withTryNesting(ANSI_TRY_NESTING);
        }
        if (scenario.equals("Q_STRING") && style == Style.WHOLE_STAGE) {
            config = config.withInlineStrings(true);
        }
        return config;
    }

    /** The resource with the regression corpus: one random unit per line, see {@link RandomUnit}. */
    public static final String CORPUS = "/corpus/seeds.txt";

    private static final Pattern SEED_WORKLOAD = Pattern.compile("seed:(\\d+)(?::NEG:([A-Z_]+))?");

    /**
     * Returns the suite {@code regression}: the units of the regression
     * corpus, and every seed that an entry of the ledger names, with all
     * styles and variants of its key, so that the entry can be checked in
     * both directions. Units whose style does not fit their plan are left out.
     */
    public static List<UnitCase> regression(Ledger ledger, RandomPlans generator) {
        Set<String> seen = new LinkedHashSet<>();
        List<UnitCase> units = new ArrayList<>();
        for (RandomUnit u : regressionUnits(ledger)) {
            if (seen.add(u.toString())) {
                UnitCase unit = u.build(generator);
                if (unit != null) {
                    units.add(unit);
                }
            }
        }
        return units;
    }

    /** Returns the random units of the suite {@code regression}, before they are built. */
    public static List<RandomUnit> regressionUnits(Ledger ledger) {
        List<RandomUnit> units = new ArrayList<>(corpus());
        for (Ledger.Entry e : ledger.entries()) {
            String[] parts = e.keyPattern().split("/", -1);
            Matcher m = SEED_WORKLOAD.matcher(parts[0]);
            if (!m.matches()) {
                continue;
            }
            long seed = Long.parseLong(m.group(1));
            NegativePrograms.Mutation mutation = m.group(2) == null ? null
                    : NegativePrograms.Mutation.valueOf(m.group(2));
            List<Style> styles = parts.length > 1 && !parts[1].contains("*") ? List.of(Style.valueOf(parts[1]))
                    : STYLES;
            List<Profile> profiles = List.of(Profile.values());
            if (mutation != null) {
                profiles = List.of(Profile.STRICT);
            } else if (parts.length > 2 && !parts[2].startsWith("*")) {
                profiles = List.of(Profile.valueOf(parts[2].split(":")[0]));
            }
            for (Profile profile : profiles) {
                for (Style style : styles) {
                    for (Variant variant : mutation == null ? List.of(Variant.values()) : List.of(Variant.BASE)) {
                        units.add(new RandomUnit(seed, style, profile, variant, mutation));
                    }
                }
            }
        }
        return units;
    }

    /** Returns the units of the regression corpus; empty lines and comments after {@code #} are ignored. */
    public static List<RandomUnit> corpus() {
        List<RandomUnit> units = new ArrayList<>();
        try (InputStream in = Workloads.class.getResourceAsStream(CORPUS)) {
            if (in == null) {
                throw new IllegalStateException("No regression corpus " + CORPUS);
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int hash = line.indexOf('#');
                String text = (hash < 0 ? line : line.substring(0, hash)).strip();
                if (!text.isEmpty()) {
                    units.add(RandomUnit.parse(text));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return units;
    }

    /** Returns the workload name of a scenario, e.g. {@code Q_AGG:S:ROWS}; {@code Q_TINY} has no size. */
    public static String workload(String scenario, Scenario.Size size, PlanNode.Layout layout) {
        return scenario.equals("Q_TINY") ? scenario + ":" + layout : scenario + ":" + size + ":" + layout;
    }
}
