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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The scaling reports of P2: the fitted exponent of a known power law, and
 * curves, CSV and SVG from aggregated rounds.
 */
class ScalingTest {

    @Test
    void slopeOfAPowerLaw() {
        List<double[]> xy = new ArrayList<>();
        for (int chars : new int[] {100, 400, 1600, 6400}) {
            xy.add(new double[] {Math.log(chars), Math.log(3e-6 * Math.pow(chars, 1.25))});
        }
        assertEquals(1.25, Scaling.slope(xy), 1e-9);
        assertTrue(Double.isNaN(Scaling.slope(xy.subList(0, 1))));
    }

    @Test
    void curvesFromRounds() {
        // Time grows linearly for A and with exponent 1.5 for B; the source length doubles per level.
        List<RoundRecord> records = new ArrayList<>();
        List<PointInfo> infos = new ArrayList<>();
        int[] levels = {8, 16, 32};
        for (int i = 0; i < levels.length; i++) {
            int chars = 1000 << i;
            String point = "columns:" + levels[i] + "/WHOLE_STAGE";
            infos.add(new PointInfo("P2", point, "series:columns:" + levels[i] + "/WHOLE_STAGE/STRICT/h", chars,
                    Map.of("series", "columns", "level", Integer.toString(levels[i])), true, null));
            long a = 1_000L * chars;
            long b = (long) (1_000.0 * Math.pow(chars, 1.5) / Math.sqrt(1000));
            for (int fork = 0; fork < 2; fork++) {
                for (int round = 0; round < 5; round++) {
                    long noise = round % 2;
                    for (Arm arm : Arm.CORE) {
                        long nanos = switch (arm) {
                            case A -> a + noise;
                            case B, B_PRIME -> b + noise;
                            default -> b + b / 33 + noise;
                        };
                        records.add(new RoundRecord("P2", point, "k", "xint", "local", fork, round, arm.label(), 0, 1,
                                arm == Arm.B_PLUS ? 1 + Arm.PLUS_FRACTION : 1, nanos, nanos, 1000L * chars, 0, 0, -1, -1, 3, false,
                                "3.1.12", "3.1.19"));
                    }
                }
            }
        }
        List<Aggregator.PointResult> results = Aggregator.aggregate(records, Aggregator.Options.DEFAULT);
        List<Scaling.Curve> curves = Scaling.curves(results, infos);
        assertEquals(1, curves.size());
        Scaling.Curve c = curves.get(0);
        assertEquals("columns-xint", c.name());
        assertEquals(List.of(8, 16, 32), c.rows().stream().map(Scaling.Row::level).toList());
        assertEquals(1.0, c.exponent(Scaling.Row::secondsA), 1e-3);
        assertEquals(1.5, c.exponent(Scaling.Row::secondsB), 1e-3);
        assertEquals(1.0, c.exponent(Scaling.Row::bytesA), 1e-9);

        String csv = Scaling.csv(c);
        assertEquals(4, csv.split("\n").length);
        assertTrue(csv.startsWith("series,mode,level,source_chars,"), csv);
        String svg = Scaling.svg(c);
        assertTrue(svg.startsWith("<svg ") && svg.contains("<polyline") && svg.contains("exponent 1.50"), svg);
        assertFalse(svg.contains("NaN"), svg);
    }
}
