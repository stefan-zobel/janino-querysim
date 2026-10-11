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

import java.util.List;

/**
 * The four arms of every round, and the javac reference of P9.
 */
public enum Arm {

    /** The baseline: the reference. */
    A("A"),

    /** The candidate: the actual measurement, B/A. */
    B("B"),

    /** The candidate through a second class loader: the negative control, B'/B must be 1. */
    B_PRIME("B'"),

    /**
     * The candidate, which after its operations waits actively for another
     * 1/33 of their time: the positive control, B+/B must be about 1.03.
     */
    B_PLUS("B+"),

    /**
     * The code compiled by javac, as a reference beside the four arms; only
     * in P9, the runtime of the generated code: J/A.
     */
    J("J");

    /** The four arms of every measurement, without the reference. */
    public static final List<Arm> CORE = List.of(A, B, B_PRIME, B_PLUS);

    /** The extra work of {@link #B_PLUS}: 1/33 of its own time. */
    public static final double PLUS_FRACTION = 1.0 / 33;

    /**
     * The extra work of {@link #B_PLUS}: waits actively for 1/33 of the time
     * from {@code start} to {@code end}, the time of its operations. An active
     * wait costs the same in every JIT mode and with any number of operations
     * per round, also with one.
     *
     * @return a value against dead code elimination
     */
    static int waitExtra(long start, long end) {
        long until = end + Math.round((end - start) * PLUS_FRACTION);
        int h = 0;
        while (System.nanoTime() < until) {
            h++;
        }
        return h;
    }

    private final String label;

    Arm(String label) {
        this.label = label;
    }

    /** Returns the name in the raw data, e.g. {@code B'}. */
    public String label() {
        return label;
    }

    /** Returns the arm with the given label. */
    public static Arm ofLabel(String label) {
        for (Arm a : values()) {
            if (a.label.equals(label)) {
                return a;
            }
        }
        throw new IllegalArgumentException("Unknown arm: " + label);
    }
}
