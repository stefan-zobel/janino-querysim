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

import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.harness.ComplianceBackend;
import io.github.janinolts.qsim.harness.DifferentialRunner;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;
import io.github.janinolts.qsim.harness.Ledger;
import io.github.janinolts.qsim.harness.Reproducer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The compiler backends of the correctness commands: javac, the baseline and
 * the candidate, each Janino version in a class loader of its own, and on
 * request the candidate in its compliance mode; closing releases the class
 * loaders.
 */
final class Backends implements AutoCloseable {

    private final IsolatedBackend baseline;
    private final IsolatedBackend candidate;
    private final boolean compliance;
    private final DifferentialRunner runner;

    Backends() {
        this(false);
    }

    /**
     * Loads the backends.
     *
     * @param compliance whether to include the candidate in its compliance
     *        mode ({@code JLTS-C})
     * @throws IllegalArgumentException if the compliance mode is requested
     *         for a candidate that does not have it
     */
    Backends(boolean compliance) {
        this.baseline = JaninoInstallation.baseline().load();
        this.candidate = JaninoInstallation.candidate().load();
        this.compliance = compliance;
        try {
            this.runner = new DifferentialRunner(new DifferentialRunner.Backends(new JavacBackend(), baseline,
                    candidate, compliance ? new ComplianceBackend(candidate) : null), Backends.class.getClassLoader());
        } catch (RuntimeException e) {
            try {
                close();
            } catch (IOException x) {
                e.addSuppressed(x);
            }
            throw e;
        }
    }

    DifferentialRunner runner() {
        return runner;
    }

    /**
     * Returns the ledger of the candidate: the ledger of its development line,
     * with the entries that apply to it and to the backends of the run.
     */
    Ledger ledger() {
        return Ledger.forVersion(candidate.version(), compliance);
    }

    /**
     * Returns the class path for a reproducer: {@code qsim-runtime} and the
     * JARs of the candidate.
     */
    static String reproducerClassPath() throws IOException {
        List<String> entries = new ArrayList<>();
        entries.add(Reproducer.runtimeLocation().toAbsolutePath().toString());
        try (Stream<Path> jars = Files.list(JaninoInstallation.candidate().directory())) {
            jars.filter(p -> !p.getFileName().toString().startsWith("qsim-")).sorted()
                    .forEach(p -> entries.add(p.toAbsolutePath().toString()));
        }
        return String.join(File.pathSeparator, entries);
    }

    @Override
    public void close() throws IOException {
        baseline.close();
        candidate.close();
    }
}
