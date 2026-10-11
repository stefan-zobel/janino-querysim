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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns the rounds of all replicas into one result per measurement point and
 * mode, and classifies it.
 * <p>
 * Per fork: the median of the logarithms of the round ratios B/A and B'/B,
 * the logarithm of the ratio of the summed times of B+ and B, with disturbed
 * rounds capped (see {@link #cappedNanosPerOp}), and the median of the
 * logarithms of the
 * allocation ratios. Per replica: the mean of its forks. Overall: the mean of
 * the replicas, with a t interval over the replica means; the replica is the
 * statistical unit, since forks and rounds of one runner are not independent.
 * With a single replica, as on a local machine, the forks serve as units
 * instead, and the result says so.
 */
public final class Aggregator {

    /**
     * The parameters of the classification.
     *
     * @param delta the equivalence margin, e.g. 0.02 for 2 %
     * @param q the false discovery rate of the selection and the false
     *        coverage rate of the adjusted intervals
     */
    public record Options(double delta, double q) {
        public static final Options DEFAULT = new Options(0.02, 0.05);
    }

    /** The classification of a measurement point. */
    public enum Classification { SAME, FASTER, SLOWER, INCONCLUSIVE, INVALID }

    /**
     * An estimate of a ratio, on the log scale.
     *
     * @param logMean the mean of the unit values (logarithms of ratios)
     * @param se the standard error, NaN with fewer than two units
     * @param units the number of units (replicas, or forks)
     */
    public record Estimate(double logMean, double se, int units) {

        /** Returns the ratio, e.g. 1.03. */
        public double ratio() {
            return Math.exp(logMean);
        }

        /** Tells whether an interval can be computed. */
        public boolean hasInterval() {
            return units >= 2 && !Double.isNaN(se);
        }

        /** Returns the interval of the logarithm at the given confidence level. */
        public double[] logInterval(double level) {
            if (!hasInterval()) {
                return new double[] {Double.NaN, Double.NaN};
            }
            double t = Stats.studentTQuantile(1 - (1 - level) / 2, units - 1);
            return new double[] {logMean - t * se, logMean + t * se};
        }

        /** Returns the interval of the ratio at the given confidence level. */
        public double[] interval(double level) {
            double[] l = logInterval(level);
            return new double[] {Math.exp(l[0]), Math.exp(l[1])};
        }

        /** Returns the two-sided p-value of "no difference", or NaN. */
        public double p() {
            if (!hasInterval()) {
                return Double.NaN;
            }
            return se == 0 ? (logMean == 0 ? 1 : 0) : Stats.twoSidedP(logMean / se, units - 1);
        }

        /** Returns the minimum detectable difference: the half width of the 95 % interval, as a fraction. */
        public double minimumDetectableDifference() {
            double[] l = logInterval(0.95);
            return Math.exp((l[1] - l[0]) / 2) - 1;
        }
    }

    /**
     * The per-fork summary in the format of janino-benchmarks.
     *
     * @param replica the replica
     * @param fork the fork
     * @param median the median of the round ratios B/A
     * @param p10 the 10th percentile of the round ratios B/A
     * @param p90 the 90th percentile of the round ratios B/A
     * @param secondsA the median time per operation of A
     * @param secondsB the median time per operation of B
     * @param bytesA the median allocation per operation of A
     * @param bytesB the median allocation per operation of B
     * @param rounds the number of complete rounds
     */
    public record ForkSummary(String replica, int fork, double median, double p10, double p90, double secondsA,
            double secondsB, double bytesA, double bytesB, int rounds) {
    }

    /**
     * The result of a measurement point in one mode.
     *
     * @param measurement the measurement, e.g. {@code P1}
     * @param point the point
     * @param unitKey the key of the unit, for the ledger
     * @param mode the mode
     * @param ratio B/A of the time per operation
     * @param negative B'/B, the negative control
     * @param positive B+/B, the positive control
     * @param allocation B/A of the allocated bytes per operation
     * @param reference J/A, the code of javac against the baseline (P9);
     *        without units if there is no reference
     * @param classification the classification
     * @param adjusted the interval of B/A at the level after Benjamini and
     *        Yekutieli, if the point was selected
     * @param notes the reasons and warnings, for the report
     * @param unitsAreForks whether the forks served as units (a single replica)
     * @param replicas the number of replicas
     * @param forks the per-fork summaries
     */
    public record PointResult(String measurement, String point, String unitKey, String mode, Estimate ratio,
            Estimate negative, Estimate positive, Estimate allocation, Estimate reference,
            Classification classification,
            double[] adjusted, List<String> notes, boolean unitsAreForks, int replicas, List<ForkSummary> forks) {
    }

    private Aggregator() {
    }

    /** Aggregates and classifies the rounds of a run. */
    public static List<PointResult> aggregate(List<RoundRecord> records, Options options) {
        // measurement | point | mode -> replica -> fork -> round -> arm
        Map<String, Map<String, Map<Integer, Map<Integer, Map<Arm, RoundRecord>>>>> groups = new TreeMap<>();
        Map<String, RoundRecord> firstOfGroup = new TreeMap<>();
        for (RoundRecord r : records) {
            String group = r.measurement() + '|' + r.point() + '|' + r.mode();
            firstOfGroup.putIfAbsent(group, r);
            groups.computeIfAbsent(group, g -> new TreeMap<>())
                    .computeIfAbsent(r.replica(), x -> new TreeMap<>())
                    .computeIfAbsent(r.fork(), x -> new TreeMap<>())
                    .computeIfAbsent(r.round(), x -> new EnumMap<>(Arm.class))
                    .put(Arm.ofLabel(r.arm()), r);
        }

        List<Partial> partials = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<Integer, Map<Integer, Map<Arm, RoundRecord>>>>> g : groups.entrySet()) {
            partials.add(estimate(firstOfGroup.get(g.getKey()), g.getValue()));
        }

        double[] p = partials.stream().mapToDouble(x -> x.ratio.p()).toArray();
        List<Integer> tested = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            if (!Double.isNaN(p[i])) {
                tested.add(i);
            }
        }
        double[] testedP = tested.stream().mapToDouble(i -> p[i]).toArray();
        boolean[] selectedTested = Stats.benjaminiHochberg(testedP, options.q());
        boolean[] selected = new boolean[p.length];
        int k = 0;
        for (int j = 0; j < tested.size(); j++) {
            selected[tested.get(j)] = selectedTested[j];
            k += selectedTested[j] ? 1 : 0;
        }
        double adjustedLevel = tested.isEmpty() ? 0.95 : Stats.fcrLevel(k, tested.size(), options.q());

        List<PointResult> results = new ArrayList<>();
        for (int i = 0; i < partials.size(); i++) {
            results.add(classify(partials.get(i), partials, selected[i], adjustedLevel, options));
        }
        return results;
    }

    /** The estimates of one group, before the classification, which needs all groups. */
    private record Partial(RoundRecord first, Estimate ratio, Estimate negative, Estimate positive,
            Estimate allocation, Estimate reference, double expectedPositive, boolean unitsAreForks, int replicas, List<ForkSummary> forks,
            boolean warmupCapped, int plannedRounds, int fewestRounds, int mostRounds,
            int limitedForks) {
    }

    private static Partial estimate(RoundRecord first,
            Map<String, Map<Integer, Map<Integer, Map<Arm, RoundRecord>>>> replicas) {
        List<ForkSummary> summaries = new ArrayList<>();
        // Per replica, the fork values of the estimates.
        List<List<double[]>> perReplica = new ArrayList<>();
        boolean warmupCapped = false;
        // The forks whose time budget ended the measurement before the planned rounds.
        int plannedRounds = 0;
        int fewestRounds = Integer.MAX_VALUE;
        int mostRounds = 0;
        int limitedForks = 0;
        for (Map.Entry<String, Map<Integer, Map<Integer, Map<Arm, RoundRecord>>>> replica : replicas.entrySet()) {
            List<double[]> forkValues = new ArrayList<>();
            for (Map.Entry<Integer, Map<Integer, Map<Arm, RoundRecord>>> fork : replica.getValue().entrySet()) {
                List<Double> ba = new ArrayList<>();
                List<Double> negative = new ArrayList<>();
                List<Double> allocation = new ArrayList<>();
                List<Double> secondsA = new ArrayList<>();
                List<Double> secondsB = new ArrayList<>();
                List<Double> bytesA = new ArrayList<>();
                List<Double> bytesB = new ArrayList<>();
                List<Double> reference = new ArrayList<>();
                List<RoundRecord> plusRounds = new ArrayList<>();
                List<RoundRecord> bRounds = new ArrayList<>();
                int planned = 0;
                for (Map<Arm, RoundRecord> round : fork.getValue().values()) {
                    if (!round.keySet().containsAll(Arm.CORE)) {
                        continue;
                    }
                    RoundRecord a = round.get(Arm.A);
                    RoundRecord b = round.get(Arm.B);
                    RoundRecord bp = round.get(Arm.B_PRIME);
                    RoundRecord plus = round.get(Arm.B_PLUS);
                    warmupCapped |= a.warmupCapped();
                    planned = Math.max(planned, a.plannedRounds());
                    ba.add(Math.log(b.nanosPerOp() / a.nanosPerOp()));
                    negative.add(Math.log(bp.nanosPerOp() / b.nanosPerOp()));
                    allocation.add(Math.log(b.bytesPerOp() / a.bytesPerOp()));
                    secondsA.add(a.nanosPerOp() / 1e9);
                    secondsB.add(b.nanosPerOp() / 1e9);
                    bytesA.add(a.bytesPerOp());
                    bytesB.add(b.bytesPerOp());
                    plusRounds.add(plus);
                    bRounds.add(b);
                    RoundRecord j = round.get(Arm.J);
                    if (j != null) {
                        reference.add(Math.log(j.nanosPerOp() / a.nanosPerOp()));
                    }
                }
                if (ba.isEmpty()) {
                    continue;
                }
                if (ba.size() < planned) {
                    plannedRounds = Math.max(plannedRounds, planned);
                    fewestRounds = Math.min(fewestRounds, ba.size());
                    mostRounds = Math.max(mostRounds, ba.size());
                    limitedForks++;
                }
                double cap = cap(bRounds);
                double positive = Math.log(cappedNanosPerOp(plusRounds, cap) / cappedNanosPerOp(bRounds, cap));
                long plusOps = plusRounds.stream().mapToLong(RoundRecord::ops).sum();
                double plusExecuted = plusRounds.stream().mapToDouble(RoundRecord::executedOps).sum();
                forkValues.add(new double[] {Stats.median(ba), Stats.median(negative), positive,
                        Stats.median(allocation), Math.log(plusExecuted / plusOps),
                        reference.isEmpty() ? Double.NaN : Stats.median(reference)});
                summaries.add(new ForkSummary(replica.getKey(), fork.getKey(), Math.exp(Stats.median(ba)),
                        Math.exp(Stats.quantile(ba, 0.1)), Math.exp(Stats.quantile(ba, 0.9)), Stats.median(secondsA),
                        Stats.median(secondsB), Stats.median(bytesA), Stats.median(bytesB), ba.size()));
            }
            if (!forkValues.isEmpty()) {
                perReplica.add(forkValues);
            }
        }
        boolean unitsAreForks = perReplica.size() < 2;
        List<double[]> units = new ArrayList<>();
        if (unitsAreForks) {
            perReplica.forEach(units::addAll);
        } else {
            for (List<double[]> forks : perReplica) {
                double[] mean = new double[6];
                for (double[] f : forks) {
                    for (int j = 0; j < mean.length; j++) {
                        mean[j] += f[j] / forks.size();
                    }
                }
                units.add(mean);
            }
        }
        return new Partial(first, estimate(units, 0), estimate(units, 1), estimate(units, 2), estimate(units, 3),
                estimate(units, 5), estimate(units, 4).logMean(), unitsAreForks, perReplica.size(), summaries, warmupCapped,
                plannedRounds, fewestRounds, mostRounds, limitedForks);
    }

    /**
     * Returns the cap of the time per executed operation for the positive
     * control: the median plus three scaled median absolute deviations of the
     * rounds of B in a fork. One cap from B applies to both B and B+, so that
     * both arms are treated alike.
     */
    static double cap(List<RoundRecord> bRounds) {
        List<Double> perExecuted = bRounds.stream().map(r -> (double) r.nanos() / r.executedOps()).toList();
        double median = Stats.median(perExecuted);
        double mad = Stats.median(perExecuted.stream().map(u -> Math.abs(u - median)).toList()) * 1.4826;
        return median + 3 * mad;
    }

    /**
     * Returns the time per nominal operation of an arm in a fork from the sum
     * of its rounds, robust against disturbed rounds: the time per executed
     * operation of a round is capped from above. The cap works on the time per
     * executed operation, which includes the extra work of B+, so that extra
     * work is not cut back; a measurement in which it took no time would
     * still show no difference.
     */
    static double cappedNanosPerOp(List<RoundRecord> rounds, double cap) {
        double nanos = 0;
        long ops = 0;
        for (RoundRecord r : rounds) {
            nanos += Math.min((double) r.nanos() / r.executedOps(), cap) * r.executedOps();
            ops += r.ops();
        }
        return nanos / ops;
    }

    private static Estimate estimate(List<double[]> units, int index) {
        List<Double> values = units.stream().map(u -> u[index]).filter(v -> !Double.isNaN(v)).toList();
        if (values.isEmpty()) {
            return new Estimate(Double.NaN, Double.NaN, 0);
        }
        double se = values.size() < 2 ? Double.NaN : Stats.standardDeviation(values) / Math.sqrt(values.size());
        return new Estimate(Stats.mean(values), se, values.size());
    }

    private static PointResult classify(Partial x, List<Partial> all, boolean selected, double adjustedLevel,
            Options options) {
        List<String> notes = new ArrayList<>();
        double band = Math.log(1 + options.delta());
        double[] adjusted = null;
        Classification c;
        if (!x.ratio.hasInterval() || !x.negative.hasInterval()) {
            c = Classification.INCONCLUSIVE;
            notes.add("fewer than two units: no interval");
        } else if (negativeControlFailed(x.negative, band)) {
            c = Classification.INVALID;
            notes.add("negative control failed: B'/B differs from 1 by more than the margin");
        } else if (!within(x.negative.logInterval(0.95), band)) {
            c = Classification.INCONCLUSIVE;
            notes.add("negative control too imprecise to show B'/B = 1 within the margin");
        } else if (within(x.ratio.logInterval(0.95), band)) {
            c = Classification.SAME;
        } else {
            c = Classification.INCONCLUSIVE;
            boolean positiveDetected = x.positive.hasInterval() && x.positive.logInterval(0.95)[0] > 0;
            if (!selected) {
                notes.add("not selected after Benjamini-Hochberg");
            } else {
                adjusted = x.ratio.interval(adjustedLevel);
                double[] l = x.ratio.logInterval(adjustedLevel);
                double direction = Math.signum(x.ratio.logMean);
                Partial xint = all.stream().filter(o -> o.first.measurement().equals(x.first.measurement())
                        && o.first.point().equals(x.first.point()) && o.first.mode().equals(Mode.XINT.label()))
                        .findFirst().orElse(null);
                Estimate allocation = xint != null ? xint.allocation : x.allocation;
                if (l[0] <= 0 && l[1] >= 0) {
                    notes.add("the adjusted interval contains 1");
                } else if (!positiveDetected) {
                    notes.add("positive control not detected: the measurement is too imprecise");
                } else if (xint == null) {
                    notes.add("no xint measurement to confirm the direction");
                } else if (Math.signum(xint.ratio.logMean) != direction) {
                    notes.add("xint points the other way");
                } else if (Math.signum(allocation.logMean) != direction) {
                    notes.add("the allocation points the other way");
                } else {
                    c = direction > 0 ? Classification.SLOWER : Classification.FASTER;
                }
            }
        }
        if (x.positive.hasInterval()) {
            double[] pi = x.positive.logInterval(0.95);
            if (x.expectedPositive < pi[0] || x.expectedPositive > pi[1]) {
                notes.add(String.format(Locale.ROOT,
                        "calibration: B+/B does not cover the executed extra work %.4f", Math.exp(x.expectedPositive)));
            }
        }
        if (x.unitsAreForks) {
            notes.add("single replica: forks as units, not independent");
        }
        if (x.warmupCapped) {
            notes.add("warm-up capped");
        }
        if (x.plannedRounds > 0) {
            notes.add("time budget: " + (x.fewestRounds == x.mostRounds ? "" : x.fewestRounds + " to ")
                    + x.mostRounds + " of " + x.plannedRounds + " rounds in " + x.limitedForks + " of "
                    + x.forks.size() + " forks");
        }
        RoundRecord f = x.first;
        return new PointResult(f.measurement(), f.point(), f.unitKey(), f.mode(), x.ratio, x.negative, x.positive,
                x.allocation, x.reference, c, adjusted, notes, x.unitsAreForks, x.replicas, x.forks);
    }

    /**
     * Tells whether the negative control shows a systematic error: its
     * interval excludes 1 and its estimate lies outside the margin. A control
     * that is merely too imprecise does not fail; the point is inconclusive
     * then.
     */
    private static boolean negativeControlFailed(Estimate negative, double band) {
        double[] l = negative.logInterval(0.95);
        return (l[0] > 0 || l[1] < 0) && Math.abs(negative.logMean()) > band;
    }

    private static boolean within(double[] logInterval, double band) {
        return logInterval[0] >= -band && logInterval[1] <= band;
    }

    /**
     * The measurements to which the allocation limit does not apply: P4, whose
     * allocation is mostly the loading of the classes of the compiler itself,
     * and P10, in which every arm loads class files of the baseline.
     */
    public static final Set<String> WITHOUT_ALLOCATION_LIMIT = Set.of("P4", "P10");

    /** Returns the results of xint whose allocation B/A exceeds the limit, e.g. 1.01. */
    public static List<PointResult> allocationViolations(List<PointResult> results, double limit) {
        Objects.requireNonNull(results);
        return results.stream().filter(r -> r.mode().equals(Mode.XINT.label())
                && !WITHOUT_ALLOCATION_LIMIT.contains(r.measurement()) && r.allocation().units() > 0
                && r.allocation().ratio() > limit).toList();
    }
}
