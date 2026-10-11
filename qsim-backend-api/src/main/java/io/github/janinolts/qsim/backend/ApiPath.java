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

/**
 * The API of Janino through which a unit is compiled. javac always compiles a
 * complete compilation unit and ignores the path, except for checking that it
 * {@linkplain #accepts(UnitKind) accepts} the unit.
 */
public enum ApiPath {

    /**
     * {@code ClassBodyEvaluator} with the class name, the default imports, the
     * superclass and the interfaces set through setters, then
     * {@code cook(fileName, body)}; the way Apache Spark compiles.
     */
    CLASS_BODY,

    /**
     * {@code SimpleCompiler} with a complete compilation unit, then
     * {@code getClassLoader().loadClass(name)}; the way Apache Flink compiles.
     */
    SIMPLE_COMPILER,

    /**
     * The {@code commons-compiler} interfaces: the default
     * {@code ICompilerFactory} from {@code CompilerFactoryFactory}, then an
     * {@code IClassBodyEvaluator}; the way Apache Calcite compiles.
     */
    COMMONS_COMPILER,

    /** {@code ExpressionEvaluator}: a single expression with parameters. */
    EXPRESSION,

    /** {@code ScriptEvaluator}: a sequence of statements with parameters. */
    SCRIPT;

    /**
     * Tells whether a unit of the given kind can be compiled through this
     * path. The three class paths accept every kind: an expression or a script
     * is rendered into a class that implements {@code Evaluator}. The
     * evaluator paths accept only their own kind.
     */
    public boolean accepts(UnitKind kind) {
        return switch (this) {
            case CLASS_BODY, SIMPLE_COMPILER, COMMONS_COMPILER -> true;
            case EXPRESSION -> kind == UnitKind.EXPRESSION;
            case SCRIPT -> kind == UnitKind.SCRIPT;
        };
    }
}
