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

import java.util.Map;

/**
 * A measurement point as {@code bench} selected it; one line of
 * {@code points.jsonl}. A point that is not measured says why, e.g. P9 for
 * identical code.
 *
 * @param measurement the measurement
 * @param point the point
 * @param unitKey the key of the unit
 * @param sourceChars the length of the generated source text
 * @param attributes the parameters of the point, e.g. the series and the level of P2
 * @param measured whether the point is measured
 * @param reason why it is not measured, or {@code null}
 */
public record PointInfo(String measurement, String point, String unitKey, int sourceChars,
        Map<String, String> attributes, boolean measured, String reason) {

    /** The name of the file. */
    public static final String FILE = "points.jsonl";

    /** Returns an attribute, or {@code null}. */
    public String attribute(String name) {
        return attributes == null ? null : attributes.get(name);
    }
}
