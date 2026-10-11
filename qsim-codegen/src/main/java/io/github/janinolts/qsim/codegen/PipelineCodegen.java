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
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * The code of filters and projections inside a fused loop, shared by the
 * styles with a loop: whole-stage and expression.
 */
final class PipelineCodegen {

    /** The prefix of the fields that hold the columns of a split projection. */
    static final String FIELD_PREFIX = "proj_";

    /** The maximum number of parameter slots of a method, minus one for {@code this}. */
    private static final int MAX_PARAMETER_SLOTS = 254;

    private PipelineCodegen() {
    }

    /** Writes a filter that leaves the row with the given statement if the condition is not true. */
    static void filter(CodegenContext ctx, CodeWriter w, InputAccess access, Expression condition, String exit) {
        w.comment(ctx.config().comments(), "filter");
        ExprCode c = new ExprCodegen(ctx, w, access).gen(condition);
        String drop = c.isNull().equals("false") ? "!" + c.value() : c.isNull() + " || !" + c.value();
        w.open("if (" + drop + ")");
        w.line(exit);
        w.close();
    }

    /**
     * Writes a projection and returns the null-pairs of its columns. Each
     * column is wrapped in {@code tryNesting} nested {@code try} statements
     * if the configuration asks for it. If {@code methods} is true and the
     * code is longer than the split threshold, the columns are computed in
     * helper methods (added to the context) whose results are fields.
     *
     * @param methods whether the class can take helper methods and fields
     */
    static List<ExprCode> project(CodegenContext ctx, CodeWriter w, InputAccess access, List<Expression> columns,
            boolean methods) {
        w.comment(ctx.config().comments(), "project");
        CodeWriter trial = new CodeWriter(w.level());
        int references = ctx.referenceCount();
        List<ExprCode> codes = new ArrayList<>();
        for (Expression e : columns) {
            codes.add(column(ctx, trial, access, e, methods));
        }
        int threshold = ctx.config().splitThreshold();
        if (!methods || threshold == 0 || trial.length() <= threshold || slots(access.parameters()) > MAX_PARAMETER_SLOTS) {
            w.append(trial);
            return codes;
        }
        ctx.tag("split");
        // The code of the trial is not used; neither are its references.
        ctx.truncateReferences(references);
        List<ExprCode> fields = new ArrayList<>();
        String parameters = String.join(", ", access.parameters());
        List<String> arguments = new ArrayList<>();
        for (String p : access.parameters()) {
            arguments.add(p.substring(p.lastIndexOf(' ') + 1));
        }
        CodeWriter helper = null;
        String helperName = null;
        for (Expression e : columns) {
            if (helper == null) {
                helperName = ctx.fresh("project");
                helper = new CodeWriter(3);
            }
            String n = ctx.fresh("");
            ExprCode field = new ExprCode(FIELD_PREFIX + "isNull" + n, FIELD_PREFIX + "value" + n, e.type());
            ctx.addField("private boolean " + field.isNull() + ";");
            ctx.addField("private " + JavaTypes.javaType(e.type()) + " " + field.value() + ";");
            ExprCode c = column(ctx, helper, access, e, true);
            helper.line(field.isNull() + " = " + c.isNull() + ";");
            helper.line(field.value() + " = " + c.value() + ";");
            fields.add(field);
            if (helper.length() > threshold) {
                addHelper(ctx, helperName, parameters, helper);
                w.line(helperName + "(" + String.join(", ", arguments) + ");");
                helper = null;
            }
        }
        if (helper != null) {
            addHelper(ctx, helperName, parameters, helper);
            w.line(helperName + "(" + String.join(", ", arguments) + ");");
        }
        return fields;
    }

    private static void addHelper(CodegenContext ctx, String name, String parameters, CodeWriter body) {
        CodeWriter m = new CodeWriter(2);
        m.open("private void " + name + "(" + parameters + ")");
        m.append(body);
        m.close();
        ctx.addMethod(m.toString());
    }

    /** The parameter slots of a parameter list: two for {@code long} and {@code double}. */
    private static int slots(List<String> parameters) {
        int slots = 0;
        for (String p : parameters) {
            slots += p.startsWith("long ") || p.startsWith("double ") ? 2 : 1;
        }
        return slots;
    }

    /**
     * Writes the code of one column, in nested {@code try} statements if the
     * configuration asks for them. With {@code tryJumps}, a labeled
     * {@code do} statement encloses them, a {@code break} leaves all of them
     * when the column is {@code NULL}, and each {@code finally} block holds a
     * {@code try} statement of its own.
     */
    static ExprCode column(CodegenContext ctx, CodeWriter w, InputAccess access, Expression e, boolean fields) {
        int depth = fields ? ctx.config().tryNesting() : 0;
        if (depth == 0) {
            return new ExprCodegen(ctx, w, access).gen(e);
        }
        boolean jumps = ctx.config().tryJumps();
        ctx.tag("try");
        if (jumps) {
            ctx.tag("try-jump");
        }
        ctx.addFieldOnce("private int tryDepth;");
        ctx.addFieldOnce("private Object lastError;");
        String n = ctx.fresh("");
        DataType type = e.type();
        ExprCode out = new ExprCode("isNull" + n, "value" + n, type);
        String label = "column" + n;
        w.line("boolean " + out.isNull() + " = true;");
        w.line(JavaTypes.javaType(type) + " " + out.value() + " = " + JavaTypes.defaultValue(type) + ";");
        if (jumps) {
            w.open(label + ": do");
        }
        for (int level = 0; level < depth; level++) {
            w.line("tryDepth++;");
            w.open("try");
        }
        ExprCode c = new ExprCodegen(ctx, w, access).gen(e);
        w.line(out.isNull() + " = " + c.isNull() + ";");
        w.line(out.value() + " = " + c.value() + ";");
        if (jumps) {
            w.open("if (" + out.isNull() + ")");
            w.line("break " + label + ";");
            w.close();
        }
        for (int level = 0; level < depth; level++) {
            String error = ctx.fresh("e");
            w.reopen("catch (ArithmeticException " + error + ")");
            w.line("lastError = " + error + ";");
            w.line("throw " + error + ";");
            w.reopen("finally");
            if (jumps) {
                String inner = ctx.fresh("e");
                w.open("try");
                w.line("tryDepth--;");
                w.reopen("catch (RuntimeException " + inner + ")");
                w.line("lastError = " + inner + ";");
                w.close();
            } else {
                w.line("tryDepth--;");
            }
            w.close();
        }
        if (jumps) {
            w.close(" while (false);");
        }
        return out;
    }

    /** Returns an expression that creates the output row, with the values boxed and {@code null} for NULL. */
    static String outputRow(List<ExprCode> columns) {
        List<String> values = new ArrayList<>();
        for (ExprCode c : columns) {
            values.add(boxed(c));
        }
        return "new Object[] {" + String.join(", ", values) + "}";
    }

    /** Returns an expression that boxes a null-pair, {@code null} for NULL. */
    static String boxed(ExprCode c) {
        String boxed = JavaTypes.box(c.type(), c.value());
        return c.isNull().equals("false") ? boxed : c.isNull() + " ? null : " + boxed;
    }

    /** Reads all columns of an access, e.g. to emit a row. */
    static List<ExprCode> columns(CodegenContext ctx, CodeWriter w, InputAccess access,
            Schema schema) {
        List<ExprCode> result = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            result.add(access.column(i, schema.type(i), w, ctx));
        }
        return result;
    }
}
