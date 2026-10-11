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
import io.github.janinolts.qsim.plan.Schema;

/**
 * The code of a comparator of rows, shared by the class styles, in the total
 * order of the interpreter.
 */
final class SortCodegen {

    private SortCodegen() {
    }

    /**
     * Returns an expression that compares two non-null values of a type in the
     * total order, negative, zero or positive; integral types with conditional
     * expressions, the others with {@code compare} or {@code compareTo}.
     */
    static String compare(String a, String b, DataType type) {
        return switch (type) {
            case BYTE, SHORT, INT, LONG, DATE -> "(" + a + " < " + b + " ? -1 : (" + a + " == " + b + " ? 0 : 1))";
            case FLOAT -> "Float.compare(" + a + ", " + b + ")";
            case DOUBLE -> "Double.compare(" + a + ", " + b + ")";
            case BOOLEAN -> "Boolean.compare(" + a + ", " + b + ")";
            case STRING, DECIMAL -> a + ".compareTo(" + b + ")";
        };
    }

    /**
     * Writes the body of {@code int compare(Object[] a, Object[] b)}: the keys
     * in order, {@code NULL} first or last, descending keys negated.
     */
    static void body(CodeWriter w, CodegenContext ctx, PlanNode.Sort sort, String a, String b) {
        ctx.tag("ternary");
        Schema schema = sort.input().schema();
        for (PlanNode.SortKey key : sort.keys()) {
            DataType type = schema.type(key.column());
            String n = ctx.fresh("");
            String x = "x" + n;
            String y = "y" + n;
            w.line("Object " + x + " = " + a + "[" + key.column() + "];");
            w.line("Object " + y + " = " + b + "[" + key.column() + "];");
            w.open("if (" + x + " == null || " + y + " == null)");
            w.open("if (" + x + " != " + y + ")");
            w.line("return " + x + " == null ? " + (key.nullsFirst() ? "-1 : 1" : "1 : -1") + ";");
            w.close();
            w.reopen("else");
            String c = "c" + n;
            w.line("int " + c + " = " + compare(JavaTypes.unbox(type, x), JavaTypes.unbox(type, y), type) + ";");
            w.open("if (" + c + " != 0)");
            w.line("return " + (key.ascending() ? c : "-" + c) + ";");
            w.close();
            w.close();
        }
        w.line("return 0;");
    }
}
