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

import java.util.Arrays;

/**
 * A grouping or join key of boxed values, compared after {@link Keys}: the
 * constructor brings each value into its canonical form.
 */
public final class RowKey {

    private final Object[] values;

    /** Creates a key; the values are made canonical in place. */
    public RowKey(Object[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] = Keys.canonical(values[i]);
        }
        this.values = values;
    }

    /** Returns the canonical values; the array must not be changed. */
    public Object[] values() {
        return values;
    }

    /** Tells whether a value of the key is {@code null}; such a key never matches in a join. */
    public boolean hasNull() {
        for (Object v : values) {
            if (v == null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RowKey && Arrays.equals(values, ((RowKey) other).values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
