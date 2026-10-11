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
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Table;
import io.github.janinolts.qsim.runtime.ColumnBatch;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts tables into the input that generated code reads.
 */
public final class InputData {

    private InputData() {
    }

    /**
     * Returns the inputs for {@code BufferedRowIterator.init}: per scan, in
     * order, the rows of its table as an {@code Object[][]} for the layout
     * {@code ROWS}, or a {@link ColumnBatch} for {@code COLUMNS}.
     */
    public static Object[] wholeStage(List<PlanNode.Scan> scans, Map<String, Table> tables) {
        Object[] inputs = new Object[scans.size()];
        for (int k = 0; k < inputs.length; k++) {
            Table table = table(scans.get(k), tables);
            inputs[k] = scans.get(k).layout() == PlanNode.Layout.ROWS ? rows(table) : columnBatch(table);
        }
        return inputs;
    }

    /** Returns per scan, in order, the rows of its table, whatever the layout. */
    public static Object[][][] rows(List<PlanNode.Scan> scans, Map<String, Table> tables) {
        Object[][][] result = new Object[scans.size()][][];
        for (int k = 0; k < result.length; k++) {
            result[k] = rows(table(scans.get(k), tables));
        }
        return result;
    }

    private static Table table(PlanNode.Scan scan, Map<String, Table> tables) {
        return Objects.requireNonNull(tables.get(scan.table()), () -> "No table " + scan.table());
    }

    /** Returns the rows as an array of arrays; the row arrays are shared. */
    public static Object[][] rows(Table table) {
        return table.rows().toArray(new Object[0][]);
    }

    /** Returns the table in columnar form. */
    public static ColumnBatch columnBatch(Table table) {
        int n = table.size();
        int columns = table.schema().size();
        Object[] values = new Object[columns];
        boolean[][] nulls = new boolean[columns][];
        for (int c = 0; c < columns; c++) {
            DataType type = table.schema().type(c);
            Object array = Array.newInstance(elementClass(type), n);
            boolean[] flags = new boolean[n];
            for (int r = 0; r < n; r++) {
                Object v = table.rows().get(r)[c];
                if (v == null) {
                    flags[r] = true;
                } else {
                    Array.set(array, r, v);
                }
            }
            values[c] = array;
            nulls[c] = flags;
        }
        return new ColumnBatch(n, values, nulls);
    }

    private static Class<?> elementClass(DataType type) {
        return switch (type) {
            case BOOLEAN -> boolean.class;
            case BYTE -> byte.class;
            case SHORT -> short.class;
            case INT, DATE -> int.class;
            case LONG -> long.class;
            case FLOAT -> float.class;
            case DOUBLE -> double.class;
            case STRING -> String.class;
            case DECIMAL -> BigDecimal.class;
        };
    }
}
