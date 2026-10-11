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

/**
 * The code style: the API path and the shape of the code of one family of
 * engines.
 */
public enum Style {

    /**
     * Apache Spark: a class body whose {@code generate(Object[])} returns an
     * inner iterator class with one fused loop over all operators of a
     * pipeline.
     */
    WHOLE_STAGE,

    /** Apache Flink: one complete class per function, combined by fixed operators of the runtime. */
    OPERATOR_CLASSES,

    /** Apache Calcite: chains of anonymous inner classes and boxed values. */
    ENUMERABLE,

    /**
     * ksqlDB and filters: a single expression, or a sequence of statements
     * that computes one output row from one input row.
     */
    EXPRESSION
}
