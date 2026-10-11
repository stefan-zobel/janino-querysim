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

package io.github.janinolts.qsim.codegen;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.plan.PlanNode;

import java.util.List;
import java.util.Objects;

/**
 * The code generated for a plan, and how to run it.
 *
 * @param units the generated units, each compiled with a compiler instance of
 *        its own; the first is the main unit
 * @param apiPath the API path through which Janino compiles them, as the
 *        engine family of the style does
 * @param driver how the harness runs the compiled code
 * @param scans the scans of the plan, in the order of the inputs
 * @param ordered whether the order of the output rows is defined (a sort)
 */
public record GeneratedQuery(List<GeneratedUnit> units, ApiPath apiPath, Driver driver, List<PlanNode.Scan> scans,
        boolean ordered) {

    public GeneratedQuery {
        units = List.copyOf(units);
        if (units.isEmpty()) {
            throw new IllegalArgumentException("No units");
        }
        Objects.requireNonNull(apiPath, "apiPath");
        Objects.requireNonNull(driver, "driver");
        scans = List.copyOf(scans);
    }

    /** Returns the main unit. */
    public GeneratedUnit unit() {
        return units.get(0);
    }

    /** How the harness runs the compiled code. */
    public sealed interface Driver {

        /**
         * The main class extends {@code GeneratedClass}: instantiate it with
         * its no-arg constructor, call {@code generate(references)}, which
         * returns a {@code BufferedRowIterator}, pass the inputs from
         * {@link InputData#wholeStage} to its {@code init}, and iterate.
         */
        record WholeStage() implements Driver {
        }

        /**
         * An evaluator, called once per row of the only scan.
         *
         * @param argumentColumns if {@code singleValue}: the columns whose
         *        values are the arguments, in order; otherwise empty, and the
         *        only argument is the whole row as an {@code Object[]}
         * @param singleValue whether the result is the single value of the
         *        output row; otherwise it is the output row as an
         *        {@code Object[]}, or {@code null} if the row is filtered out
         */
        record PerRow(List<Integer> argumentColumns, boolean singleValue) implements Driver {
            public PerRow {
                argumentColumns = List.copyOf(argumentColumns);
            }
        }

        /**
         * The main class implements {@code Bindable}: instantiate it with its
         * no-arg constructor and call {@code bind} with a {@code DataContext}
         * of the rows from {@link InputData#rows}.
         */
        record Enumerable() implements Driver {
        }

        /**
         * One class per function, each instantiated with its constructor
         * {@code (Object[] references)}, combined by the operators of the
         * runtime as the graph says; see {@link OperatorGraph}.
         *
         * @param graph the operators, with the indexes of their units
         */
        record OperatorClasses(OperatorGraph.Node graph) implements Driver {
        }
    }
}
