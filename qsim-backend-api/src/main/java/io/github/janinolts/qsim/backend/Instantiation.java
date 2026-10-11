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
 * How the harness instantiates the main class of a unit of kind
 * {@link UnitKind#CLASS}.
 */
public enum Instantiation {

    /**
     * The no-arg constructor, as Spark and Calcite do; the references are
     * passed later, e.g. to {@code GeneratedClass.generate(Object[])}.
     */
    NO_ARG_CONSTRUCTOR,

    /** A constructor with one parameter {@code Object[] references}, as Flink does. */
    REFERENCES_CONSTRUCTOR
}
