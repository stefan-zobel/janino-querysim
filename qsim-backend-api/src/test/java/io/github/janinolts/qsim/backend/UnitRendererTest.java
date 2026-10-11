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

package io.github.janinolts.qsim.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class UnitRendererTest {

    @Test
    void classUnitAsCompilationUnit() {
        GeneratedUnit unit = GeneratedUnit.ofClass("p.q", "C", "    int x;\n")
                .imports("java.util.List", "java.util.*")
                .extendedClass("java.lang.Object")
                .implementedInterfaces("java.util.Map$Entry", "java.io.Serializable")
                .build();

        assertEquals("""
                package p.q;

                import java.util.List;
                import java.util.*;

                public class C extends java.lang.Object implements java.util.Map.Entry, java.io.Serializable {
                    int x;
                }
                """, UnitRenderer.compilationUnit(unit));
        assertEquals("    int x;\n", UnitRenderer.classBody(unit));
    }

    @Test
    void classUnitInTheDefaultPackage() {
        GeneratedUnit unit = GeneratedUnit.ofClass("", "C", "").build();

        assertEquals("public class C {\n}\n", UnitRenderer.compilationUnit(unit));
        assertEquals("C", unit.qualifiedName());
    }

    @Test
    void expressionAsEvaluatorClass() {
        GeneratedUnit unit = GeneratedUnit.ofExpression("p", "E", "a * 3 + b", "int")
                .parameter("int", "a")
                .parameter("java.lang.String[]", "b")
                .build();

        assertEquals("""
                    public Object evaluate(Object[] arguments) throws Exception {
                        return eval((java.lang.Integer) arguments[0], (java.lang.String[]) arguments[1]);
                    }

                    private static int eval(int a, java.lang.String[] b) throws Exception {
                        return a * 3 + b;
                    }
                """, UnitRenderer.classBody(unit));
        assertEquals(List.of("io.github.janinolts.qsim.runtime.Evaluator"), UnitRenderer.implementedInterfaces(unit));
    }

    @Test
    void voidScriptAsEvaluatorClass() {
        GeneratedUnit unit = GeneratedUnit.ofScript("", "S", "        System.out.println();\n", "void").build();

        assertEquals("""
                    public Object evaluate(Object[] arguments) throws Exception {
                        eval();
                        return null;
                    }

                    private static void eval() throws Exception {
                        System.out.println();
                    }
                """, UnitRenderer.classBody(unit));
    }

    @Test
    void invalidUnits() {
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedUnit.ofClass("", "C", "").parameter("int", "a").build());
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedUnit.ofExpression("", "E", "1", "int").extendedClass("java.lang.Object").build());
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedUnit.ofExpression("", "E", "1", "int")
                        .instantiation(Instantiation.REFERENCES_CONSTRUCTOR).build());
    }

    @Test
    void apiPathsAcceptUnitKinds() {
        for (UnitKind kind : UnitKind.values()) {
            assertEquals(true, ApiPath.CLASS_BODY.accepts(kind));
            assertEquals(true, ApiPath.SIMPLE_COMPILER.accepts(kind));
            assertEquals(true, ApiPath.COMMONS_COMPILER.accepts(kind));
            assertEquals(kind == UnitKind.EXPRESSION, ApiPath.EXPRESSION.accepts(kind));
            assertEquals(kind == UnitKind.SCRIPT, ApiPath.SCRIPT.accepts(kind));
        }
    }
}
