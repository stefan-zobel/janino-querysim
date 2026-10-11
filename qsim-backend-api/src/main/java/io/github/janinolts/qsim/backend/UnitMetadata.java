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

package io.github.janinolts.qsim.backend;

import java.util.List;
import java.util.Objects;

/**
 * Where a unit comes from: enough to generate it again and to group findings
 * and measurements.
 *
 * @param seed the seed of the plan, the data and the generator decisions
 * @param style the code style, e.g. {@code WHOLE_STAGE}
 * @param profile the language profile, e.g. {@code STRICT}
 * @param configHash the hash of the generator configuration
 * @param featureTags the code patterns that the unit contains, e.g.
 *        {@code null-pair}
 */
public record UnitMetadata(long seed, String style, String profile, String configHash, List<String> featureTags) {

    /** The metadata of a hand-written unit. */
    public static final UnitMetadata HAND_WRITTEN = new UnitMetadata(0, "", "", "", List.of());

    public UnitMetadata {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(configHash, "configHash");
        featureTags = List.copyOf(featureTags);
    }
}
