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
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.CompiledQuery;
import io.github.janinolts.qsim.harness.QueryRunner;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * The interleaved measurement of one point in one JVM: all arms in short
 * rounds, one directly after the other, in an order that changes randomly
 * from round to round.
 * <p>
 * What an operation is depends on the measurement (see {@link Operation}); in
 * P1 it is the whole way of an engine: a new compiler instance, compiling,
 * loading the main class and initializing it, which includes the
 * verification of its class file by the JVM. Only the operations are timed;
 * what an arm prepares for them, e.g. the syntax trees for the phase
 * {@code compile} of P3, is not. The warm-up repeats rounds until the JIT
 * compiler settles (its time per round falls below 2 % of the round), and
 * calibrates the number of operations per round so that an arm takes about
 * the target time, at least one operation.
 * <p>
 * The measured rounds of a point have a time budget: once the next round
 * would exceed it, the measurement ends, but not before the minimum number
 * of rounds. Only points whose operation takes far longer than the target
 * time of a round reach the budget, e.g. the highest levels of P2 in
 * {@code xint}, where one compilation takes up to 20 seconds.
 */
public final class MeasurementLoop {

    /**
     * The settings of a fork.
     *
     * @param rounds the number of measured rounds
     * @param roundNanos the target time of an arm in a round
     * @param minWarmupRounds the minimum number of warm-up rounds
     * @param maxWarmupNanos the maximum time of the warm-up
     * @param seed the seed of the order of the arms
     * @param minRounds the number of measured rounds that the time budget
     *        cannot cut
     * @param pointNanos the time budget of the measured rounds of a point
     */
    public record Settings(int rounds, long roundNanos, int minWarmupRounds, long maxWarmupNanos, long seed,
            int minRounds, long pointNanos) {

        public Settings {
            if (minRounds < 1) {
                throw new IllegalArgumentException("The minimum number of rounds must be at least 1: " + minRounds);
            }
        }

        /** The settings without a time budget. */
        public Settings(int rounds, long roundNanos, int minWarmupRounds, long maxWarmupNanos, long seed) {
            this(rounds, roundNanos, minWarmupRounds, maxWarmupNanos, seed, rounds, Long.MAX_VALUE);
        }

        /**
         * Tells whether the measurement of a point continues after a number of
         * complete rounds and the time they took: up to the number of rounds,
         * and beyond the minimum only while the next round, as long as the
         * average round so far, fits into the time budget.
         */
        boolean continueAfter(int completedRounds, long elapsedNanos) {
            if (completedRounds >= rounds) {
                return false;
            }
            if (completedRounds < minRounds) {
                return true;
            }
            return elapsedNanos + (double) elapsedNanos / completedRounds <= pointNanos;
        }
    }

    /**
     * The backends of the arms; {@code B+} uses the backend of {@code B}.
     *
     * @param a the baseline
     * @param b the candidate
     * @param bPrime the candidate, loaded through a second class loader
     * @param javac javac, the reference of P9, or {@code null}
     */
    public record Arms(CompilerBackend a, CompilerBackend b, CompilerBackend bPrime, CompilerBackend javac) {

        /** The arms without javac. */
        public Arms(CompilerBackend a, CompilerBackend b, CompilerBackend bPrime) {
            this(a, b, bPrime, null);
        }

        CompilerBackend of(Arm arm) {
            return switch (arm) {
                case A -> a;
                case B, B_PLUS -> b;
                case B_PRIME -> bPrime;
                case J -> Objects.requireNonNull(javac, "javac");
            };
        }
    }

    /** Consumes the results of the operations, so that the JIT compiler cannot remove them. */
    private static volatile int sink;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static {
        THREADS.setThreadCpuTimeEnabled(true);
        THREADS.setThreadAllocatedMemoryEnabled(true);
    }

    private MeasurementLoop() {
    }

    /**
     * Measures one point.
     *
     * @param out receives one record per arm and round
     */
    public static void run(Measurements.Point point, Arms arms, CompileOptions options, Settings settings,
            String mode, String replica, int fork, Consumer<RoundRecord> out) {
        Objects.requireNonNull(out);
        try (Operation operation = Operation.of(point, arms, options)) {
            run(point, operation, settings, mode, replica, fork, out);
        }
    }

    private static void run(Measurements.Point point, Operation operation, Settings settings, String mode,
            String replica, int fork, Consumer<RoundRecord> out) {
        List<Arm> arms = operation.arms();

        // Warm-up and calibration.
        long ops = 1;
        int warmupRounds = 0;
        int settled = 0;
        boolean capped = false;
        long warmupStart = System.nanoTime();
        while (true) {
            long jit0 = jitMillis();
            long roundNanos = 0;
            long nanosA = 0;
            for (Arm arm : arms) {
                Object prepared = operation.prepare(arm, ops);
                long start = System.nanoTime();
                sink += operation.execute(arm, ops, prepared);
                long nanos = System.nanoTime() - start;
                roundNanos += nanos;
                if (arm == Arm.A) {
                    nanosA = nanos;
                }
            }
            long jitNanos = (jitMillis() - jit0) * 1_000_000;
            warmupRounds++;
            ops = Math.max(1, Math.min(operation.maxOps(),
                    Math.round((double) settings.roundNanos() * ops / Math.max(1, nanosA))));
            settled = jitNanos < 0.02 * roundNanos ? settled + 1 : 0;
            if (warmupRounds >= settings.minWarmupRounds() && settled >= 2) {
                break;
            }
            if (System.nanoTime() - warmupStart > settings.maxWarmupNanos()) {
                capped = true;
                break;
            }
        }

        SplittableRandom random = new SplittableRandom(settings.seed() * 31 + (replica + "|" + fork + "|" + mode
                + "|" + point.id()).hashCode());
        String baselineVersion = operation.arms.a().version();
        String candidateVersion = operation.arms.b().version();
        long measurementStart = System.nanoTime();
        for (int round = 0; settings.continueAfter(round, System.nanoTime() - measurementStart); round++) {
            List<Arm> order = new ArrayList<>(arms);
            for (int i = order.size() - 1; i > 0; i--) {
                Collections.swap(order, i, random.nextInt(i + 1));
            }
            for (int position = 0; position < order.size(); position++) {
                Arm arm = order.get(position);
                double executed = arm == Arm.B_PLUS ? ops * (1 + Arm.PLUS_FRACTION) : ops;
                Object prepared = operation.prepare(arm, ops);
                long gc0 = gcMillis();
                long jit0 = jitMillis();
                long switches0 = OsCounters.involuntarySwitches();
                long steal0 = OsCounters.stealTicks();
                long cpu0 = THREADS.getCurrentThreadCpuTime();
                long alloc0 = THREADS.getCurrentThreadAllocatedBytes();
                long t0 = System.nanoTime();
                sink += operation.execute(arm, ops, prepared);
                if (arm == Arm.B_PLUS) {
                    sink += Arm.waitExtra(t0, System.nanoTime());
                }
                long nanos = System.nanoTime() - t0;
                long alloc = THREADS.getCurrentThreadAllocatedBytes() - alloc0;
                long cpu = THREADS.getCurrentThreadCpuTime() - cpu0;
                long foreign = operation.foreignAllocatedBytes();
                if (foreign >= 0) {
                    alloc = foreign;
                }
                out.accept(new RoundRecord(point.measurement(), point.id(), point.unitKey(), mode, replica, fork,
                        round, arm.label(), position, ops, executed, nanos, cpu, alloc, gcMillis() - gc0,
                        jitMillis() - jit0, OsCounters.delta(switches0, OsCounters.involuntarySwitches()),
                        OsCounters.delta(steal0, OsCounters.stealTicks()), warmupRounds, capped, baselineVersion,
                        candidateVersion, settings.rounds()));
            }
        }
    }

    /**
     * Executes operations: compile, load, initialize.
     *
     * @return a value against dead code elimination
     * @throws IllegalStateException if the unit does not compile, which makes
     *         the point unmeasurable
     */
    static int execute(GeneratedQuery query, CompilerBackend backend, CompileOptions options, long count) {
        int h = 0;
        for (long i = 0; i < count; i++) {
            CompiledQuery compiled = QueryRunner.compile(query, backend, options);
            CompileResult failure = compiled.failure();
            if (failure != null) {
                throw new IllegalStateException(backend.id() + " " + backend.version() + " does not compile "
                        + query.unit().qualifiedName() + ": " + failure);
            }
            for (CompileResult result : compiled.results()) {
                Class<?> mainClass = result.mainClass();
                try {
                    h += Class.forName(mainClass.getName(), true, mainClass.getClassLoader()).hashCode();
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return h;
    }

    private static long gcMillis() {
        long sum = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sum += Math.max(0, gc.getCollectionTime());
        }
        return sum;
    }

    private static long jitMillis() {
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        return jit != null && jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() : 0;
    }
}
