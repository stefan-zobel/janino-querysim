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


/**
 * A small library of pull-based operators after the model of linq4j, which
 * Apache Calcite uses: generated code combines them with anonymous classes.
 * The elements are untyped, so that generated code needs no generics; rows are
 * {@code Object[]}. The operators record in a {@code Position} which row they
 * produce, so that an exception can be attributed to it.
 */
package io.github.janinolts.qsim.runtime.linq;
