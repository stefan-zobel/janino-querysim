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

package io.github.janinolts.qsim.harness;

import java.util.Objects;

/**
 * A difference between two backends for one unit.
 *
 * @param key the unit
 * @param pair the two backends
 * @param kind what differs
 * @param assessment what the difference means
 * @param detail a description for the report
 */
public record Difference(UnitKey key, Pair pair, Kind kind, Assessment assessment, String detail) {

    /** The pairs of backends that are compared, each answering one question. */
    public enum Pair {
        /** The interpreter and javac: must always agree, otherwise qsim is wrong. */
        INTERP_JAVAC("INTERP:JAVAC"),
        /** javac and the candidate: deviation from javac. */
        JAVAC_JLTS("JAVAC:JLTS"),
        /** The baseline and the candidate: compatibility or regression. */
        J3112_JLTS("J3112:JLTS");

        private final String label;

        Pair(String label) {
            this.label = label;
        }

        /** Returns the label, e.g. {@code J3112:JLTS}, as in the ledger. */
        public String label() {
            return label;
        }

        /** Returns the pair with the given label. */
        public static Pair ofLabel(String label) {
            for (Pair p : values()) {
                if (p.label.equals(label)) {
                    return p;
                }
            }
            throw new IllegalArgumentException("Unknown pair: " + label);
        }
    }

    /** What differs. */
    public enum Kind {
        /** Both compiled and ran, the results differ. */
        WRONG,
        /** One gives an invalid class or a compiler failure, the other compiles. */
        INVALID,
        /** One rejects, the other accepts. */
        REJECTED,
        /** Same results, but different class files (baseline and candidate only). */
        BYTES,
        /** Both reject, but at a different location or with a different message (baseline and candidate only). */
        LOCATION,
        /** The allocation of the candidate exceeds the limit; used by the measurements, not by this harness. */
        ALLOC
    }

    /** What a difference means. */
    public enum Assessment {
        /** The interpreter and javac disagree: a defect of qsim, to be fixed first. */
        QSIM_DEFECT,
        /** The baseline deviates from javac and the candidate does not: an expected fix. */
        CORRECTION,
        /** The baseline agrees with javac and the candidate does not: a regression. */
        REGRESSION,
        /** The candidate deviates from javac like the baseline: a registered or a new deviation. */
        DEVIATION,
        /** The class files differ with the same results: must be explained by the change log. */
        CHANGED_CODE,
        /** Baseline, candidate and javac all differ. */
        UNCLEAR,
        /**
         * Follows from the language profile of the unit: the feature in it,
         * with its ID in the register or the change log, makes the backends
         * differ; explained without an entry in the ledger.
         */
        EXPECTED,
        /**
         * A backend does not behave as the language profile of the unit
         * expects: the register, the change log or the catalog of features is
         * wrong, or the backend has a defect.
         */
        NOT_AS_EXPECTED
    }

    public Difference {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String toString() {
        return key + " " + pair.label() + " " + kind + " (" + assessment + "): " + detail;
    }
}
