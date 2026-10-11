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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.codegen.Generator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every unit of the suite {@code smoke}: javac must give the result of the
 * interpreter, otherwise the generator or the interpreter of qsim is wrong.
 * (The Janino backends are compared with the ledger in
 * {@link CorrectnessSuiteTest}.)
 */
class ScenarioRoundTripTest {

    static Stream<Arguments> units() {
        return Workloads.smoke().stream().map(u -> Arguments.of(u.key().toString(), u));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("units")
    void javacGivesTheResultOfTheInterpreter(String key, UnitCase unit) {
        RunResult expected = QueryRunner.interpret(unit.plan(), unit.config().ansi(), unit.tables());
        GeneratedQuery query = Generator.generate(unit.plan(), unit.config());
        CompilerBackend javac = TestBackends.all().get(0);
        RunResult actual = QueryRunner.run(query, javac, TestBackends.options(), unit.tables());
        assertTrue(expected.sameAs(actual), () -> "expected " + expected + ", got " + actual + "\n" + source(query));
    }

    private static String source(GeneratedQuery query) {
        List<String> sources = new ArrayList<>();
        for (GeneratedUnit u : query.units()) {
            sources.add(UnitRenderer.compilationUnit(u));
        }
        return String.join("\n", sources);
    }
}
