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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The measurement points: every measurement has points with unique IDs, the
 * filters work, and the extents are as the concept describes them.
 */
class MeasurementsTest {

    @Test
    void everyMeasurementHasUniquePoints() {
        for (String m : Measurements.ALL) {
            List<Measurements.Point> points = Measurements.of(m, false, null);
            assertFalse(points.isEmpty(), m);
            Set<String> ids = new HashSet<>();
            for (Measurements.Point p : points) {
                assertTrue(ids.add(p.id()), m + ": duplicate " + p.id());
                assertEquals(m, p.measurement());
            }
        }
    }

    @Test
    void extents() {
        assertEquals(9, Measurements.p1(false).size());
        assertEquals(Series.values().length * 5, Measurements.p2().size());
        assertEquals(3 * Measurements.Phase.values().length, Measurements.p3(false).size());
        assertEquals(4, Measurements.p4(false).size());
        assertEquals(ResolutionUnits.CLASSES.size() + ResolutionUnits.OVERLOADS.size(), Measurements.p6().size());
        assertEquals(3 * Measurements.Debug.values().length, Measurements.p7(false).size());
        assertEquals(9 + Measurements.p2().size(), Measurements.p8(false).size());
        assertEquals(9 + Measurements.p2().size(), Measurements.p9(false).size());
        assertEquals(9, Measurements.p10(false).size());
        int cores = Runtime.getRuntime().availableProcessors();
        for (Measurements.Point p : Measurements.p5(false)) {
            assertTrue(Integer.parseInt(p.attributes().get("threads")) <= Math.max(1, cores), p.id());
        }
        assertTrue(Measurements.p1(true).size() > 30);
    }

    @Test
    void patternsAreCommaSeparated() {
        List<Measurements.Point> points = Measurements.of("P2", false, "columns:8/*, units:*");
        assertEquals(1 + 5, points.size());
        assertTrue(points.stream().allMatch(p -> p.id().startsWith("columns:8/") || p.id().startsWith("units:")));
        assertEquals(List.of("Q_TINY:ROWS/EXPRESSION"),
                Measurements.of("P1", false, "Q_TINY*").stream().map(Measurements.Point::id).toList());
        assertThrows(IllegalArgumentException.class, () -> Measurements.of("P11", false, null));
    }

    @Test
    void derivedPointsShareTheUnit() {
        Measurements.Point p1 = Measurements.of("P1", false, "Q_FILTER_PROJECT*").get(0);
        List<Measurements.Point> phases = Measurements.of("P3", false, "Q_FILTER_PROJECT*");
        assertEquals(4, phases.size());
        for (Measurements.Point p : phases) {
            assertEquals(p1.unitKey(), p.unitKey());
            assertTrue(p.id().startsWith(p1.id() + "/"), p.id());
        }
        assertEquals(p1.sourceChars(), phases.get(0).sourceChars());
    }

    @Test
    void seriesPointsCarryTheirLevel() {
        for (Measurements.Point p : Measurements.p2()) {
            Series s = Series.ofLabel(p.attributes().get("series"));
            int level = Integer.parseInt(p.attributes().get("level"));
            assertTrue(s.levels().contains(level));
            assertEquals(s.workload(level), p.unitKey().substring(0, p.unitKey().indexOf('/')));
        }
    }
}
