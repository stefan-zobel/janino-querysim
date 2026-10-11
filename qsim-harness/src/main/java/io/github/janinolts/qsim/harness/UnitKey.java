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
 * Identifies a unit across runs, in the ledger and in the reports:
 * {@code workload/style/profile/configHash}, e.g.
 * {@code Q_TINY:ROWS/WHOLE_STAGE/STRICT/3fa2c41b09d7}.
 *
 * @param workload the scenario with its variant, or {@code seed:<n>} for a
 *        random plan
 * @param style the code style
 * @param profile the language profile
 * @param configHash the hash of the generator configuration
 */
public record UnitKey(String workload, String style, String profile, String configHash) {

    public UnitKey {
        for (String part : new String[] {workload, style, profile, configHash}) {
            Objects.requireNonNull(part);
            if (part.contains("/") || part.contains("\t")) {
                throw new IllegalArgumentException("A part of a unit key contains '/' or a tab: " + part);
            }
        }
    }

    /** Parses the form of {@link #toString()}. */
    public static UnitKey parse(String text) {
        String[] parts = text.split("/", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Not a unit key: " + text);
        }
        return new UnitKey(parts[0], parts[1], parts[2], parts[3]);
    }

    @Override
    public String toString() {
        return workload + '/' + style + '/' + profile + '/' + configHash;
    }
}
