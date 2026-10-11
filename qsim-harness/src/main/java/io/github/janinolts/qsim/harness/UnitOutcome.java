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

import java.util.List;
import java.util.Objects;

/**
 * What the differential comparison found for one unit.
 *
 * @param key the unit
 * @param featureTags the code patterns of the generated unit
 * @param runs the results, first the interpreter, then javac, the baseline
 *        and the candidate
 * @param classFiles the comparison of the class files of the baseline and
 *        the candidate in the configuration {@code normalized}, or
 *        {@code null} if one of them produced none
 * @param differences the differences between the pairs of backends that the
 *        ledger must explain
 * @param expected the differences that follow from the language profile of
 *        the unit, with the assessment {@code EXPECTED}
 */
public record UnitOutcome(UnitKey key, List<String> featureTags, List<BackendRun> runs,
        ClassFileComparison classFiles, List<Difference> differences, List<Difference> expected) {

    /**
     * The run of one backend.
     *
     * @param id the backend, e.g. {@code JLTS}, or {@code INTERP}
     * @param version the version of the compiler, empty for the interpreter
     * @param result the result
     * @param classFileBytes the total size of the class files in the
     *        configuration {@code as-shipped}, 0 if there are none
     * @param classCount the number of class files
     */
    public record BackendRun(String id, String version, RunResult result, int classFileBytes, int classCount) {
    }

    public UnitOutcome {
        Objects.requireNonNull(key, "key");
        featureTags = List.copyOf(featureTags);
        runs = List.copyOf(runs);
        differences = List.copyOf(differences);
        expected = List.copyOf(expected);
    }

    /** Returns the result of a backend, or {@code null} if it did not run. */
    public RunResult result(String backendId) {
        return runs.stream().filter(r -> r.id().equals(backendId)).map(BackendRun::result).findFirst().orElse(null);
    }
}
