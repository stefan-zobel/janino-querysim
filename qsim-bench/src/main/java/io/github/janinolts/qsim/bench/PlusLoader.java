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
 * Which copy of the candidate the positive control {@code B+} compiles with.
 * A copy is the candidate loaded through a class loader of its own; its code
 * has its own profile in the JIT compiler. A copy that two arms share runs
 * twice per round, the others once, which can matter in {@code tiered}.
 */
public enum PlusLoader {

    /** {@code B+} shares the copy of {@code B}: the default. */
    SHARED("shared", "B+ shares the copy of B"),

    /** {@code B+} has a copy of its own, so that every copy runs once per round. */
    OWN("own", "B+ has a copy of its own"),

    /** {@code B+} shares the copy of {@code B'}: the mirror image of {@link #SHARED}. */
    PRIME("prime", "B+ shares the copy of B'");

    private final String label;
    private final String description;

    PlusLoader(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Returns the name on the command line, e.g. {@code own}. */
    public String label() {
        return label;
    }

    /**
     * Describes the copies of the candidate for the report.
     *
     * @param primeFirst whether the copy of {@code B'} was loaded before the
     *        copy of {@code B}
     */
    public String describe(boolean primeFirst) {
        return description + (primeFirst ? "; B' loaded before B" : "");
    }

    /** Returns the value with the given label. */
    public static PlusLoader ofLabel(String label) {
        for (PlusLoader p : values()) {
            if (p.label.equals(label)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown copy for B+: " + label + " (shared, own or prime)");
    }
}
