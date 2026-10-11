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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The columns of a table or of the output of an operator.
 *
 * @param fields the columns, in order
 */
public record Schema(List<Schema.Field> fields) {

    /**
     * A column.
     *
     * @param name the name
     * @param type the type
     * @param nullable whether the column may contain {@code NULL}; generated
     *        code needs no null checks for a column that cannot
     */
    public record Field(String name, DataType type, boolean nullable) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        /** Creates a nullable column. */
        public Field(String name, DataType type) {
            this(name, type, true);
        }
    }

    public Schema {
        fields = List.copyOf(fields);
    }

    /** Returns a schema of nullable columns with the given types and the names {@code c0}, {@code c1}, ... */
    public static Schema of(DataType... types) {
        return build(true, types);
    }

    /** Returns a schema of columns without {@code NULL}, named {@code c0}, {@code c1}, ... */
    public static Schema notNull(DataType... types) {
        return build(false, types);
    }

    private static Schema build(boolean nullable, DataType... types) {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            fields.add(new Field("c" + i, types[i], nullable));
        }
        return new Schema(fields);
    }

    /** Tells whether a column may contain {@code NULL}. */
    public boolean nullable(int index) {
        return fields.get(index).nullable();
    }

    /** Returns the number of columns. */
    public int size() {
        return fields.size();
    }

    /** Returns the type of a column. */
    public DataType type(int index) {
        return fields.get(index).type();
    }

    /**
     * Checks that every column reference of an expression exists in this
     * schema and has its type.
     *
     * @throws IllegalArgumentException otherwise
     */
    public void check(Expression expression) {
        if (expression instanceof Expression.Column c) {
            if (c.index() >= fields.size()) {
                throw new IllegalArgumentException("No column " + c.index() + " in " + this);
            }
            if (c.type() != type(c.index())) {
                throw new IllegalArgumentException("Column " + c.index() + " is a " + type(c.index())
                        + ", not a " + c.type());
            }
        }
        for (Expression child : expression.children()) {
            check(child);
        }
    }
}
