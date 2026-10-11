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

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.javac.JavacBackend;

import java.util.List;

/**
 * The three compiler backends, loaded once for all tests of the JVM.
 */
final class TestBackends {

    private static List<CompilerBackend> all;

    private TestBackends() {
    }

    /** Returns javac, the baseline and the candidate, in this order. */
    static synchronized List<CompilerBackend> all() {
        if (all == null) {
            all = List.of(new JavacBackend(), JaninoInstallation.baseline().load(),
                    JaninoInstallation.candidate().load());
        }
        return all;
    }

    /** Returns options with the given parent class loader; the API path comes from the query. */
    static CompileOptions options() {
        return CompileOptions.of(ApiPath.CLASS_BODY, TestBackends.class.getClassLoader());
    }
}
