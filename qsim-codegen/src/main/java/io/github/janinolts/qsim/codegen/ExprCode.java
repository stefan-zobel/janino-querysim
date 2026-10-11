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

import io.github.janinolts.qsim.plan.DataType;

/**
 * The result of generated code for an expression: a null-pair of a
 * {@code boolean} and a value of the Java type of the expression. Each is a
 * variable or a constant; the value is meaningful only if the flag is false.
 *
 * @param isNull the flag, e.g. {@code isNull_3} or {@code false}
 * @param value the value, e.g. {@code value_3}
 * @param type the type of the expression
 */
record ExprCode(String isNull, String value, DataType type) {
}
