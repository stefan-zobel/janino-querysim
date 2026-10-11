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

/**
 * A compiler that turns a {@link GeneratedUnit} into loaded classes.
 * <p>
 * Every call of {@link #compile} uses a new compiler instance, as query engines
 * do. A backend is safe for concurrent use.
 */
public interface CompilerBackend {

    /** Returns the identifier of the backend, e.g. {@code J3112}, {@code JLTS} or {@code JAVAC}. */
    String id();

    /** Returns the version of the compiler, from the JAR manifest or the JDK. */
    String version();

    /**
     * Compiles a unit and loads its main class. Compile errors and failures of
     * the compiler are reported in the result, not thrown.
     *
     * @throws IllegalArgumentException if the API path does not
     *         {@linkplain ApiPath#accepts(UnitKind) accept} the unit, or if a
     *         type of the unit cannot be resolved through the parent class
     *         loader
     */
    CompileResult compile(GeneratedUnit unit, CompileOptions options);
}
