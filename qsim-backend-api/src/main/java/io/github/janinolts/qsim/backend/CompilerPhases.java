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
 * The first two phases of a compilation, one at a time, for a backend that
 * can separate them: parsing the {@linkplain UnitRenderer#compilationUnit
 * compilation unit} of a unit, and compiling the syntax tree to class files.
 * Loading and instantiating the classes are the other two phases; they do not
 * depend on the compiler.
 * <p>
 * Every call uses a new compiler instance. A syntax tree is compiled at most
 * once, since compiling may change it.
 */
public interface CompilerPhases {

    /**
     * Parses the compilation unit of a unit.
     *
     * @return the syntax tree, opaque to the caller
     * @throws Exception if the source text does not parse
     */
    Object parse(GeneratedUnit unit, CompileOptions options) throws Exception;

    /**
     * Compiles a syntax tree from {@link #parse} to class files, without
     * loading them; the parent class loader of the options resolves the
     * referenced types.
     *
     * @return the class files, by binary class name
     * @throws Exception if the syntax tree does not compile
     */
    Map<String, byte[]> compile(Object syntaxTree, CompileOptions options) throws Exception;
}
