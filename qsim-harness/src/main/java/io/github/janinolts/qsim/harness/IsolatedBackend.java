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

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.CompilerPhases;
import io.github.janinolts.qsim.backend.GeneratedUnit;

import java.io.IOException;
import java.net.URLClassLoader;

/**
 * A Janino backend in a class loader of its own. Closing it closes the class
 * loader; classes that were loaded through it stay usable, but no new ones can
 * be loaded.
 */
public final class IsolatedBackend implements CompilerBackend, AutoCloseable {

    private final CompilerBackend backend;
    private final URLClassLoader loader;

    IsolatedBackend(CompilerBackend backend, URLClassLoader loader) {
        this.backend = backend;
        this.loader = loader;
    }

    @Override
    public String id() {
        return backend.id();
    }

    @Override
    public String version() {
        return backend.version();
    }

    @Override
    public CompileResult compile(GeneratedUnit unit, CompileOptions options) {
        return backend.compile(unit, options);
    }

    /** Returns the separate phases of the compilation, or {@code null} if the adapter cannot separate them. */
    public CompilerPhases phases() {
        return backend instanceof CompilerPhases p ? p : null;
    }

    /** Returns the class loader that holds the adapter and this Janino version. */
    public ClassLoader classLoader() {
        return loader;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    @Override
    public String toString() {
        return backend.id() + ' ' + backend.version();
    }
}
