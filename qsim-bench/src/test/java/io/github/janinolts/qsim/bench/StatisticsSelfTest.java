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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.bench.Aggregator.Classification;
import io.github.janinolts.qsim.bench.Aggregator.PointResult;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * The self-test of the statistics: simulated time series with known effects
 * and disturbances must be classified correctly before any real measurement
 * is trusted.
 */
class StatisticsSelfTest {

    /**
     * The parameters of a simulated point.
     *
     * @param effect the true relative difference of B to A in time
     * @param allocationEffect the true relative difference in allocation
     * @param negativeBias a systematic error of B' relative to B
     * @param plusFraction the extra work of B+ (1/33 if the control works)
     * @param noise the standard deviation of the log time of one arm in one round
     * @param forkSd the standard deviation of the log offset of the candidate per fork (JIT decisions)
     * @param replicaSd the standard deviation of the log offset of the candidate per replica (hardware)
     * @param ops the nominal operations per round
     * @param plusTakesTime whether the extra work of B+ takes time; false simulates a broken measurement
     */
    record Sim(double effect, double allocationEffect, double negativeBias, double plusFraction, double noise,
            double forkSd, double replicaSd, int ops, boolean plusTakesTime) {

        static Sim of(double effect) {
            return new Sim(effect, effect * 0.7, 0, Arm.PLUS_FRACTION, 0.02, 0.004, 0.003, 1, true);
        }

        Sim withNegativeBias(double bias) {
            return new Sim(effect, allocationEffect, bias, plusFraction, noise, forkSd, replicaSd, ops, plusTakesTime);
        }

        Sim withPlusFraction(double fraction) {
            return new Sim(effect, allocationEffect, negativeBias, fraction, noise, forkSd, replicaSd, ops,
                    plusTakesTime);
        }

        Sim withOps(int n) {
            return new Sim(effect, allocationEffect, negativeBias, plusFraction, noise, forkSd, replicaSd, n,
                    plusTakesTime);
        }

        Sim withPlusTakingNoTime() {
            return new Sim(effect, allocationEffect, negativeBias, plusFraction, noise, forkSd, replicaSd, ops, false);
        }
    }

    static List<RoundRecord> simulate(long seed, String point, String mode, Sim s, int replicas, int forks,
            int rounds) {
        SplittableRandom random = new SplittableRandom(seed);
        List<RoundRecord> records = new ArrayList<>();
        for (int r = 0; r < replicas; r++) {
            double replicaOffset = random.nextGaussian() * s.replicaSd();
            double machine = Math.exp(random.nextGaussian() * 0.2);
            for (int f = 0; f < forks; f++) {
                double forkOffset = random.nextGaussian() * s.forkSd();
                double primeOffset = forkOffset + random.nextGaussian() * s.forkSd() * 0.5;
                double drift = 0;
                for (int round = 0; round < rounds; round++) {
                    drift += random.nextGaussian() * 0.05;
                    double base = 1e6 * machine * Math.exp(drift);
                    double[] logTime = {
                        0,
                        Math.log1p(s.effect()) + forkOffset + replicaOffset,
                        Math.log1p(s.effect()) + primeOffset + replicaOffset + Math.log1p(s.negativeBias()),
                        Math.log1p(s.effect()) + forkOffset + replicaOffset,
                    };
                    double[] executed = {s.ops(), s.ops(), s.ops(), s.ops() * (1 + s.plusFraction())};
                    for (Arm arm : Arm.CORE) {
                        int i = arm.ordinal();
                        double burst = random.nextInt(20) == 0 ? 1.5 : 1;
                        double perOp = base * Math.exp(logTime[i] + random.nextGaussian() * s.noise()) * burst;
                        long bytesPerOp = arm == Arm.A ? 1_000_000 : Math.round(1_000_000 * (1 + s.allocationEffect()));
                        double timed = arm == Arm.B_PLUS && !s.plusTakesTime() ? s.ops() : executed[i];
                        records.add(new RoundRecord("P1", point, point, mode, "r" + r, f, round, arm.label(), i,
                                s.ops(), executed[i], Math.round(perOp * timed), 0, bytesPerOp * s.ops(), 0,
                                0, -1, -1, 3, false, "3.1.12", "3.1.19"));
                    }
                }
            }
        }
        return records;
    }

    private static PointResult only(List<RoundRecord> records) {
        List<PointResult> results = Aggregator.aggregate(records, Aggregator.Options.DEFAULT);
        assertEquals(1, results.size());
        return results.get(0);
    }

    /** The result of the tiered mode of a point measured in xint and tiered with the same effect. */
    private static PointResult tieredWithXint(long seed, Sim s) {
        List<RoundRecord> records = new ArrayList<>(simulate(seed, "p", "xint", s, 6, 3, 40));
        records.addAll(simulate(seed + 1000, "p", "tiered", s, 6, 3, 40));
        return Aggregator.aggregate(records, Aggregator.Options.DEFAULT).stream()
                .filter(r -> r.mode().equals("tiered")).findFirst().orElseThrow();
    }

    @Test
    void noEffectIsSame() {
        int same = 0;
        for (long seed = 1; seed <= 20; seed++) {
            PointResult r = only(simulate(seed, "p", "xint", Sim.of(0), 6, 3, 40));
            assertTrue(r.classification() != Classification.FASTER && r.classification() != Classification.SLOWER,
                    r::toString);
            same += r.classification() == Classification.SAME ? 1 : 0;
        }
        assertTrue(same >= 18, "SAME in " + same + " of 20");
    }

    @Test
    void threePercentIsDetected() {
        for (long seed = 1; seed <= 10; seed++) {
            assertEquals(Classification.SLOWER, tieredWithXint(seed, Sim.of(0.03)).classification());
            assertEquals(Classification.FASTER, tieredWithXint(seed, Sim.of(-0.03)).classification());
        }
    }

    @Test
    void onePercentIsNeverTheWrongDirection() {
        for (long seed = 1; seed <= 10; seed++) {
            PointResult r = tieredWithXint(seed, Sim.of(0.01));
            assertTrue(r.classification() != Classification.FASTER && r.classification() != Classification.INVALID,
                    r::toString);
            assertTrue(r.ratio().minimumDetectableDifference() > 0);
        }
    }

    @Test
    void estimateIsUnbiased() {
        PointResult r = only(simulate(7, "p", "xint", Sim.of(0.03), 6, 3, 40));
        double[] ci = r.ratio().interval(0.95);
        assertTrue(ci[0] < 1.03 && 1.03 < ci[1], () -> ci[0] + " .. " + ci[1]);
        assertEquals(1.021, r.allocation().ratio(), 0.0005);
    }

    @Test
    void biasedNegativeControlIsInvalid() {
        for (long seed = 1; seed <= 5; seed++) {
            PointResult r = only(simulate(seed, "p", "xint", Sim.of(0.03).withNegativeBias(0.05), 6, 3, 40));
            assertEquals(Classification.INVALID, r.classification(), r::toString);
        }
    }

    @Test
    void impreciseNegativeControlIsInconclusiveNotInvalid() {
        // Two forks of one replica: one degree of freedom, very wide intervals, but no systematic error.
        int invalid = 0;
        for (long seed = 1; seed <= 10; seed++) {
            PointResult r = only(simulate(seed, "p", "xint", Sim.of(0), 1, 2, 10));
            invalid += r.classification() == Classification.INVALID ? 1 : 0;
        }
        assertEquals(0, invalid);
    }

    @Test
    void positiveControlWithFewOperationsPerRound() {
        // One operation per round: B+ waits for 1/33 of its time in every round, so one operation suffices.
        for (long seed = 1; seed <= 5; seed++) {
            PointResult r = only(simulate(seed, "p", "xint", Sim.of(0).withOps(1), 6, 3, 40));
            double[] ci = r.positive().interval(0.95);
            assertTrue(ci[0] > 1, () -> "B+/B " + ci[0] + " .. " + ci[1]);
        }
    }

    @Test
    void positiveControlWithDisturbedRounds() {
        // Noise and a burst of +50 % in one round in 20: the capped sums still find the 3 %, also with few operations.
        for (int ops : new int[] {1, 48}) {
            for (long seed = 1; seed <= 5; seed++) {
                PointResult r = only(simulate(seed, "p", "xint", Sim.of(0).withOps(ops), 6, 3, 20));
                double[] ci = r.positive().interval(0.95);
                assertTrue(ci[0] > 1, () -> "ops " + ops + ": B+/B " + ci[0] + " .. " + ci[1]);
            }
        }
    }

    @Test
    void positiveControlIsFoundOnlyIfTheExtraWorkTakesTime() {
        // A 95 % interval excludes 1 by chance now and then; so count over many seeds.
        int foundHealthy = 0;
        int foundBroken = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Sim healthy = Sim.of(0).withOps(48);
            foundHealthy += only(simulate(seed, "p", "xint", healthy, 6, 3, 40)).positive().interval(0.95)[0] > 1
                    ? 1 : 0;
            Sim broken = healthy.withPlusTakingNoTime();
            foundBroken += only(simulate(seed, "p", "xint", broken, 6, 3, 40)).positive().interval(0.95)[0] > 1
                    ? 1 : 0;
        }
        assertTrue(foundHealthy >= 18, "found with working extra work: " + foundHealthy + " of 20");
        assertTrue(foundBroken <= 3, "found although the extra work takes no time: " + foundBroken + " of 20");
    }

    @Test
    void missingPositiveControlIsInconclusive() {
        for (long seed = 1; seed <= 5; seed++) {
            PointResult r = tieredWithXint(seed, Sim.of(0.04).withPlusFraction(0));
            assertEquals(Classification.INCONCLUSIVE, r.classification(), r::toString);
            assertTrue(r.notes().stream().anyMatch(n -> n.contains("positive control")), r.notes()::toString);
        }
    }

    @Test
    void falseDiscoveriesAmongManyNullPoints() {
        List<RoundRecord> records = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Sim noisy = new Sim(0, 0, 0, Arm.PLUS_FRACTION, 0.05, 0.02, 0.02, 10, true);
            records.addAll(simulate(100 + i, "p" + i, "xint", noisy, 6, 3, 40));
        }
        long discoveries = Aggregator.aggregate(records, Aggregator.Options.DEFAULT).stream()
                .filter(r -> r.classification() == Classification.FASTER
                        || r.classification() == Classification.SLOWER).count();
        assertTrue(discoveries <= 2, "false discoveries: " + discoveries);
    }

    @Test
    void singleReplicaUsesForksAsUnits() {
        PointResult r = only(simulate(3, "p", "xint", Sim.of(0), 1, 3, 40));
        assertTrue(r.unitsAreForks());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("single replica")));
    }

    @Test
    void allocationLimit() {
        List<RoundRecord> records = new ArrayList<>(simulate(1, "p", "xint", Sim.of(0.03), 6, 3, 10));
        records.addAll(simulate(2, "q", "xint", Sim.of(0.0), 6, 3, 10));
        List<PointResult> violations = Aggregator.allocationViolations(
                Aggregator.aggregate(records, Aggregator.Options.DEFAULT), 1.01);
        assertEquals(1, violations.size());
        assertEquals("p", violations.get(0).point());
    }

    @Test
    void distributions() {
        assertEquals(2.5706, Stats.studentTQuantile(0.975, 5), 1e-4);
        assertEquals(4.3027, Stats.studentTQuantile(0.975, 2), 1e-4);
        assertEquals(2.1098, Stats.studentTQuantile(0.975, 17), 1e-4);
        assertEquals(12.7062, Stats.studentTQuantile(0.975, 1), 1e-3);
        assertEquals(0.5, Stats.studentTCdf(0, 4), 1e-12);
        assertEquals(0.05, Stats.twoSidedP(2.5706, 5), 1e-4);
        assertEquals(2.5, Stats.median(List.of(4.0, 1.0, 3.0, 2.0)), 0);
        assertEquals(1.9, Stats.quantile(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 0.1), 1e-12);
    }

    @Test
    void benjaminiHochberg() {
        double[] p = {0.01, 0.04, 0.03, 0.005, 0.5};
        // Sorted: 0.005, 0.01, 0.03, 0.04, 0.5; thresholds 0.01, 0.02, 0.03, 0.04, 0.05: the first four pass.
        assertArrayEquals(new boolean[] {true, true, true, true, false}, Stats.benjaminiHochberg(p, 0.05));
        assertArrayEquals(new boolean[] {false, false}, Stats.benjaminiHochberg(new double[] {0.5, 0.9}, 0.05));
        assertEquals(1 - 4 * 0.05 / 5, Stats.fcrLevel(4, 5, 0.05), 1e-12);
    }
}
