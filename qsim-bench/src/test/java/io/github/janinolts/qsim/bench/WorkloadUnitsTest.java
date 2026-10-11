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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.CompiledQuery;
import io.github.janinolts.qsim.harness.CorrectnessReport;
import io.github.janinolts.qsim.harness.CorrectnessRun;
import io.github.janinolts.qsim.harness.DifferentialRunner;
import io.github.janinolts.qsim.harness.QueryRunner;
import io.github.janinolts.qsim.harness.UnitCase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The units of the measurements that do not come from the fixed scenarios:
 * the scaling series of P2 compute the same in all backends, and the units of
 * P6 compile with javac and both Janino versions.
 */
class WorkloadUnitsTest {

    @Test
    void seriesAgreeAcrossBackends() throws Exception {
        List<UnitCase> units = new ArrayList<>();
        for (Series series : Series.values()) {
            List<Integer> levels = series == Series.TRY_NESTING || series == Series.TRY_JUMPS ? series.levels()
                    : series.levels().subList(0, 3);
            for (int level : levels) {
                units.add(series.unit(level));
            }
        }
        try (Backends backends = new Backends()) {
            CorrectnessRun run = CorrectnessRun.run("series", backends.runner(), backends.ledger(), units);
            assertTrue(run.passed(), () -> CorrectnessReport.markdown(run));
        }
    }

    @Test
    void operatorChainHasOneClassPerOperator() {
        for (int level : Series.UNITS.levels()) {
            GeneratedQuery query = Series.UNITS.unit(level).generate();
            assertEquals(level, query.units().size(), "units at level " + level);
        }
    }

    @Test
    void seriesLevelsGrow() {
        for (Series series : Series.values()) {
            if (series == Series.TRY_NESTING || series == Series.TRY_JUMPS) {
                continue;
            }
            int previous = 0;
            for (int level : series.levels()) {
                int chars = series.unit(level).generate().units().stream().mapToInt(u -> u.body().length()).sum();
                assertTrue(chars > previous, series + " at level " + level + ": " + chars + " after " + previous);
                previous = chars;
            }
        }
    }

    @Test
    void resolutionUnitsCompileEverywhere() throws Exception {
        try (Backends backends = new Backends()) {
            DifferentialRunner.Backends b = backends.runner().backends();
            List<GeneratedQuery> queries = new ArrayList<>();
            ResolutionUnits.CLASSES.forEach(n -> queries.add(ResolutionUnits.classes(n)));
            ResolutionUnits.OVERLOADS.forEach(n -> queries.add(ResolutionUnits.overloads(n)));
            for (GeneratedQuery q : queries) {
                for (CompilerBackend backend : List.of(b.javac(), b.baseline(), b.candidate())) {
                    CompiledQuery c = QueryRunner.compile(q, backend, backends.runner().options());
                    assertNull(c.failure(), () -> backend.id() + " " + q.unit().className() + ": " + c.failure());
                }
            }
        }
    }

    @Test
    void resolutionUnitsReferToDistinctClasses() {
        Set<String> distinct = new HashSet<>(ResolutionUnits.REFERENCED);
        assertEquals(ResolutionUnits.REFERENCED.size(), distinct.size());
        assertEquals(64, ResolutionUnits.REFERENCED.size());
        assertEquals(ResolutionUnits.CLASSES.get(ResolutionUnits.CLASSES.size() - 1).intValue(),
                ResolutionUnits.REFERENCED.size());
    }
}
