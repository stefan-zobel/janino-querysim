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
import io.github.janinolts.qsim.backend.Instantiation;
import io.github.janinolts.qsim.backend.UnitMetadata;
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.PlanIds;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Schema;
import io.github.janinolts.qsim.runtime.AggregateHandler;
import io.github.janinolts.qsim.runtime.KeyFunction;
import io.github.janinolts.qsim.runtime.RowComparator;
import io.github.janinolts.qsim.runtime.RowCondition;
import io.github.janinolts.qsim.runtime.RowFunction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The style {@link Style#OPERATOR_CLASSES}, after Flink: one complete class
 * per function (projection, filter condition, key selector, aggregation,
 * comparator), with a constructor that takes the references, compiled by a
 * {@code SimpleCompiler} each. The operators that combine them are fixed code
 * of the runtime; see {@link OperatorGraph}.
 */
final class OperatorClassesCodegen {

    private final CodegenConfig config;
    private final PlanIds ids;
    private final List<GeneratedUnit> units = new ArrayList<>();
    private final Set<String> featureTags = new LinkedHashSet<>();

    private OperatorClassesCodegen(PlanNode plan, CodegenConfig config) {
        this.config = config;
        this.ids = new PlanIds(plan);
    }

    static GeneratedQuery generate(PlanNode plan, CodegenConfig config) {
        OperatorClassesCodegen g = new OperatorClassesCodegen(plan, config);
        OperatorGraph.Node graph = g.node(plan);
        // Every unit carries the feature tags of the whole query.
        List<GeneratedUnit> units = new ArrayList<>();
        for (GeneratedUnit u : g.units) {
            UnitMetadata m = u.metadata();
            units.add(new GeneratedUnit(u.kind(), u.packageName(), u.className(), u.imports(), u.extendedClass(),
                    u.implementedInterfaces(), u.body(), u.parameters(), u.returnType(), u.instantiation(),
                    u.references(), new UnitMetadata(m.seed(), m.style(), m.profile(), m.configHash(),
                            List.copyOf(g.featureTags))));
        }
        return new GeneratedQuery(units, ApiPath.SIMPLE_COMPILER, new GeneratedQuery.Driver.OperatorClasses(graph),
                g.ids.scans(), Scenario.ordered(plan));
    }

    private OperatorGraph.Node node(PlanNode node) {
        int id = ids.id(node);
        return switch (node) {
            case PlanNode.Scan s -> new OperatorGraph.Scan(Generator.scanIndex(ids, s), s.table());
            case PlanNode.Filter f -> {
                OperatorGraph.Node input = node(f.input());
                yield new OperatorGraph.Filter(input, unit("Condition_" + id, RowCondition.class, (ctx, w) -> {
                    w.open("public boolean test(Object[] row)");
                    ExprCode c = new ExprCodegen(ctx, w, InputAccess.boxedRow("row", f.input().schema()))
                            .gen(f.condition());
                    w.line("return " + (c.isNull().equals("false") ? "" : "!" + c.isNull() + " && ") + c.value() + ";");
                    w.close();
                }));
            }
            case PlanNode.Project p -> {
                OperatorGraph.Node input = node(p.input());
                yield new OperatorGraph.Project(input, unit("Projection_" + id, RowFunction.class,
                        (ctx, w) -> projection(ctx, w, p)));
            }
            case PlanNode.Limit l -> new OperatorGraph.Limit(node(l.input()), l.count());
            case PlanNode.Union u -> {
                List<OperatorGraph.Node> inputs = new ArrayList<>();
                for (PlanNode input : u.inputs()) {
                    inputs.add(node(input));
                }
                yield new OperatorGraph.Union(inputs, ids.sourceLabel(u));
            }
            case PlanNode.Sort s -> {
                OperatorGraph.Node input = node(s.input());
                yield new OperatorGraph.Sort(input, unit("Comparator_" + id, RowComparator.class, (ctx, w) -> {
                    w.open("public int compare(Object[] a, Object[] b)");
                    SortCodegen.body(w, ctx, s, "a", "b");
                    w.close();
                }), ids.sourceLabel(s));
            }
            case PlanNode.HashJoin j -> {
                OperatorGraph.Node probe = node(j.probe());
                OperatorGraph.Node build = node(j.build());
                int probeKey = keySelector("KeySelector_" + id + "_probe", j.probe().schema(), j.probeKeys());
                int buildKey = keySelector("KeySelector_" + id + "_build", j.build().schema(), j.buildKeys());
                yield new OperatorGraph.Join(probe, build, probeKey, buildKey,
                        j.type() == PlanNode.JoinType.LEFT_OUTER, j.build().schema().size());
            }
            case PlanNode.HashAggregate a -> {
                OperatorGraph.Node input = node(a.input());
                int key = keySelector("KeySelector_" + id, a.input().schema(), a.groupColumns());
                int handler = unit("Aggregation_" + id, AggregateHandler.class, (ctx, w) -> {
                    AggregateCodegen aggregates = new AggregateCodegen(a, ctx);
                    w.raw(aggregates.bufferClass("Accumulator", "static final ", 1));
                    w.blank();
                    w.open("public Object createAccumulator()");
                    w.line("return new Accumulator();");
                    w.close();
                    w.blank();
                    w.open("public void accumulate(Object accumulator, Object[] row)");
                    w.line("Accumulator acc = (Accumulator) accumulator;");
                    aggregates.update(w, "acc", InputAccess.boxedRow("row", a.input().schema()));
                    w.close();
                    w.blank();
                    w.open("public Object[] getValue(Object accumulator)");
                    w.line("Accumulator acc = (Accumulator) accumulator;");
                    w.line("return " + PipelineCodegen.outputRow(aggregates.results(w, "acc")) + ";");
                    w.close();
                });
                yield new OperatorGraph.Aggregate(input, key, handler, ids.sourceLabel(a), a.groupColumns().isEmpty());
            }
        };
    }

    private int keySelector(String name, Schema schema, List<Integer> columns) {
        return unit(name, KeyFunction.class, (ctx, w) -> {
            w.open("public RowKey key(Object[] row)");
            InputAccess access = InputAccess.boxedRow("row", schema);
            List<String> values = new ArrayList<>();
            for (int c : columns) {
                values.add(PipelineCodegen.boxed(access.column(c, schema.type(c), w, ctx)));
            }
            w.line("return new RowKey(new Object[] {" + String.join(", ", values) + "});");
            w.close();
        });
    }

    /**
     * The method {@code apply} of a projection; if its code is longer than the
     * split threshold, the columns are computed in helper methods
     * {@code compute_<n>(Object[] row, Object[] out)}.
     */
    private void projection(CodegenContext ctx, CodeWriter w, PlanNode.Project p) {
        InputAccess access = InputAccess.boxedRow("row", p.input().schema());
        List<Expression> columns = p.columns().stream().map(PlanNode.NamedExpression::expression).toList();
        List<CodeWriter> parts = new ArrayList<>();
        CodeWriter part = new CodeWriter(2);
        int total = 0;
        for (int i = 0; i < columns.size(); i++) {
            CodeWriter column = new CodeWriter(2);
            ExprCode c = PipelineCodegen.column(ctx, column, access, columns.get(i), true);
            column.line("out[" + i + "] = " + PipelineCodegen.boxed(c) + ";");
            int threshold = config.splitThreshold();
            if (threshold > 0 && part.length() > 0 && part.length() + column.length() > threshold) {
                parts.add(part);
                part = new CodeWriter(2);
            }
            part.append(column);
            total += column.length();
        }
        parts.add(part);
        w.open("public Object[] apply(Object[] row)");
        w.line("Object[] out = new Object[" + columns.size() + "];");
        if (parts.size() == 1 || config.splitThreshold() == 0 || total <= config.splitThreshold()) {
            for (CodeWriter x : parts) {
                w.raw(x.toString());
            }
            w.line("return out;");
            w.close();
            return;
        }
        ctx.tag("split");
        List<String> helpers = new ArrayList<>();
        for (CodeWriter x : parts) {
            String name = ctx.fresh("compute");
            helpers.add(name);
            w.line(name + "(row, out);");
        }
        w.line("return out;");
        w.close();
        for (int i = 0; i < parts.size(); i++) {
            w.blank();
            w.open("private void " + helpers.get(i) + "(Object[] row, Object[] out)");
            w.raw(parts.get(i).toString());
            w.close();
        }
    }

    /** Creates a unit: a class that implements the interface, with a constructor that takes the references. */
    private int unit(String name, Class<?> iface, BiConsumer<CodegenContext, CodeWriter> body) {
        CodegenContext ctx = new CodegenContext(config);
        ctx.enableReferences("references[%d]");
        CodeWriter methods = new CodeWriter(1);
        body.accept(ctx, methods);
        CodeWriter w = new CodeWriter(1);
        w.line("private final Object[] references;");
        for (String field : ctx.fields()) {
            w.line(field);
        }
        w.blank();
        w.open("public " + name + "(Object[] references)");
        w.line("this.references = references;");
        w.close();
        w.blank();
        w.raw(methods.toString());
        featureTags.addAll(ctx.featureTags());
        units.add(GeneratedUnit.ofClass(Generator.PACKAGE, name, w.toString())
                .imports(Generator.IMPORTS)
                .implementedInterfaces(iface.getName())
                .instantiation(Instantiation.REFERENCES_CONSTRUCTOR)
                .references(ctx.references())
                .metadata(Generator.metadata(config, ctx))
                .build());
        return units.size() - 1;
    }
}
