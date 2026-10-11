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

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Schema;

import java.util.List;
import java.util.TreeSet;

/**
 * The style {@link Style#EXPRESSION}: the engine loops over the rows and calls
 * an evaluator per row.
 * <ul>
 * <li>A projection of a single expression that needs no null handling and
 * cannot throw becomes one Java expression for an {@code ExpressionEvaluator},
 * with one primitive parameter per column it reads; the result is the value of
 * the output row.</li>
 * <li>Everything else becomes a script for a {@code ScriptEvaluator}, with the
 * row as its only parameter; it returns the output row, or {@code null} if a
 * filter drops the row.</li>
 * </ul>
 */
final class ExpressionStyleCodegen {

    static final String EXPRESSION_CLASS_NAME = "GeneratedExpression";
    static final String SCRIPT_CLASS_NAME = "GeneratedScript";

    private ExpressionStyleCodegen() {
    }

    /** Tells whether a plan fits the style: filters and projections over one scan. */
    static boolean supports(PlanNode plan) {
        return switch (plan) {
            case PlanNode.Scan s -> true;
            case PlanNode.Filter f -> supports(f.input());
            case PlanNode.Project p -> supports(p.input());
            default -> false;
        };
    }

    static GeneratedQuery generate(PlanNode plan, CodegenConfig config) {
        if (!supports(plan)) {
            throw new UnsupportedOperationException("The style EXPRESSION supports only filters and projections");
        }
        Pipeline pipeline = Pipeline.of(plan);
        CodegenContext ctx = new CodegenContext(config);
        Schema schema = pipeline.scan().schema();
        if (!config.ansi() && pipeline.operators().size() == 1
                && pipeline.operators().get(0) instanceof PlanNode.Project p && p.columns().size() == 1
                && isSimple(p.columns().get(0).expression(), schema)) {
            return expression(pipeline, p.columns().get(0).expression(), config, ctx);
        }
        return script(pipeline, config, ctx);
    }

    private static GeneratedQuery script(Pipeline pipeline, CodegenConfig config, CodegenContext ctx) {
        CodeWriter w = new CodeWriter(2);
        InputAccess access = InputAccess.boxedRow("row", pipeline.scan().schema());
        Schema schema = pipeline.scan().schema();
        for (PlanNode op : pipeline.operators()) {
            switch (op) {
                case PlanNode.Filter f -> PipelineCodegen.filter(ctx, w, access, f.condition(), "return null;");
                case PlanNode.Project p -> {
                    access = InputAccess.projected(PipelineCodegen.project(ctx, w, access,
                            p.columns().stream().map(PlanNode.NamedExpression::expression).toList(), false));
                    schema = p.schema();
                }
                default -> throw new IllegalStateException("Not supported in the style EXPRESSION: " + op);
            }
        }
        List<ExprCode> output = PipelineCodegen.columns(ctx, w, access, schema);
        w.line("return " + PipelineCodegen.outputRow(output) + ";");
        GeneratedUnit unit = GeneratedUnit.ofScript(Generator.PACKAGE, SCRIPT_CLASS_NAME, w.toString(),
                        "java.lang.Object[]")
                .imports(Generator.IMPORTS)
                .parameter("java.lang.Object[]", "row")
                .metadata(Generator.metadata(config, ctx))
                .build();
        return new GeneratedQuery(List.of(unit), ApiPath.SCRIPT, new GeneratedQuery.Driver.PerRow(List.of(), false),
                List.of(pipeline.scan()), false);
    }

    private static GeneratedQuery expression(Pipeline pipeline, Expression e, CodegenConfig config,
            CodegenContext ctx) {
        TreeSet<Integer> columns = new TreeSet<>();
        collectColumns(e, columns);
        GeneratedUnit.Builder builder = GeneratedUnit.ofExpression(Generator.PACKAGE, EXPRESSION_CLASS_NAME,
                render(e), JavaTypes.javaType(e.type()));
        Schema schema = pipeline.scan().schema();
        for (int c : columns) {
            builder.parameter(JavaTypes.javaType(schema.type(c)), "p" + c);
        }
        GeneratedUnit unit = builder.imports(Generator.IMPORTS).metadata(Generator.metadata(config, ctx)).build();
        return new GeneratedQuery(List.of(unit), ApiPath.EXPRESSION,
                new GeneratedQuery.Driver.PerRow(List.copyOf(columns), true), List.of(pipeline.scan()), false);
    }

    private static final List<DataType> SIMPLE_TYPES =
            List.of(DataType.INT, DataType.LONG, DataType.FLOAT, DataType.DOUBLE);

    /**
     * Tells whether an expression can be one Java expression: columns without
     * {@code NULL}, non-null literals, {@code + - *} and unary minus on
     * {@code INT}, {@code LONG}, {@code FLOAT} and {@code DOUBLE}.
     */
    static boolean isSimple(Expression e, Schema schema) {
        if (!SIMPLE_TYPES.contains(e.type())) {
            return false;
        }
        return switch (e) {
            case Expression.Column c -> !schema.nullable(c.index());
            case Expression.Literal l -> l.value() != null;
            case Expression.Arithmetic a -> a.op() != Expression.ArithmeticOp.DIVIDE
                    && a.op() != Expression.ArithmeticOp.REMAINDER
                    && isSimple(a.left(), schema) && isSimple(a.right(), schema);
            case Expression.Negate n -> isSimple(n.operand(), schema);
            default -> false;
        };
    }

    private static String render(Expression e) {
        return switch (e) {
            case Expression.Column c -> "p" + c.index();
            case Expression.Literal l -> JavaTypes.literal(l.type(), l.value());
            case Expression.Arithmetic a -> {
                String symbol = switch (a.op()) {
                    case ADD -> " + ";
                    case SUBTRACT -> " - ";
                    case MULTIPLY -> " * ";
                    default -> throw new IllegalStateException(a.op().toString());
                };
                yield "(" + widen(a.left(), a.type()) + symbol + widen(a.right(), a.type()) + ")";
            }
            case Expression.Negate n -> "(-" + render(n.operand()) + ")";
            default -> throw new IllegalStateException("Not simple: " + e);
        };
    }

    private static String widen(Expression e, DataType to) {
        String s = render(e);
        return e.type() == to ? s : "((" + JavaTypes.javaType(to) + ") " + s + ")";
    }

    private static void collectColumns(Expression e, TreeSet<Integer> columns) {
        if (e instanceof Expression.Column c) {
            columns.add(c.index());
        }
        for (Expression child : e.children()) {
            collectColumns(child, columns);
        }
    }
}
