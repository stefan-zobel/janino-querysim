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

import java.util.List;
import java.util.Locale;

/**
 * Recognizes a compile failure that is a limit of the JVM, which Janino and
 * javac report in different ways: Janino with an internal compiler error or a
 * compile error, javac with an error diagnostic, either of them possibly with
 * a {@link StackOverflowError} for nesting that is too deep.
 */
public final class Limits {

    /** Parts of the messages, in lower case. */
    private static final List<String> MESSAGES = List.of(
            // Janino
            "code grows beyond 64 kb",
            "has grown past jvm limit",
            "too many parameters",
            "nested too deeply",
            // javac
            "code too large",
            "too many constants",
            "too many local variables",
            "string constant is too long");

    private Limits() {
    }

    /** Tells whether a failed compilation hit a limit of the JVM. */
    public static boolean isLimit(CompileResult result) {
        if (result.outcome() != Outcome.REJECTED && result.outcome() != Outcome.INTERNAL_ERROR) {
            return false;
        }
        // Janino wraps the limit in exceptions that name the class and the method; look at the whole chain.
        StringBuilder messages = new StringBuilder(result.message() == null ? "" : result.message());
        for (Throwable t = result.cause(); t != null; t = t.getCause()) {
            if (t instanceof StackOverflowError) {
                return true;
            }
            messages.append('\n').append(t.getMessage());
        }
        String text = messages.toString().toLowerCase(Locale.ROOT);
        return MESSAGES.stream().anyMatch(text::contains);
    }
}
