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

package io.github.janinolts.qsim.backend.javac;

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.UnitKind;
import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.runtime.Evaluator;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * The backend for javac of the running JDK, through {@code javax.tools}, in
 * memory.
 * <p>
 * javac always compiles a complete compilation unit (see
 * {@link UnitRenderer#compilationUnit}) with {@code --release 8}, whatever API
 * path and target version the options name. It resolves the classes that the
 * code references from a class path of files, not through the parent class
 * loader; by default, that class path is the location of {@code qsim-runtime}.
 */
public final class JavacBackend implements CompilerBackend {

    /** The identifier of this backend. */
    public static final String ID = "JAVAC";

    private final JavaCompiler compiler;
    private final List<Path> classPath;

    /** Creates the backend with the location of {@code qsim-runtime} as the class path. */
    public JavacBackend() {
        this(List.of(locationOf(Evaluator.class)));
    }

    /**
     * Creates the backend.
     *
     * @param classPath the JAR files and directories that javac compiles
     *        against
     */
    public JavacBackend(List<Path> classPath) {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler; javac needs a JDK, not a JRE");
        }
        this.classPath = List.copyOf(classPath);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return Runtime.version().toString();
    }

    @Override
    public CompileResult compile(GeneratedUnit unit, CompileOptions options) {
        if (!options.apiPath().accepts(unit.kind())) {
            throw new IllegalArgumentException(options.apiPath() + " does not accept a unit of kind " + unit.kind());
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter output = new StringWriter();
        Map<String, byte[]> classFiles;
        try (StandardJavaFileManager standard =
                     compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
             MemoryFileManager fileManager = new MemoryFileManager(standard)) {
            standard.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            JavaFileObject source = MemoryFileManager.source(unit.qualifiedName(),
                    UnitRenderer.compilationUnit(unit));
            Boolean success = compiler.getTask(output, fileManager, diagnostics, javacOptions(options), null,
                    List.of(source)).call();
            if (!Boolean.TRUE.equals(success)) {
                return rejected(diagnostics, output);
            }
            classFiles = fileManager.classFiles();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            return CompileResult.internalError(e);
        }

        try {
            MemoryClassLoader loader = new MemoryClassLoader(classFiles, options.parent());
            Class<?> mainClass = loader.loadClass(unit.qualifiedName());
            Evaluator evaluator = null;
            if (unit.kind() != UnitKind.CLASS) {
                evaluator = mainClass.asSubclass(Evaluator.class).getDeclaredConstructor().newInstance();
            }
            return CompileResult.ok(mainClass, evaluator, classFiles);
        } catch (LinkageError e) {
            return CompileResult.invalidClass(classFiles, e);
        } catch (ReflectiveOperationException e) {
            return CompileResult.internalError(e);
        }
    }

    private static List<String> javacOptions(CompileOptions options) {
        List<String> result = new ArrayList<>(List.of("--release", "8", "-proc:none", "-Xlint:-options"));
        List<String> debug = new ArrayList<>();
        if (options.debugSource()) {
            debug.add("source");
        }
        if (options.debugLines()) {
            debug.add("lines");
        }
        if (options.debugVars()) {
            debug.add("vars");
        }
        result.add(debug.isEmpty() ? "-g:none" : "-g:" + String.join(",", debug));
        return result;
    }

    private static CompileResult rejected(DiagnosticCollector<JavaFileObject> diagnostics, StringWriter output) {
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                return CompileResult.rejected(d.getMessage(Locale.ROOT), toInt(d.getLineNumber()),
                        toInt(d.getColumnNumber()), null);
            }
        }
        return CompileResult.internalError(new IllegalStateException(
                "javac failed without an error diagnostic: " + output));
    }

    private static int toInt(long position) {
        return position == Diagnostic.NOPOS ? 0 : (int) position;
    }

    private static Path locationOf(Class<?> c) {
        CodeSource source = Objects.requireNonNull(c.getProtectionDomain().getCodeSource(),
                () -> "No code source for " + c);
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate " + c, e);
        }
    }
}
