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

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;

import java.util.Objects;

/**
 * The candidate in its compliance mode ({@code JaninoOption.JAVAC_COMPLIANCE}),
 * which only the development line {@code master} has: compiles every unit
 * through the candidate with the option set, in the class loader of the
 * candidate.
 */
public final class ComplianceBackend implements CompilerBackend {

    /** The identifier of the candidate in its compliance mode. */
    public static final String ID = "JLTS-C";

    private final CompilerBackend candidate;

    /**
     * Creates the backend.
     *
     * @param candidate the candidate
     * @throws IllegalArgumentException if the candidate is not of the
     *         development line {@code master}: a version without the
     *         compliance mode would report every unit as unsupported, and the
     *         comparison would pass without comparing anything
     */
    public ComplianceBackend(CompilerBackend candidate) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        if (!Ledger.lineOf(candidate.version()).equals(Ledger.LINE_MASTER)) {
            throw new IllegalArgumentException("The compliance mode needs a candidate of the development line "
                    + Ledger.LINE_MASTER + ", not " + candidate.version());
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return candidate.version();
    }

    @Override
    public CompileResult compile(GeneratedUnit unit, CompileOptions options) {
        return candidate.compile(unit, options.withCompliance(true));
    }

    @Override
    public String toString() {
        return ID + ' ' + version();
    }
}
