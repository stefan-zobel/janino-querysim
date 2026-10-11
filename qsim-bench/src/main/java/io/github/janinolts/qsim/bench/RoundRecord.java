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

/**
 * One arm in one round of a measurement: one line of {@code rounds.jsonl}.
 *
 * @param measurement the measurement, e.g. {@code P1}
 * @param point the measurement point within it
 * @param unitKey the key of the measured unit, for the ledger
 * @param mode the JIT mode, e.g. {@code xint}
 * @param replica the replica (runner)
 * @param fork the fork (JVM) within the replica, from 0
 * @param round the round within the fork, from 0
 * @param arm the arm: {@code A}, {@code B}, {@code B'} or {@code B+}
 * @param position the position of the arm in the order of the round, from 0
 * @param ops the nominal number of operations
 * @param executedOps the work executed, in operations: {@code ops}, and
 *        for {@code B+} {@code ops * (1 + 1/33)}, the extra 1/33 being its
 *        active wait
 * @param nanos the wall time of the operations
 * @param cpuNanos the CPU time of the measuring thread
 * @param allocatedBytes the bytes that the thread allocated
 * @param gcMillis the time of all garbage collections in the meantime
 * @param jitMillis the time of the JIT compiler in the meantime
 * @param involuntarySwitches the involuntary context switches of the thread,
 *        or -1 if unknown (only Linux)
 * @param stealTicks the steal time of the machine in clock ticks, or -1 if
 *        unknown (only Linux)
 * @param warmupRounds the number of warm-up rounds of the fork for this point
 * @param warmupCapped whether the warm-up ended at its limit, not because
 *        the JIT compiler settled
 * @param baselineVersion the version of the baseline
 * @param candidateVersion the version of the candidate
 * @param plannedRounds the measured rounds that the fork planned for this
 *        point, or 0 if unknown; fewer complete rounds mean that the time
 *        budget of the point ended the measurement
 */
public record RoundRecord(
        String measurement,
        String point,
        String unitKey,
        String mode,
        String replica,
        int fork,
        int round,
        String arm,
        int position,
        long ops,
        double executedOps,
        long nanos,
        long cpuNanos,
        long allocatedBytes,
        long gcMillis,
        long jitMillis,
        long involuntarySwitches,
        long stealTicks,
        int warmupRounds,
        boolean warmupCapped,
        String baselineVersion,
        String candidateVersion,
        int plannedRounds) {

    /** A record without the planned rounds, e.g. of P4, whose rounds have no time budget. */
    public RoundRecord(String measurement, String point, String unitKey, String mode, String replica, int fork,
            int round, String arm, int position, long ops, double executedOps, long nanos, long cpuNanos,
            long allocatedBytes, long gcMillis, long jitMillis, long involuntarySwitches, long stealTicks,
            int warmupRounds, boolean warmupCapped, String baselineVersion, String candidateVersion) {
        this(measurement, point, unitKey, mode, replica, fork, round, arm, position, ops, executedOps, nanos,
                cpuNanos, allocatedBytes, gcMillis, jitMillis, involuntarySwitches, stealTicks, warmupRounds,
                warmupCapped, baselineVersion, candidateVersion, 0);
    }

    /** Returns the wall time per nominal operation. */
    public double nanosPerOp() {
        return (double) nanos / ops;
    }

    /** Returns the allocated bytes per nominal operation. */
    public double bytesPerOp() {
        return (double) allocatedBytes / ops;
    }
}
