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

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * The javac reference of P9 in the aggregation: J/A is estimated like B/A,
 * and does not change the classification; without J there is no estimate.
 */
class ReferenceArmTest {

    @Test
    void referenceIsEstimatedBesideTheFourArms() {
        List<RoundRecord> records = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(7);
        for (String point : List.of("with", "without")) {
            for (int fork = 0; fork < 3; fork++) {
                for (int round = 0; round < 10; round++) {
                    for (Arm arm : Arm.values()) {
                        if (arm == Arm.J && point.equals("without")) {
                            continue;
                        }
                        double factor = switch (arm) {
                            case B_PLUS -> 1 + Arm.PLUS_FRACTION;
                            case J -> 0.8;
                            default -> 1;
                        };
                        long nanos = Math.round(1_000_000 * factor * (1 + random.nextDouble() * 1e-3));
                        records.add(new RoundRecord("P9", point, "k", "xint", "local", fork, round, arm.label(), 0,
                                1, arm == Arm.B_PLUS ? 1 + Arm.PLUS_FRACTION : 1, nanos, nanos, 1000, 0, 0, -1, -1, 3, false,
                                "3.1.12", "3.1.19"));
                    }
                }
            }
        }
        List<Aggregator.PointResult> results = Aggregator.aggregate(records, Aggregator.Options.DEFAULT);
        assertEquals(2, results.size());
        Aggregator.PointResult with = results.stream().filter(r -> r.point().equals("with")).findFirst().orElseThrow();
        Aggregator.PointResult without = results.stream().filter(r -> r.point().equals("without")).findFirst()
                .orElseThrow();
        assertEquals(0.8, with.reference().ratio(), 0.01);
        assertEquals(3, with.reference().units());
        assertEquals(0, without.reference().units());
        assertEquals(Aggregator.Classification.SAME, with.classification());
        assertEquals(Aggregator.Classification.SAME, without.classification());
    }
}
