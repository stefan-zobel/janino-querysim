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

import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.Outcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compilation of all units of a query, one compiler instance per unit.
 *
 * @param results the results, in the order of the units
 */
public record CompiledQuery(List<CompileResult> results) {

    public CompiledQuery {
        results = List.copyOf(results);
    }

    /** Returns the first result that is not {@code OK}, or {@code null} if all units compiled. */
    public CompileResult failure() {
        return results.stream().filter(r -> r.outcome() != Outcome.OK).findFirst().orElse(null);
    }

    /** Returns the class files of all units, by binary class name. */
    public Map<String, byte[]> classFiles() {
        Map<String, byte[]> all = new LinkedHashMap<>();
        for (CompileResult r : results) {
            all.putAll(r.classFiles());
        }
        return all;
    }

    /** Returns the main class of the unit with the given index. */
    public Class<?> mainClass(int unit) {
        return results.get(unit).mainClass();
    }
}
