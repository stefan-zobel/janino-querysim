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

import java.util.Map;

/**
 * Type names in source form, as a unit uses them: a primitive type or
 * {@code void}, a fully qualified class name ({@code java.lang.String}), or
 * either followed by {@code []} once per array dimension ({@code int[][]}).
 * Nested classes are named by their binary names ({@code java.util.Map$Entry})
 * where a {@code Class} object is needed, i.e. as the superclass, an interface,
 * a parameter type or a return type.
 */
public final class TypeNames {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "byte", byte.class, "short", short.class, "char", char.class,
            "int", int.class, "long", long.class, "float", float.class, "double", double.class,
            "void", void.class);

    private static final Map<String, String> WRAPPERS = Map.of(
            "boolean", "java.lang.Boolean", "byte", "java.lang.Byte", "short", "java.lang.Short",
            "char", "java.lang.Character", "int", "java.lang.Integer", "long", "java.lang.Long",
            "float", "java.lang.Float", "double", "java.lang.Double");

    private TypeNames() {
    }

    /**
     * Returns the class of a type name, loaded through the given class loader
     * without initializing it.
     *
     * @throws IllegalArgumentException if the class cannot be found
     */
    public static Class<?> resolve(String typeName, ClassLoader loader) {
        String element = typeName.strip();
        int dimensions = 0;
        while (element.endsWith("[]")) {
            element = element.substring(0, element.length() - 2).strip();
            dimensions++;
        }
        Class<?> type = PRIMITIVES.get(element);
        if (type == null) {
            try {
                type = Class.forName(element, false, loader);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Type not found: " + typeName, e);
            }
        }
        for (int i = 0; i < dimensions; i++) {
            type = type.arrayType();
        }
        return type;
    }

    /**
     * Returns the wrapper class name of a primitive type, e.g.
     * {@code java.lang.Integer} for {@code int}; any other type name unchanged.
     */
    public static String boxed(String typeName) {
        return WRAPPERS.getOrDefault(typeName.strip(), typeName);
    }

    /** Returns the source form of a type name: binary names of nested classes with {@code .} instead of {@code $}. */
    public static String sourceForm(String typeName) {
        return typeName.replace('$', '.');
    }
}
