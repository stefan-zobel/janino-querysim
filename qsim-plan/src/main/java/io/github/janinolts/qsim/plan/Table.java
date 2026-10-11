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

package io.github.janinolts.qsim.plan;

import java.util.List;
import java.util.Objects;

/**
 * The rows of a table, as boxed values.
 *
 * @param schema the schema
 * @param rows the rows, each with one value per column, {@code null} for
 *        {@code NULL}; the arrays are not copied and must not be changed
 */
public record Table(Schema schema, List<Object[]> rows) {

    public Table {
        Objects.requireNonNull(schema, "schema");
        rows = List.copyOf(rows);
        for (int r = 0; r < rows.size(); r++) {
            Object[] row = rows.get(r);
            if (row.length != schema.size()) {
                throw new IllegalArgumentException("Row " + r + " has " + row.length + " values, not "
                        + schema.size());
            }
            for (int c = 0; c < row.length; c++) {
                if (row[c] == null && !schema.nullable(c)) {
                    throw new IllegalArgumentException("Row " + r + ", column " + c + ": NULL in a column without NULL");
                }
                if (!schema.type(c).accepts(row[c])) {
                    throw new IllegalArgumentException("Row " + r + ", column " + c + ": not a "
                            + schema.type(c) + ": " + row[c].getClass().getName());
                }
            }
        }
    }

    /** Returns the number of rows. */
    public int size() {
        return rows.size();
    }
}
