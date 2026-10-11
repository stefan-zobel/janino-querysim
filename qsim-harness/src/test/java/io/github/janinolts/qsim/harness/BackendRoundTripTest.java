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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.Outcome;
import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.runtime.Evaluator;
import io.github.janinolts.qsim.runtime.GeneratedClass;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone M1: hand-written units compile in one JVM with javac, the
 * baseline and the candidate, over all five API paths, with the same results;
 * each Janino version runs in a class loader of its own.
 */
class BackendRoundTripTest {

    private static final List<ApiPath> CLASS_PATHS =
            List.of(ApiPath.CLASS_BODY, ApiPath.SIMPLE_COMPILER, ApiPath.COMMONS_COMPILER);

    private static IsolatedBackend baseline;
    private static IsolatedBackend candidate;
    private static JavacBackend javac;

    @BeforeAll
    static void loadBackends() {
        baseline = JaninoInstallation.baseline().load();
        candidate = JaninoInstallation.candidate().load();
        javac = new JavacBackend();
    }

    @AfterAll
    static void closeBackends() throws IOException {
        if (baseline != null) {
            baseline.close();
        }
        if (candidate != null) {
            candidate.close();
        }
    }

    private static CompilerBackend backend(String id) {
        return switch (id) {
            case JaninoInstallation.BASELINE_ID -> baseline;
            case JaninoInstallation.CANDIDATE_ID -> candidate;
            case JavacBackend.ID -> javac;
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static Stream<Arguments> combinations(List<ApiPath> paths) {
        List<Arguments> result = new ArrayList<>();
        for (String id : List.of(JaninoInstallation.BASELINE_ID, JaninoInstallation.CANDIDATE_ID, JavacBackend.ID)) {
            for (ApiPath path : paths) {
                result.add(Arguments.of(id, path));
            }
        }
        return result.stream();
    }

    static Stream<Arguments> classPaths() {
        return combinations(CLASS_PATHS);
    }

    static Stream<Arguments> expressionPaths() {
        List<ApiPath> paths = new ArrayList<>(CLASS_PATHS);
        paths.add(ApiPath.EXPRESSION);
        return combinations(paths);
    }

    static Stream<Arguments> scriptPaths() {
        List<ApiPath> paths = new ArrayList<>(CLASS_PATHS);
        paths.add(ApiPath.SCRIPT);
        return combinations(paths);
    }

    private static CompileResult compile(String id, ApiPath path, GeneratedUnit unit) {
        CompileResult result = backend(id).compile(unit, options(path));
        assertEquals(Outcome.OK, result.outcome(), () -> id + ' ' + path + ": " + result);
        return result;
    }

    private static CompileOptions options(ApiPath path) {
        return CompileOptions.of(path, BackendRoundTripTest.class.getClassLoader());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("classPaths")
    void wholeStageClass(String id, ApiPath path) throws Exception {
        GeneratedUnit unit = HandWrittenUnits.wholeStage();
        CompileResult result = compile(id, path, unit);

        assertSame(GeneratedClass.class, result.mainClass().getSuperclass());
        GeneratedClass instance = (GeneratedClass) Instances.instantiate(unit, result.mainClass());
        assertEquals(HandWrittenUnits.wholeStageResult(), instance.generate(unit.references()));
        assertTrue(result.classFiles().containsKey(unit.qualifiedName()), result.classFiles().keySet()::toString);
        assertTrue(result.classFiles().containsKey(unit.qualifiedName() + "$Counter"),
                result.classFiles().keySet()::toString);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("classPaths")
    void operatorClass(String id, ApiPath path) throws Exception {
        GeneratedUnit unit = HandWrittenUnits.operatorClass();
        CompileResult result = compile(id, path, unit);

        Evaluator instance = (Evaluator) Instances.instantiate(unit, result.mainClass());
        assertEquals(21, instance.evaluate(new Object[] {7}));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("expressionPaths")
    void expression(String id, ApiPath path) throws Exception {
        CompileResult result = compile(id, path, HandWrittenUnits.expression());

        assertNotNull(result.evaluator());
        assertEquals(17, result.evaluator().evaluate(new Object[] {4, 5}));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scriptPaths")
    void script(String id, ApiPath path) throws Exception {
        CompileResult result = compile(id, path, HandWrittenUnits.script());

        assertEquals(3_628_800L, result.evaluator().evaluate(new Object[] {10}));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("classPaths")
    void compileError(String id, ApiPath path) {
        CompileResult result = backend(id).compile(HandWrittenUnits.invalid(), options(path));

        assertEquals(Outcome.REJECTED, result.outcome(), result::toString);
        assertTrue(result.line() > 0, result::toString);
        assertTrue(result.column() > 0, result::toString);
    }

    @Test
    void compileErrorsOfBothJaninoVersionsAtTheSameLocation() {
        for (ApiPath path : CLASS_PATHS) {
            CompileResult a = baseline.compile(HandWrittenUnits.invalid(), options(path));
            CompileResult b = candidate.compile(HandWrittenUnits.invalid(), options(path));
            assertEquals(a.line(), b.line(), path::toString);
            assertEquals(a.column(), b.column(), path::toString);
        }
        assertEquals(2, baseline.compile(HandWrittenUnits.invalid(), options(ApiPath.CLASS_BODY)).line());
    }

    @Test
    void versions() {
        assertEquals("J3112", baseline.id());
        assertEquals("JLTS", candidate.id());
        assertEquals("JAVAC", javac.id());
        assertEquals(System.getProperty("qsim.baseline.version"), baseline.version());
        assertEquals(System.getProperty("qsim.candidate.version"), candidate.version());
        assertEquals(Runtime.version().toString(), javac.version());
    }

    @Test
    void classFileVersions() {
        GeneratedUnit unit = HandWrittenUnits.wholeStage();
        CompileOptions asShipped = options(ApiPath.CLASS_BODY);
        CompileOptions normalized = asShipped.withTargetVersion(8);

        assertEquals(50, majorVersion(baseline.compile(unit, asShipped), unit));
        assertEquals(52, majorVersion(candidate.compile(unit, asShipped), unit));
        assertEquals(52, majorVersion(baseline.compile(unit, normalized), unit));
        assertEquals(52, majorVersion(candidate.compile(unit, normalized), unit));
        assertEquals(52, majorVersion(javac.compile(unit, asShipped), unit));
    }

    @Test
    void eachJaninoVersionInAClassLoaderOfItsOwn() throws Exception {
        String name = "org.codehaus.janino.SimpleCompiler";
        Class<?> a = Class.forName(name, false, baseline.classLoader());
        Class<?> b = Class.forName(name, false, candidate.classLoader());
        assertNotSame(a, b);
        assertSame(baseline.classLoader(), a.getClassLoader());
        assertSame(candidate.classLoader(), b.getClassLoader());

        // A second copy of the same version, as for the negative control of the measurements.
        try (IsolatedBackend copy = JaninoInstallation.candidate().load()) {
            assertNotSame(b, Class.forName(name, false, copy.classLoader()));
            assertEquals(candidate.version(), copy.version());
        }
    }

    @Test
    void complianceMode() {
        CompileOptions options = options(ApiPath.SIMPLE_COMPILER).withCompliance(true);
        GeneratedUnit unit = HandWrittenUnits.wholeStage();

        assertEquals(Outcome.UNSUPPORTED, baseline.compile(unit, options).outcome());
        Outcome expected = candidate.version().startsWith("3.1.") ? Outcome.UNSUPPORTED : Outcome.OK;
        assertEquals(expected, candidate.compile(unit, options).outcome());
    }

    private static int majorVersion(CompileResult result, GeneratedUnit unit) {
        assertEquals(Outcome.OK, result.outcome(), result::toString);
        Map<String, byte[]> classFiles = result.classFiles();
        byte[] bytes = classFiles.get(unit.qualifiedName());
        return ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }
}
