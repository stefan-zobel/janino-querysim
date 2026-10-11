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

import java.util.Map;

import org.junit.jupiter.api.Test;

class TypeNamesTest {

    private final ClassLoader loader = TypeNamesTest.class.getClassLoader();

    @Test
    void resolve() {
        assertEquals(int.class, TypeNames.resolve("int", loader));
        assertEquals(void.class, TypeNames.resolve("void", loader));
        assertEquals(int[][].class, TypeNames.resolve("int[][]", loader));
        assertEquals(String[].class, TypeNames.resolve("java.lang.String []", loader));
        assertEquals(Map.Entry.class, TypeNames.resolve("java.util.Map$Entry", loader));
        assertThrows(IllegalArgumentException.class, () -> TypeNames.resolve("no.such.Type", loader));
    }

    @Test
    void boxed() {
        assertEquals("java.lang.Character", TypeNames.boxed("char"));
        assertEquals("java.lang.String", TypeNames.boxed("java.lang.String"));
        assertEquals("int[]", TypeNames.boxed("int[]"));
    }

    @Test
    void sourceForm() {
        assertEquals("java.util.Map.Entry", TypeNames.sourceForm("java.util.Map$Entry"));
    }
}
