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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.Outcome;
import io.github.janinolts.qsim.runtime.GeneratedClass;

import org.junit.jupiter.api.Test;

class JavacBackendTest {

    private final JavacBackend javac = new JavacBackend();
    private final CompileOptions options =
            CompileOptions.of(ApiPath.SIMPLE_COMPILER, JavacBackendTest.class.getClassLoader());

    @Test
    void compilesAndLoadsAClass() throws Exception {
        GeneratedUnit unit = GeneratedUnit.ofClass("p", "G", """
                    public Object generate(Object[] references) {
                        return new Runnable() {
                            public void run() {
                            }
                        }.getClass().getName();
                    }
                """).extendedClass(GeneratedClass.class.getName()).build();

        CompileResult result = javac.compile(unit, options);

        assertEquals(Outcome.OK, result.outcome(), result::toString);
        assertSame(GeneratedClass.class, result.mainClass().getSuperclass());
        assertEquals("p.G$1", ((GeneratedClass) result.mainClass().getDeclaredConstructor().newInstance())
                .generate(new Object[0]));
        assertEquals(2, result.classFiles().size());
        assertEquals(52, majorVersion(result.classFiles().get("p.G")));
    }

    @Test
    void compilesAnExpression() throws Exception {
        GeneratedUnit unit = GeneratedUnit.ofExpression("", "E", "a * 3 + b", "int")
                .parameter("int", "a").parameter("int", "b").build();

        CompileResult result = javac.compile(unit, options.withApiPath(ApiPath.EXPRESSION));

        assertEquals(Outcome.OK, result.outcome(), result::toString);
        assertEquals(17, result.evaluator().evaluate(new Object[] {4, 5}));
    }

    @Test
    void reportsTheLocationOfACompileError() {
        GeneratedUnit unit = GeneratedUnit.ofClass("", "R", """
                    void f() {
                        int x = "text";
                    }
                """).build();

        CompileResult result = javac.compile(unit, options);

        assertEquals(Outcome.REJECTED, result.outcome());
        assertEquals(3, result.line());
        assertTrue(result.column() > 0);
        assertTrue(result.message().contains("incompatible types"), result.message());
    }

    @Test
    void rejectsLanguageFeaturesAfterJava8() {
        GeneratedUnit unit = GeneratedUnit.ofClass("", "V", """
                    void f() {
                        var x = 1;
                    }
                """).build();

        assertEquals(Outcome.REJECTED, javac.compile(unit, options).outcome());
    }

    @Test
    void rejectsAUnitThatThePathDoesNotAccept() {
        GeneratedUnit unit = GeneratedUnit.ofClass("", "C", "").build();

        assertThrows(IllegalArgumentException.class, () -> javac.compile(unit, options.withApiPath(ApiPath.SCRIPT)));
    }

    private static int majorVersion(byte[] classFile) {
        return ((classFile[6] & 0xff) << 8) | (classFile[7] & 0xff);
    }
}
