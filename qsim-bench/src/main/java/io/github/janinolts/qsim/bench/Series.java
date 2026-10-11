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

import io.github.janinolts.qsim.codegen.CodegenConfig;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.harness.UnitCase;
import io.github.janinolts.qsim.harness.Workloads;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Table;

import java.util.List;
import java.util.Map;

/**
 * The scaling series of P2: each varies one size parameter in five levels
 * and keeps everything else fixed. The unit of a level has the workload
 * {@code series:<name>:<level>}, e.g. {@code series:columns:128}.
 */
public enum Series {

    /** The columns of {@code Q_FILTER_PROJECT}, in the style {@code WHOLE_STAGE}. */
    COLUMNS("columns", Style.WHOLE_STAGE, 8, 32, 128, 512, 2048),

    /** The depth of four expression trees, in the style {@code WHOLE_STAGE}. */
    EXPR_DEPTH("exprDepth", Style.WHOLE_STAGE, 2, 4, 8, 16, 32),

    /** The branches of the {@code CASE} expressions and {@code IN} lists of {@code Q_CASE_HEAVY}. */
    CASE_BRANCHES("caseBranches", Style.WHOLE_STAGE, 4, 32, 128, 512, 2048),

    /** The aggregates of {@code Q_AGG}, in the style {@code WHOLE_STAGE}. */
    AGGREGATES("aggregates", Style.WHOLE_STAGE, 1, 4, 16, 64, 256),

    /**
     * The nesting of {@code try} statements in {@code Q_ANSI} in size M with
     * ANSI, without jumps; their code is the same in 3.1.12 and janino-lts.
     */
    TRY_NESTING("tryNesting", Style.WHOLE_STAGE, 0, 1, 2, 4, 8),

    /**
     * As {@link #TRY_NESTING}, with jumps: a {@code break} leaves the nested
     * {@code try} statements, and each {@code finally} block holds a
     * {@code try} statement; the cases whose code Janino generates differently
     * since 3.1.14.
     */
    TRY_JUMPS("tryJumps", Style.WHOLE_STAGE, 0, 1, 2, 4, 8),

    /** The generated classes of a query: a chain of operators in the style {@code OPERATOR_CLASSES}. */
    UNITS("units", Style.OPERATOR_CLASSES, 1, 4, 16, 64, 256);

    /** The series of the search for the limits of a method, without splitting. */
    public static final List<Series> LIMIT_SERIES = List.of(COLUMNS, CASE_BRANCHES);

    /** The rows of the data of a series unit; the compile time does not depend on them. */
    static final int ROWS = 16;

    private final String label;
    private final Style style;
    private final List<Integer> levels;

    Series(String label, Style style, Integer... levels) {
        this.label = label;
        this.style = style;
        this.levels = List.of(levels);
    }

    /** Returns the name in point IDs and reports, e.g. {@code columns}. */
    public String label() {
        return label;
    }

    /** Returns the style of the series. */
    public Style style() {
        return style;
    }

    /** Returns the five levels. */
    public List<Integer> levels() {
        return levels;
    }

    /** Returns the series with the given label. */
    public static Series ofLabel(String label) {
        for (Series s : values()) {
            if (s.label.equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown series: " + label);
    }

    /** Returns the plan of a level. */
    public Scenario scenario(int level) {
        PlanNode.Layout layout = PlanNode.Layout.ROWS;
        return switch (this) {
            case COLUMNS -> Scenario.filterProject(level, layout);
            case EXPR_DEPTH -> Scenario.expressionDepth(level, layout);
            case CASE_BRANCHES -> Scenario.caseHeavy(level, layout);
            case AGGREGATES -> Scenario.aggregate(level, layout);
            case TRY_NESTING, TRY_JUMPS -> Scenario.ansi(Scenario.Size.M, layout);
            case UNITS -> Scenario.operatorChain(level, layout);
        };
    }

    /** Returns the configuration of the generator for a level. */
    public CodegenConfig config(int level) {
        if (this == TRY_NESTING || this == TRY_JUMPS) {
            return CodegenConfig.of(style, true, Workloads.SCENARIO_SEED).withTryNesting(level)
                    .withTryJumps(this == TRY_JUMPS);
        }
        return Workloads.config(scenario(level).name(), style, false, Workloads.SCENARIO_SEED);
    }

    /** Returns the workload name of a level, e.g. {@code series:columns:128}. */
    public String workload(int level) {
        return "series:" + label + ":" + level;
    }

    /** Returns the unit of a level. */
    public UnitCase unit(int level) {
        Scenario scenario = scenario(level);
        Map<String, Table> data = scenario.data(Workloads.SCENARIO_SEED, ROWS);
        return UnitCase.of(workload(level), scenario.plan(), data, config(level));
    }

    /** Returns the unit of a level without splitting methods, for the search for the limits. */
    public UnitCase unsplitUnit(int level) {
        Scenario scenario = scenario(level);
        Map<String, Table> data = scenario.data(Workloads.SCENARIO_SEED, ROWS);
        return UnitCase.of("limits:" + label + ":" + level, scenario.plan(), data,
                config(level).withSplitThreshold(0));
    }
}
