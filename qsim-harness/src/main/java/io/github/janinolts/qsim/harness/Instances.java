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

package io.github.janinolts.qsim.harness;

import io.github.janinolts.qsim.backend.GeneratedUnit;

/**
 * Instantiates the main class of a compiled unit the way the unit says.
 * Reflection is used only here; the instance is then used through the types of
 * {@code qsim-runtime}.
 */
public final class Instances {

    private Instances() {
    }

    /**
     * Creates an instance of the main class.
     *
     * @throws ReflectiveOperationException if the constructor is missing or
     *         throws; a {@code LinkageError}, e.g. a {@code VerifyError}, is
     *         thrown unwrapped
     */
    public static Object instantiate(GeneratedUnit unit, Class<?> mainClass) throws ReflectiveOperationException {
        return switch (unit.instantiation()) {
            case NO_ARG_CONSTRUCTOR -> mainClass.getDeclaredConstructor().newInstance();
            case REFERENCES_CONSTRUCTOR ->
                    mainClass.getDeclaredConstructor(Object[].class).newInstance((Object) unit.references());
        };
    }
}
