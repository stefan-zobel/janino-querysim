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
 * How a compilation ended.
 */
public enum Outcome {

    /** Compiled, and the main class is loaded (but not yet linked or initialized). */
    OK,

    /** A compile error: a {@code CompileException}, or an error diagnostic of javac. */
    REJECTED,

    /**
     * The compiler failed: an {@code InternalCompilerException}, another
     * {@code RuntimeException}, or a {@code StackOverflowError}.
     */
    INTERNAL_ERROR,

    /** A {@code LinkageError}, e.g. a {@code ClassFormatError}, when the main class was loaded. */
    INVALID_CLASS,

    /** The backend does not support the requested option, e.g. the compliance mode. */
    UNSUPPORTED
}
