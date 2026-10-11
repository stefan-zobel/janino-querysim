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
 * A table in columnar form: per column an array of values and an array of
 * null flags. The value array of a column has the Java type of the column,
 * e.g. {@code int[]} or {@code String[]}; where a value is {@code NULL}, its
 * flag is set and the value is 0, {@code false} or {@code null}.
 */
public final class ColumnBatch {

    private final int rowCount;
    private final Object[] values;
    private final boolean[][] nulls;

    /**
     * Creates a batch.
     *
     * @param rowCount the number of rows
     * @param values per column the array of values, not copied
     * @param nulls per column the null flags, not copied
     */
    public ColumnBatch(int rowCount, Object[] values, boolean[][] nulls) {
        if (values.length != nulls.length) {
            throw new IllegalArgumentException(values.length + " value arrays, " + nulls.length + " null arrays");
        }
        this.rowCount = rowCount;
        this.values = values;
        this.nulls = nulls;
    }

    /** Returns the number of rows. */
    public int rowCount() {
        return rowCount;
    }

    /** Returns the values of a column, e.g. an {@code int[]}. */
    public Object values(int column) {
        return values[column];
    }

    /** Returns the null flags of a column. */
    public boolean[] nulls(int column) {
        return nulls[column];
    }
}
