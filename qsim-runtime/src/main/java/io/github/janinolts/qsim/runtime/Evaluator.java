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

/**
 * A compiled expression or script, evaluated for one set of arguments.
 * <p>
 * The Janino backends wrap the evaluators of Janino in this interface; with
 * the other API paths and with javac, the generated class implements it
 * itself. Either way, the caller invokes compiled code without reflection.
 */
public interface Evaluator {

    /**
     * Evaluates the expression or script.
     *
     * @param arguments one value per parameter, in the order of the
     *        parameters; primitive values boxed
     * @return the result, boxed, or {@code null} for a script without a
     *         result
     * @throws Exception whatever the evaluated code throws, unwrapped
     */
    Object evaluate(Object[] arguments) throws Exception;
}
