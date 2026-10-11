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

import io.github.janinolts.qsim.runtime.Evaluator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The result of a compilation.
 *
 * @param outcome how the compilation ended
 * @param mainClass the loaded main class, if {@link Outcome#OK}
 * @param evaluator the evaluator, if {@link Outcome#OK} and the unit is an
 *        expression or a script
 * @param classFiles the class files by binary class name, in the order of the
 *        compiler; empty if there are none
 * @param message the message of the compile error or of the failure
 * @param line the line of the compile error, 1-based, or 0 if unknown
 * @param column the column of the compile error, 1-based, or 0 if unknown
 * @param cause the exception or error, if any
 */
public record CompileResult(
        Outcome outcome,
        Class<?> mainClass,
        Evaluator evaluator,
        Map<String, byte[]> classFiles,
        String message,
        int line,
        int column,
        Throwable cause) {

    public CompileResult {
        Objects.requireNonNull(outcome, "outcome");
        classFiles = Collections.unmodifiableMap(new LinkedHashMap<>(classFiles));
    }

    /** A successful compilation. */
    public static CompileResult ok(Class<?> mainClass, Evaluator evaluator, Map<String, byte[]> classFiles) {
        return new CompileResult(Outcome.OK, Objects.requireNonNull(mainClass, "mainClass"), evaluator, classFiles,
                null, 0, 0, null);
    }

    /** A compile error at the given location. */
    public static CompileResult rejected(String message, int line, int column, Throwable cause) {
        return new CompileResult(Outcome.REJECTED, null, null, Map.of(), message, line, column, cause);
    }

    /** A failure of the compiler itself. */
    public static CompileResult internalError(Throwable cause) {
        return new CompileResult(Outcome.INTERNAL_ERROR, null, null, Map.of(), String.valueOf(cause), 0, 0, cause);
    }

    /** Class files that the JVM rejected when the main class was loaded. */
    public static CompileResult invalidClass(Map<String, byte[]> classFiles, Throwable cause) {
        return new CompileResult(Outcome.INVALID_CLASS, null, null, classFiles, String.valueOf(cause), 0, 0, cause);
    }

    /** An option that the backend does not support. */
    public static CompileResult unsupported(String message) {
        return new CompileResult(Outcome.UNSUPPORTED, null, null, Map.of(), message, 0, 0, null);
    }

    @Override
    public String toString() {
        return switch (outcome) {
            case OK -> "OK " + mainClass.getName() + ' ' + classFiles.keySet();
            case REJECTED -> "REJECTED at " + line + ':' + column + ": " + message;
            default -> outcome + ": " + message;
        };
    }
}
