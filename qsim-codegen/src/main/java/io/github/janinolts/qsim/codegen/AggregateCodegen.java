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
import io.github.janinolts.qsim.plan.PlanNode.AggregateCall;
import io.github.janinolts.qsim.plan.PlanNode.AggregateFunction;
import io.github.janinolts.qsim.plan.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * The code of an aggregation, shared by the class styles: a buffer class with
 * typed fields per aggregate function, the statements that add a row to a
 * buffer (compound assignments, also with narrow types), and the null-pairs of
 * the results. The semantics are those of the interpreter: sums of integral
 * types in {@code long} (with ANSI through {@code Math.addExact}), sums of
 * floating-point types and averages in {@code double}, decimals from
 * {@code BigDecimal.ZERO}, minimum and maximum in the total order, the first
 * of equal values kept.
 */
final class AggregateCodegen {

    private final PlanNode.HashAggregate aggregate;
    private final Schema input;
    private final CodegenContext ctx;

    AggregateCodegen(PlanNode.HashAggregate aggregate, CodegenContext ctx) {
        this.aggregate = aggregate;
        this.input = aggregate.input().schema();
        this.ctx = ctx;
    }

    private DataType argumentType(AggregateCall call) {
        return input.type(call.column());
    }

    /** Returns the text of the buffer class, at the given indentation. */
    String bufferClass(String name, String modifiers, int level) {
        ctx.tag("compound");
        ctx.tag("member-class");
        CodeWriter w = new CodeWriter(level);
        w.open(modifiers + "class " + name);
        List<AggregateCall> calls = aggregate.aggregates();
        for (int k = 0; k < calls.size(); k++) {
            AggregateCall call = calls.get(k);
            switch (call.function()) {
                case COUNT_STAR, COUNT -> w.line("long count_" + k + ";");
                case SUM -> {
                    DataType t = call.type(input);
                    w.line(JavaTypes.javaType(t) + " sum_" + k + (t == DataType.DECIMAL ? " = BigDecimal.ZERO;" : ";"));
                    w.line("boolean has_" + k + ";");
                }
                case AVG -> {
                    boolean decimal = argumentType(call) == DataType.DECIMAL;
                    w.line((decimal ? "BigDecimal" : "double") + " sum_" + k + (decimal ? " = BigDecimal.ZERO;" : ";"));
                    w.line("long count_" + k + ";");
                }
                case MIN, MAX -> {
                    w.line(JavaTypes.javaType(argumentType(call)) + " extreme_" + k + ";");
                    w.line("boolean has_" + k + ";");
                }
            }
        }
        w.close();
        return w.toString();
    }

    /**
     * Writes the statements that add the current row to a buffer.
     *
     * @param buffer the variable of the buffer
     * @param access how to read the columns of the row
     */
    void update(CodeWriter w, String buffer, InputAccess access) {
        List<AggregateCall> calls = aggregate.aggregates();
        for (int k = 0; k < calls.size(); k++) {
            AggregateCall call = calls.get(k);
            if (call.function() == AggregateFunction.COUNT_STAR) {
                w.line(buffer + ".count_" + k + "++;");
                continue;
            }
            DataType t = argumentType(call);
            ExprCode v = access.column(call.column(), t, w, ctx);
            boolean guarded = !v.isNull().equals("false");
            if (guarded) {
                w.open("if (!" + v.isNull() + ")");
            }
            String field = buffer + ".";
            switch (call.function()) {
                case COUNT -> w.line(field + "count_" + k + "++;");
                case SUM -> {
                    if (t == DataType.DECIMAL) {
                        w.line(field + "sum_" + k + " = " + field + "sum_" + k + ".add(" + v.value() + ");");
                    } else if (t.isIntegral() && ctx.ansi()) {
                        w.line(field + "sum_" + k + " = Math.addExact(" + field + "sum_" + k + ", (long) " + v.value()
                                + ");");
                    } else {
                        w.line(field + "sum_" + k + " += " + v.value() + ";");
                    }
                    w.line(field + "has_" + k + " = true;");
                }
                case AVG -> {
                    if (t == DataType.DECIMAL) {
                        w.line(field + "sum_" + k + " = " + field + "sum_" + k + ".add(" + v.value() + ");");
                    } else {
                        w.line(field + "sum_" + k + " += " + v.value() + ";");
                    }
                    w.line(field + "count_" + k + "++;");
                }
                case MIN, MAX -> {
                    String better = SortCodegen.compare(v.value(), field + "extreme_" + k, t)
                            + (call.function() == AggregateFunction.MIN ? " < 0" : " > 0");
                    w.open("if (!" + field + "has_" + k + " || " + better + ")");
                    w.line(field + "extreme_" + k + " = " + v.value() + ";");
                    w.line(field + "has_" + k + " = true;");
                    w.close();
                }
                default -> throw new IllegalStateException(call.function().toString());
            }
            if (guarded) {
                w.close();
            }
        }
    }

    /** Writes the null-pairs of the results of a buffer and returns them, in the order of the aggregates. */
    List<ExprCode> results(CodeWriter w, String buffer) {
        List<ExprCode> result = new ArrayList<>();
        List<AggregateCall> calls = aggregate.aggregates();
        for (int k = 0; k < calls.size(); k++) {
            AggregateCall call = calls.get(k);
            DataType t = call.type(input);
            String n = ctx.fresh("");
            String isNull = "isNull" + n;
            String value = "value" + n;
            String field = buffer + ".";
            switch (call.function()) {
                case COUNT_STAR, COUNT -> {
                    w.line("long " + value + " = " + field + "count_" + k + ";");
                    result.add(new ExprCode("false", value, t));
                    continue;
                }
                case SUM -> {
                    w.line("boolean " + isNull + " = !" + field + "has_" + k + ";");
                    w.line(JavaTypes.javaType(t) + " " + value + " = " + field + "sum_" + k + ";");
                }
                case AVG -> {
                    w.line("boolean " + isNull + " = " + field + "count_" + k + " == 0;");
                    String average = t == DataType.DECIMAL
                            ? "DecimalFunctions.divide(" + field + "sum_" + k + ", BigDecimal.valueOf(" + field + "count_"
                                    + k + "))"
                            : field + "sum_" + k + " / " + field + "count_" + k;
                    w.line(JavaTypes.javaType(t) + " " + value + " = " + isNull + " ? " + JavaTypes.defaultValue(t)
                            + " : " + average + ";");
                }
                case MIN, MAX -> {
                    w.line("boolean " + isNull + " = !" + field + "has_" + k + ";");
                    w.line(JavaTypes.javaType(t) + " " + value + " = " + field + "extreme_" + k + ";");
                }
            }
            result.add(new ExprCode(isNull, value, t));
        }
        return result;
    }
}
