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

package io.github.janinolts.qsim.codegen;

/**
 * The language profile of a unit: which Java it may contain, and therefore
 * which result each backend is expected to give.
 */
public enum Profile {

    /**
     * Valid Java 8 without lambdas and method references, generic types only
     * with explicit casts, and no registered deviation between Janino and
     * javac: every backend compiles it, with the same results.
     */
    STRICT,

    /**
     * Additionally one leniency of Janino that generated code relies on, e.g.
     * the assignment to a {@code final} local variable: javac rejects it,
     * every version of Janino accepts it.
     */
    LENIENT,

    /**
     * Additionally one language feature that Janino 3.1.12 does not compile,
     * e.g. multi-catch: javac and janino-lts since the version of the feature
     * compile it.
     */
    MODERN,

    /**
     * Additionally one registered deviation between Janino and javac: the
     * result of each backend follows the register.
     */
    PROBE
}
