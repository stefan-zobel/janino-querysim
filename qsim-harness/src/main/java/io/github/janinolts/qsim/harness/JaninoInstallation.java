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

import io.github.janinolts.qsim.backend.CompilerBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A directory with the JARs of one Janino version and the adapter:
 * {@code janino-*.jar}, {@code commons-compiler-*.jar} and
 * {@code qsim-backend-janino-*.jar}, exactly one of each. The build creates
 * one for the baseline and one for the candidate.
 *
 * @param id the identifier of the backend, e.g. {@code J3112}
 * @param directory the directory
 */
public record JaninoInstallation(String id, Path directory) {

    /** The identifier of the baseline, Janino 3.1.12 by default. */
    public static final String BASELINE_ID = "J3112";

    /** The identifier of the candidate, janino-lts. */
    public static final String CANDIDATE_ID = "JLTS";

    /** The system property with the directory of the baseline. */
    public static final String BASELINE_DIR_PROPERTY = "qsim.baseline.dir";

    /** The system property with the directory of the candidate. */
    public static final String CANDIDATE_DIR_PROPERTY = "qsim.candidate.dir";

    private static final String ADAPTER_CLASS = "io.github.janinolts.qsim.backend.janino.JaninoBackend";

    private static final List<String> JAR_PREFIXES = List.of("janino-", "commons-compiler-", "qsim-backend-janino-");

    public JaninoInstallation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(directory, "directory");
    }

    /**
     * Returns the baseline: the directory from {@value #BASELINE_DIR_PROPERTY},
     * or else {@code janino-baseline} next to the JAR or the class directory of
     * qsim, as the build creates it.
     */
    public static JaninoInstallation baseline() {
        return new JaninoInstallation(BASELINE_ID, directory(BASELINE_DIR_PROPERTY, "janino-baseline"));
    }

    /**
     * Returns the candidate: the directory from {@value #CANDIDATE_DIR_PROPERTY},
     * or else {@code janino-candidate} next to the JAR or the class directory of
     * qsim.
     */
    public static JaninoInstallation candidate() {
        return new JaninoInstallation(CANDIDATE_ID, directory(CANDIDATE_DIR_PROPERTY, "janino-candidate"));
    }

    private static Path directory(String property, String name) {
        String configured = System.getProperty(property);
        if (configured != null) {
            return Path.of(configured);
        }
        try {
            Path location = Path.of(JaninoInstallation.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            Path next = location.getParent().resolve(name);
            if (Files.isDirectory(next)) {
                return next;
            }
        } catch (URISyntaxException | RuntimeException e) {
            // fall back to the working directory
        }
        return Path.of("target", name);
    }

    /**
     * Loads the backend in a new class loader whose parent is the class loader
     * of the harness. Every call creates a new class loader, so loading the
     * same installation twice gives two independent copies of Janino, e.g.
     * for the negative control of the measurements.
     *
     * @throws IllegalStateException if the directory does not contain exactly
     *         the expected JARs, or the adapter cannot be instantiated
     */
    public IsolatedBackend load() {
        List<URL> urls = jars();
        URLClassLoader loader = new URLClassLoader("qsim-" + id, urls.toArray(new URL[0]),
                JaninoInstallation.class.getClassLoader());
        try {
            CompilerBackend backend = Class.forName(ADAPTER_CLASS, true, loader)
                    .asSubclass(CompilerBackend.class)
                    .getConstructor(String.class)
                    .newInstance(id);
            return new IsolatedBackend(backend, loader);
        } catch (ReflectiveOperationException | LinkageError e) {
            closeQuietly(loader, e);
            throw new IllegalStateException("Cannot load the Janino backend from " + directory, e);
        }
    }

    private List<URL> jars() {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("No directory " + directory.toAbsolutePath()
                    + "; build with \"mvnw verify\", or set -D" + BASELINE_DIR_PROPERTY + " or -D"
                    + CANDIDATE_DIR_PROPERTY);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<URL> urls = new ArrayList<>();
        for (String prefix : JAR_PREFIXES) {
            List<Path> matches = files.stream().filter(p -> matches(p, prefix)).toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected exactly one " + prefix + "*.jar in "
                        + directory.toAbsolutePath() + ", found " + matches + " (after a version change, run \"mvnw clean\")");
            }
            try {
                urls.add(matches.get(0).toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        if (files.size() != JAR_PREFIXES.size()) {
            throw new IllegalStateException("Unexpected JARs in " + directory.toAbsolutePath() + ": " + files);
        }
        return urls;
    }

    /** Tells whether a file name is the prefix followed by a version number. */
    private static boolean matches(Path file, String prefix) {
        String name = file.getFileName().toString();
        return name.startsWith(prefix) && name.length() > prefix.length()
                && Character.isDigit(name.charAt(prefix.length()));
    }

    private static void closeQuietly(URLClassLoader loader, Throwable failure) {
        try {
            loader.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }
}
