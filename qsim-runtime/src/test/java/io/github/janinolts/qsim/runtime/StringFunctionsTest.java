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

package io.github.janinolts.qsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StringFunctionsTest {

    @Test
    void substringClamps() {
        assertEquals("abc", StringFunctions.substring("abcdef", 1, 3));
        assertEquals("abc", StringFunctions.substring("abcdef", 0, 3));
        assertEquals("abc", StringFunctions.substring("abcdef", Integer.MIN_VALUE, 3));
        assertEquals("ef", StringFunctions.substring("abcdef", 5, 100));
        assertEquals("ef", StringFunctions.substring("abcdef", 5, Integer.MAX_VALUE));
        assertEquals("", StringFunctions.substring("abcdef", 7, 1));
        assertEquals("", StringFunctions.substring("abcdef", Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals("", StringFunctions.substring("abcdef", 2, -1));
        // Code units: half of a surrogate pair.
        assertEquals("\uD834", StringFunctions.substring("\uD834\uDD1E", 1, 1));
    }

    @Test
    void upperPerCodeUnit() {
        assertEquals("ABC", StringFunctions.upper("abc"));
        assertEquals("\u00DF", StringFunctions.upper("\u00DF"));
        assertEquals("\u00C9", StringFunctions.upper("\u00E9"));
        assertEquals("I", StringFunctions.upper("\u0131"));
        assertEquals("\uD801\uDC28", StringFunctions.upper("\uD801\uDC28"));
        assertEquals("", StringFunctions.upper(""));
    }

    @Test
    void like() {
        assertTrue(StringFunctions.like("", ""));
        assertTrue(StringFunctions.like("", "%"));
        assertTrue(StringFunctions.like("", "%%"));
        assertFalse(StringFunctions.like("", "_"));
        assertTrue(StringFunctions.like("abc", "abc"));
        assertTrue(StringFunctions.like("abc", "a%"));
        assertTrue(StringFunctions.like("abc", "%c"));
        assertTrue(StringFunctions.like("abc", "%b%"));
        assertTrue(StringFunctions.like("abc", "a_c"));
        assertFalse(StringFunctions.like("abc", "a_"));
        assertTrue(StringFunctions.like("aXbXc", "a%b%c"));
        assertTrue(StringFunctions.like("abcbc", "%bc"));
        assertFalse(StringFunctions.like("abcbd", "%bc"));
        assertTrue(StringFunctions.like("a%c", "a%c"));
        assertFalse(StringFunctions.like("ABC", "abc"));
        // '_' matches one code unit, so a supplementary character needs two.
        assertFalse(StringFunctions.like("\uD834\uDD1E", "_"));
        assertTrue(StringFunctions.like("\uD834\uDD1E", "__"));
    }
}
