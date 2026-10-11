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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.harness.Difference.Kind;
import io.github.janinolts.qsim.harness.Difference.Pair;
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.Plans;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The minimizer reduces a unit that shows a difference, and the reproducer
 * of a unit runs without qsim: a candidate that compiles with javac but turns
 * every {@code +} of the source into {@code -} produces the difference.
 */
class MinimizerTest {

    /** A candidate that compiles with javac, but turns every addition of the source into a subtraction. */
    static final class WrongBackend implements CompilerBackend {

        private final CompilerBackend javac = TestBackends.all().get(0);

        @Override
        public String id() {
            return "JLTS";
        }

        @Override
        public String version() {
            return "3.1.19";
        }

        @Override
        public CompileResult compile(GeneratedUnit u, CompileOptions options) {
            GeneratedUnit wrong = new GeneratedUnit(u.kind(), u.packageName(), u.className(), u.imports(),
                    u.extendedClass(), u.implementedInterfaces(), u.body().replace(" + ", " - "), u.parameters(),
                    u.returnType(), u.instantiation(), u.references(), u.metadata());
            return javac.compile(wrong, options);
        }
    }

    private static DifferentialRunner runner(CompilerBackend candidate) {
        List<CompilerBackend> all = TestBackends.all();
        return new DifferentialRunner(new DifferentialRunner.Backends(all.get(0), all.get(1), candidate),
                MinimizerTest.class.getClassLoader());
    }

    private static int planSize(UnitCase u) {
        int n = 0;
        for (var node : Plans.nodes(u.plan())) {
            n++;
            for (Expression e : Plans.expressions(node)) {
                n += Plans.size(e);
            }
        }
        return n;
    }

    @Test
    void aDifferenceIsMinimized() throws Exception {
        DifferentialRunner runner = runner(new WrongBackend());
        RandomPlans generator = RandomPlans.withDefaults();
        UnitCase unit = null;
        for (long seed = 1; seed <= 200 && unit == null; seed++) {
            UnitCase u = new RandomUnit(seed, Style.WHOLE_STAGE, Profile.STRICT, Variant.BASE, null).build(generator);
            UnitOutcome o = runner.run(u);
            if (planSize(u) > 12 && o.differences().stream()
                    .anyMatch(d -> d.pair() == Pair.JAVAC_JLTS && d.kind() == Kind.WRONG)) {
                unit = u;
            }
        }
        assertNotNull(unit, "No seed shows the difference");

        UnitCase original = unit;
        Minimizer.Result result = new Minimizer(runner, 400, 120_000)
                .minimize(original, new Minimizer.Target(Pair.JAVAC_JLTS, Kind.WRONG));

        UnitCase min = result.unit();
        assertTrue(result.outcome().differences().stream()
                .anyMatch(d -> d.pair() == Pair.JAVAC_JLTS && d.kind() == Kind.WRONG));
        assertTrue(planSize(min) < planSize(original), () -> planSize(min) + " >= " + planSize(original));
        int rows = min.tables().values().stream().mapToInt(t -> t.size()).sum();
        assertTrue(rows < original.tables().values().stream().mapToInt(t -> t.size()).sum());
        assertTrue(rows <= 4, () -> "rows: " + rows + "\n" + Reproducer.plan(min.plan(), 0));

        Path dir = Path.of("target", "repro-test", "minimized");
        Difference d = result.outcome().differences().stream().filter(x -> x.pair() == Pair.JAVAC_JLTS).findFirst()
                .orElseThrow();
        Reproducer.write(dir, min, result.outcome(), d, null, "");
        for (String f : List.of("case.txt", "plan.txt", "data.txt", "source-0.txt", "expected.txt",
                "actual-JLTS.txt", "Repro.java")) {
            assertTrue(Files.exists(dir.resolve(f)), f);
        }
    }

    /**
     * For every style, the reproducer of a unit runs with the Janino of the
     * baseline and gives the result of javac; the plans have joins and
     * aggregations where the style fits them.
     */
    @ParameterizedTest
    @EnumSource(Style.class)
    void theReproducerRunsWithoutQsim(Style style) throws Exception {
        DifferentialRunner runner = runner(new WrongBackend());
        RandomPlans generator = RandomPlans.withDefaults();
        UnitCase unit = null;
        UnitOutcome outcome = null;
        for (long seed = 1; seed <= 300 && unit == null; seed++) {
            UnitCase u = new RandomUnit(seed, style, Profile.STRICT, Variant.REFERENCES, null).build(generator);
            if (u == null || (style != Style.EXPRESSION && Plans.nodes(u.plan()).size() < 4)) {
                continue;
            }
            UnitOutcome o = runner.run(u);
            if (o.result("JAVAC").status() == RunResult.Status.OK && !o.result("JAVAC").rows().isEmpty()) {
                unit = u;
                outcome = o;
            }
        }
        assertNotNull(unit);
        Path dir = Path.of("target", "repro-test", style.name());
        Difference d = new Difference(unit.key(), Pair.JAVAC_JLTS, Kind.WRONG, Difference.Assessment.UNCLEAR, "test");
        Reproducer.write(dir, unit, outcome, d, null, "");

        List<String> classPath = new ArrayList<>();
        classPath.add(Reproducer.runtimeLocation().toString());
        try (Stream<Path> jars = Files.list(JaninoInstallation.baseline().directory())) {
            jars.filter(p -> !p.getFileName().toString().startsWith("qsim-"))
                    .forEach(p -> classPath.add(p.toAbsolutePath().toString()));
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = dir.resolve("output.txt");
        Process p = new ProcessBuilder(java.toString(), "-cp", String.join(File.pathSeparator, classPath),
                "Repro.java").directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), output);
        assertTrue(output.contains("SAME AS JAVAC"), output);
    }
}
