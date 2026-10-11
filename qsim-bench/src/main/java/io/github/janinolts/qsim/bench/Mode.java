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
 * The JIT modes in which a measurement point runs.
 */
public enum Mode {

    /** {@code -Xint}: the work of the compiler without JIT effects; little spread. */
    XINT("xint", List.of("-Xint")),

    /** {@code -XX:TieredStopAtLevel=1}: short-lived processes and early queries. */
    C1("c1", List.of("-XX:TieredStopAtLevel=1")),

    /** The default tiered compilation: steady state; much spread. */
    TIERED("tiered", List.of());

    /** The JVM options of every measuring JVM. */
    public static final List<String> COMMON_OPTIONS =
            List.of("-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch", "-XX:+UseSerialGC");

    private final String label;
    private final List<String> options;

    Mode(String label, List<String> options) {
        this.label = label;
        this.options = options;
    }

    /** Returns the name in reports and on the command line, e.g. {@code xint}. */
    public String label() {
        return label;
    }

    /** Returns the JVM options of the mode, in addition to {@link #COMMON_OPTIONS}. */
    public List<String> options() {
        return options;
    }

    /** Returns the mode with the given label. */
    public static Mode ofLabel(String label) {
        for (Mode m : values()) {
            if (m.label.equals(label)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown mode: " + label + " (xint, c1, tiered)");
    }
}
