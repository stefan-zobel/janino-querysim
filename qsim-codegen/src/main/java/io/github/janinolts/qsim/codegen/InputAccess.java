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
import io.github.janinolts.qsim.plan.Schema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How generated code reads a column of the current input row.
 */
sealed interface InputAccess {

    /** Writes the statements that read a column, and returns its null-pair. */
    ExprCode column(int index, DataType type, CodeWriter w, CodegenContext ctx);

    /**
     * Returns the local variables that the access reads, as parameters
     * ({@code type name}) of a method that reads the same columns; fields need
     * none.
     */
    List<String> parameters();

    /**
     * Reads from an {@code Object[]} row of boxed values: a null check and an
     * unboxing cast per read.
     *
     * @param variable the variable that holds the row
     * @param schema the schema of the row
     * @param rowMayBeNull whether the whole row may be {@code null}, as the
     *        build row of an unmatched left outer join; then every column is
     *        {@code NULL}
     */
    record BoxedRow(String variable, Schema schema, boolean rowMayBeNull) implements InputAccess {

        @Override
        public ExprCode column(int index, DataType type, CodeWriter w, CodegenContext ctx) {
            ctx.tag("boxing");
            String n = ctx.fresh("");
            String isNull = "isNull" + n;
            String value = "value" + n;
            String element = variable + "[" + index + "]";
            boolean nullable = schema.nullable(index) || rowMayBeNull;
            String nullCheck = (rowMayBeNull ? variable + " == null || " : "") + element + " == null";
            if (!nullable) {
                w.line(JavaTypes.javaType(type) + " " + value + " = " + JavaTypes.unbox(type, element) + ";");
                return new ExprCode("false", value, type);
            }
            w.line("boolean " + isNull + " = " + nullCheck + ";");
            w.line(JavaTypes.javaType(type) + " " + value + " = " + isNull + " ? " + JavaTypes.defaultValue(type)
                    + " : " + JavaTypes.unbox(type, element) + ";");
            return new ExprCode(isNull, value, type);
        }

        @Override
        public List<String> parameters() {
            return List.of("Object[] " + variable);
        }
    }

    /**
     * Reads from the arrays of a column batch, held in fields named
     * {@code <prefix>_values_<i>} and {@code <prefix>_nulls_<i>}.
     *
     * @param prefix the prefix of the fields
     * @param rowVariable the variable that holds the index of the row
     * @param schema the schema of the batch
     */
    record ColumnBatch(String prefix, String rowVariable, Schema schema) implements InputAccess {

        @Override
        public ExprCode column(int index, DataType type, CodeWriter w, CodegenContext ctx) {
            String n = ctx.fresh("");
            String isNull = "isNull" + n;
            String value = "value" + n;
            w.line(JavaTypes.javaType(type) + " " + value + " = " + prefix + "_values_" + index + "[" + rowVariable
                    + "];");
            if (!schema.nullable(index)) {
                return new ExprCode("false", value, type);
            }
            w.line("boolean " + isNull + " = " + prefix + "_nulls_" + index + "[" + rowVariable + "];");
            return new ExprCode(isNull, value, type);
        }

        @Override
        public List<String> parameters() {
            return List.of("int " + rowVariable);
        }
    }

    /**
     * Reads null-pairs that are already computed, e.g. the output of a
     * projection: local variables, or fields of a split projection, which
     * methods can read without parameters. Two columns can share one
     * null-pair, e.g. a column that a projection passes through twice.
     *
     * @param codes the null-pairs, per column
     */
    record Computed(List<ExprCode> codes) implements InputAccess {

        @Override
        public ExprCode column(int index, DataType type, CodeWriter w, CodegenContext ctx) {
            return codes.get(index);
        }

        @Override
        public List<String> parameters() {
            Set<String> result = new LinkedHashSet<>();
            for (ExprCode c : codes) {
                if (!c.isNull().equals("false") && !c.isNull().startsWith(PipelineCodegen.FIELD_PREFIX)) {
                    result.add("boolean " + c.isNull());
                }
                if (!c.value().startsWith(PipelineCodegen.FIELD_PREFIX)) {
                    result.add(JavaTypes.javaType(c.type()) + " " + c.value());
                }
            }
            return List.copyOf(result);
        }
    }

    /**
     * The columns of one access followed by those of another, as the probe
     * row and the build row of a join.
     *
     * @param left the first columns
     * @param leftWidth the number of the first columns
     * @param right the following columns
     */
    record Concat(InputAccess left, int leftWidth, InputAccess right) implements InputAccess {

        @Override
        public ExprCode column(int index, DataType type, CodeWriter w, CodegenContext ctx) {
            return index < leftWidth ? left.column(index, type, w, ctx)
                    : right.column(index - leftWidth, type, w, ctx);
        }

        @Override
        public List<String> parameters() {
            Set<String> result = new LinkedHashSet<>(left.parameters());
            result.addAll(right.parameters());
            return List.copyOf(result);
        }
    }

    /** Reads from an {@code Object[]} row whose columns may all be {@code NULL}. */
    static InputAccess boxedRow(String variable, Schema schema) {
        return new BoxedRow(variable, schema, false);
    }

    /** Reads already computed null-pairs. */
    static InputAccess projected(List<ExprCode> codes) {
        return new Computed(codes);
    }
}
