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
import io.github.janinolts.qsim.plan.PlanIds;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Schema;
import io.github.janinolts.qsim.runtime.GeneratedClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The style {@link Style#WHOLE_STAGE}, after Spark: a class body whose
 * {@code generate(Object[] references)} returns an instance of an inner
 * iterator class.
 * <p>
 * Every pipeline is one loop over its source in which its operators are fused,
 * with {@code do { ... } while (false)} and {@code continue} for a row that a
 * filter drops, and a {@code for} loop over the matches of a join. The pipeline
 * at the top runs in {@code processNext()}, which returns after each source row
 * that produced output and continues there on the next call. A pipeline that
 * feeds an operator collecting its input (an aggregation into a hash map of
 * buffer objects with typed fields, a sort or a union into a list, the build
 * side of a join into a hash map of lists) runs in a method of its own. Before
 * the first row of a pipeline, the builds of its joins run from the top down,
 * then the collection of its source: the order in which the interpreter pulls.
 */
final class WholeStageCodegen {

    static final String CLASS_NAME = "GeneratedStage";

    private static final String ITERATOR = "GeneratedIterator";

    /** A pipeline: the streaming operators above a source, from the bottom up. */
    private record Chain(PlanNode source, List<PlanNode> operators) {
    }

    /** What a pipeline does with a row that passes all its operators. */
    private interface Sink {
        void emit(CodeWriter w, InputAccess access);
    }

    private final PlanNode plan;
    private final CodegenContext ctx;
    private final PlanIds ids;
    private final List<PlanNode.Scan> scans;
    private final Map<PlanNode, String> collectors = new IdentityHashMap<>();

    private WholeStageCodegen(PlanNode plan, CodegenConfig config) {
        this.plan = plan;
        this.ctx = new CodegenContext(config);
        this.ids = new PlanIds(plan);
        this.scans = ids.scans();
        ctx.enableReferences("references[%d]");
    }

    static GeneratedQuery generate(PlanNode plan, CodegenConfig config) {
        return new WholeStageCodegen(plan, config).generate();
    }

    private GeneratedQuery generate() {
        ctx.tag("inner");
        ctx.tag("do-while-false");
        Chain main = chain(plan);

        CodeWriter process = new CodeWriter(2);
        process.open("protected void processNext() throws IOException");
        ctx.addField("private boolean prepared;");
        ctx.addField("private boolean stop_main;");
        process.open("if (!prepared)");
        process.line("prepared = true;");
        prerequisites(process, main);
        process.close();
        loop(process, main, true, "stop_main", (w, access) -> w.line("append("
                + PipelineCodegen.outputRow(PipelineCodegen.columns(ctx, w, access, plan.schema())) + ");"));
        process.close();

        CodeWriter init = new CodeWriter(2);
        init.open("public void init(Object[] inputs)");
        for (int k = 0; k < scans.size(); k++) {
            PlanNode.Scan scan = scans.get(k);
            String prefix = "scan" + k;
            if (scan.layout() == PlanNode.Layout.COLUMNS) {
                ctx.addField("private int " + prefix + "_count;");
                init.line("ColumnBatch " + prefix + "_batch = (ColumnBatch) inputs[" + k + "];");
                init.line(prefix + "_count = " + prefix + "_batch.rowCount();");
                Schema schema = scan.schema();
                for (int i = 0; i < schema.size(); i++) {
                    String array = JavaTypes.arrayType(schema.type(i));
                    ctx.addField("private " + array + " " + prefix + "_values_" + i + ";");
                    ctx.addField("private boolean[] " + prefix + "_nulls_" + i + ";");
                    init.line(prefix + "_values_" + i + " = (" + array + ") " + prefix + "_batch.values(" + i + ");");
                    init.line(prefix + "_nulls_" + i + " = " + prefix + "_batch.nulls(" + i + ");");
                }
            } else {
                ctx.addField("private Object[][] " + prefix + "_rows;");
                init.line(prefix + "_rows = (Object[][]) inputs[" + k + "];");
            }
        }
        init.close();

        CodeWriter w = new CodeWriter(1);
        w.open("public Object generate(Object[] references)");
        w.line("return new " + ITERATOR + "(references);");
        w.close();
        for (String nested : ctx.nestedClasses()) {
            w.blank();
            w.append(new CodeWriter(1).raw(nested));
        }
        w.blank();
        w.open("final class " + ITERATOR + " extends BufferedRowIterator");
        w.line("private final Object[] references;");
        for (String field : ctx.fields()) {
            w.line(field);
        }
        w.blank();
        w.open("public " + ITERATOR + "(Object[] references)");
        w.line("this.references = references;");
        w.close();
        w.blank();
        w.append(init);
        w.blank();
        w.append(process);
        for (String method : ctx.methods()) {
            w.blank();
            w.append(new CodeWriter(2).raw(method));
        }
        w.close();

        GeneratedUnit unit = GeneratedUnit.ofClass(Generator.PACKAGE, CLASS_NAME, w.toString())
                .imports(Generator.IMPORTS)
                .extendedClass(GeneratedClass.class.getName())
                .references(ctx.references())
                .metadata(Generator.metadata(ctx.config(), ctx))
                .build();
        return new GeneratedQuery(List.of(unit), ApiPath.CLASS_BODY, new GeneratedQuery.Driver.WholeStage(), scans,
                Scenario.ordered(plan));
    }

    /** Decomposes the pipeline that ends at a node. */
    private static Chain chain(PlanNode top) {
        List<PlanNode> operators = new ArrayList<>();
        PlanNode node = top;
        while (true) {
            switch (node) {
                case PlanNode.Filter f -> {
                    operators.add(f);
                    node = f.input();
                }
                case PlanNode.Project p -> {
                    operators.add(p);
                    node = p.input();
                }
                case PlanNode.Limit l -> {
                    operators.add(l);
                    node = l.input();
                }
                case PlanNode.HashJoin j -> {
                    operators.add(j);
                    node = j.probe();
                }
                default -> {
                    Collections.reverse(operators);
                    return new Chain(node, operators);
                }
            }
        }
    }

    /** Writes the calls that must precede the first row of a pipeline: builds top-down, then the source. */
    private void prerequisites(CodeWriter w, Chain chain) {
        for (int i = chain.operators().size() - 1; i >= 0; i--) {
            if (chain.operators().get(i) instanceof PlanNode.HashJoin j) {
                w.line(collector(j) + "();");
            }
        }
        if (!(chain.source() instanceof PlanNode.Scan)) {
            w.line(collector(chain.source()) + "();");
        }
    }

    private int id(PlanNode node) {
        return ids.id(node);
    }

    /** Returns the name of the method that collects the input of a node, generating it on first use. */
    private String collector(PlanNode node) {
        String name = collectors.get(node);
        if (name != null) {
            return name;
        }
        int n = id(node);
        name = (node instanceof PlanNode.HashJoin ? "build_" : "collect_") + n;
        collectors.put(node, name);
        CodeWriter w = new CodeWriter(2);
        w.open("private void " + name + "()");
        String stop = "stop_" + n;
        switch (node) {
            case PlanNode.HashAggregate a -> {
                ctx.addField("private java.util.LinkedHashMap agg_map_" + n + ";");
                ctx.addField("private java.util.Iterator agg_iterator_" + n + ";");
                ctx.addField("private int agg_index_" + n + ";");
                AggregateCodegen aggregates = new AggregateCodegen(a, ctx);
                ctx.addNestedClass(aggregates.bufferClass("AggBuffer_" + n, "static final ", 1));
                w.line("agg_map_" + n + " = new java.util.LinkedHashMap();");
                Chain input = chain(a.input());
                w.line("boolean " + stop + " = false;");
                prerequisites(w, input);
                loop(w, input, false, stop, (body, access) -> {
                    String key = ctx.fresh("key");
                    String buffer = ctx.fresh("buffer");
                    body.line("RowKey " + key + " = " + rowKey(body, access, a.input().schema(), a.groupColumns()) + ";");
                    body.line("AggBuffer_" + n + " " + buffer + " = (AggBuffer_" + n + ") agg_map_" + n + ".get(" + key
                            + ");");
                    body.open("if (" + buffer + " == null)");
                    body.line(buffer + " = new AggBuffer_" + n + "();");
                    body.line("agg_map_" + n + ".put(" + key + ", " + buffer + ");");
                    body.close();
                    aggregates.update(body, buffer, access);
                });
                if (a.groupColumns().isEmpty()) {
                    w.open("if (agg_map_" + n + ".isEmpty())");
                    w.line("agg_map_" + n + ".put(new RowKey(new Object[0]), new AggBuffer_" + n + "());");
                    w.close();
                }
                w.line("agg_iterator_" + n + " = agg_map_" + n + ".entrySet().iterator();");
            }
            case PlanNode.HashJoin j -> {
                ctx.addField("private java.util.HashMap build_map_" + n + ";");
                w.line("build_map_" + n + " = new java.util.HashMap();");
                Chain input = chain(j.build());
                w.line("boolean " + stop + " = false;");
                prerequisites(w, input);
                loop(w, input, false, stop, (body, access) -> {
                    String key = ctx.fresh("key");
                    String list = ctx.fresh("list");
                    body.line("RowKey " + key + " = " + rowKey(body, access, j.build().schema(), j.buildKeys()) + ";");
                    body.open("if (!" + key + ".hasNull())");
                    body.line("java.util.List " + list + " = (java.util.List) build_map_" + n + ".get(" + key + ");");
                    body.open("if (" + list + " == null)");
                    body.line(list + " = new java.util.ArrayList();");
                    body.line("build_map_" + n + ".put(" + key + ", " + list + ");");
                    body.close();
                    body.line(list + ".add(" + PipelineCodegen.outputRow(PipelineCodegen.columns(ctx, body, access,
                            j.build().schema())) + ");");
                    body.close();
                });
            }
            case PlanNode.Sort s -> {
                collectInto(w, n, List.of(s.input()), stop);
                String comparator = "Comparator_" + n;
                CodeWriter c = new CodeWriter(1);
                c.open("static final class " + comparator + " implements java.util.Comparator");
                c.open("public int compare(Object a0, Object b0)");
                c.line("Object[] a = (Object[]) a0;");
                c.line("Object[] b = (Object[]) b0;");
                SortCodegen.body(c, ctx, s, "a", "b");
                c.close();
                c.close();
                ctx.addNestedClass(c.toString());
                w.line("java.util.Collections.sort(list_" + n + ", new " + comparator + "());");
            }
            case PlanNode.Union u -> collectInto(w, n, u.inputs(), stop);
            default -> throw new IllegalStateException("Not a collecting operator: " + node);
        }
        w.close();
        ctx.addMethod(w.toString());
        return name;
    }

    /** Collects the rows of the inputs, in order, into the list field of a node. */
    private void collectInto(CodeWriter w, int n, List<PlanNode> inputs, String stop) {
        ctx.addField("private java.util.ArrayList list_" + n + ";");
        ctx.addField("private int list_index_" + n + ";");
        w.line("list_" + n + " = new java.util.ArrayList();");
        for (int k = 0; k < inputs.size(); k++) {
            // A limit ends only the pipeline of its own input.
            PlanNode input = inputs.get(k);
            String inputStop = stop + "_" + k;
            w.line("boolean " + inputStop + " = false;");
            Chain chain = chain(input);
            prerequisites(w, chain);
            loop(w, chain, false, inputStop, (body, access) -> body.line("list_" + n + ".add("
                    + PipelineCodegen.outputRow(PipelineCodegen.columns(ctx, body, access, input.schema())) + ");"));
        }
    }

    /** Returns an expression that creates the {@code RowKey} of some columns. */
    private String rowKey(CodeWriter w, InputAccess access, Schema schema, List<Integer> columns) {
        List<String> values = new ArrayList<>();
        for (int c : columns) {
            values.add(PipelineCodegen.boxed(access.column(c, schema.type(c), w, ctx)));
        }
        return "new RowKey(new Object[] {" + String.join(", ", values) + "})";
    }

    /**
     * Writes the loop of a pipeline over its source.
     *
     * @param main whether this is the pipeline at the top, which returns after
     *        each source row with output and continues on the next call
     * @param stop the flag that a limit sets to end the pipeline
     */
    private void loop(CodeWriter w, Chain chain, boolean main, String stop, Sink sink) {
        PlanNode source = chain.source();
        String n = ctx.fresh("");
        String row = "row" + n;
        String label = JavaTypes.stringLiteral(ids.sourceLabel(source));
        InputAccess access;
        switch (source) {
            case PlanNode.Scan scan -> {
                String prefix = "scan" + indexOfScan(scan);
                boolean columnar = scan.layout() == PlanNode.Layout.COLUMNS;
                String size = columnar ? prefix + "_count" : prefix + "_rows.length";
                String index = main ? "main_index" : "index" + n;
                if (main) {
                    ctx.addField("private int main_index;");
                } else {
                    w.line("int " + index + " = 0;");
                }
                w.open("while (!" + stop + " && " + index + " < " + size + ")");
                w.line("int " + row + " = " + index + ";");
                w.line(index + "++;");
                w.line("position.set(" + label + ", " + row + ");");
                if (columnar) {
                    access = new InputAccess.ColumnBatch(prefix, row, scan.schema());
                } else {
                    String values = "values" + n;
                    w.line("Object[] " + values + " = " + prefix + "_rows[" + row + "];");
                    access = InputAccess.boxedRow(values, scan.schema());
                }
            }
            case PlanNode.HashAggregate a -> {
                int id = id(a);
                String entry = "entry" + n;
                String keyValues = "keyValues" + n;
                String buffer = "buffer" + n;
                w.open("while (!" + stop + " && agg_iterator_" + id + ".hasNext())");
                w.line("java.util.Map.Entry " + entry + " = (java.util.Map.Entry) agg_iterator_" + id + ".next();");
                w.line("int " + row + " = agg_index_" + id + "++;");
                w.line("position.set(" + label + ", " + row + ");");
                w.line("Object[] " + keyValues + " = ((RowKey) " + entry + ".getKey()).values();");
                w.line("AggBuffer_" + id + " " + buffer + " = (AggBuffer_" + id + ") " + entry + ".getValue();");
                List<ExprCode> results = new AggregateCodegen(a, ctx).results(w, buffer);
                Schema keySchema = new Schema(a.schema().fields().subList(0, a.groupColumns().size()));
                access = new InputAccess.Concat(InputAccess.boxedRow(keyValues, keySchema), a.groupColumns().size(),
                        InputAccess.projected(results));
            }
            default -> {
                int id = id(source);
                String values = "values" + n;
                w.open("while (!" + stop + " && list_index_" + id + " < list_" + id + ".size())");
                w.line("int " + row + " = list_index_" + id + "++;");
                w.line("position.set(" + label + ", " + row + ");");
                w.line("Object[] " + values + " = (Object[]) list_" + id + ".get(" + row + ");");
                access = InputAccess.boxedRow(values, source.schema());
            }
        }
        w.open("do");
        operators(w, chain.operators(), 0, access, stop, sink);
        w.close(" while (false);");
        if (main) {
            w.open("if (shouldStop())");
            w.line("return;");
            w.close();
        }
        w.close();
    }

    private int indexOfScan(PlanNode.Scan scan) {
        for (int k = 0; k < scans.size(); k++) {
            if (scans.get(k) == scan) {
                return k;
            }
        }
        throw new IllegalStateException("Unknown scan");
    }

    /** Returns the field that a limit sets when it has passed its last row. */
    private String limitReached(PlanNode.Limit l) {
        return "limit_reached_" + id(l);
    }

    /** Writes the operators of a pipeline from the given one on, then the sink. */
    private void operators(CodeWriter w, List<PlanNode> ops, int i, InputAccess access, String stop, Sink sink) {
        if (i == ops.size()) {
            sink.emit(w, access);
            return;
        }
        PlanNode op = ops.get(i);
        switch (op) {
            case PlanNode.Filter f -> {
                PipelineCodegen.filter(ctx, w, access, f.condition(), "continue;");
                operators(w, ops, i + 1, access, stop, sink);
            }
            case PlanNode.Project p -> {
                List<ExprCode> codes = PipelineCodegen.project(ctx, w, access,
                        p.columns().stream().map(PlanNode.NamedExpression::expression).toList(), true);
                operators(w, ops, i + 1, InputAccess.projected(codes), stop, sink);
            }
            case PlanNode.Limit l -> {
                String count = "limit_count_" + id(l);
                ctx.addField("private int " + count + ";");
                ctx.addField("private boolean " + limitReached(l) + ";");
                w.line(count + "++;");
                w.open("if (" + count + " >= " + l.count() + ")");
                w.line(stop + " = true;");
                w.line(limitReached(l) + " = true;");
                w.close();
                operators(w, ops, i + 1, access, stop, sink);
            }
            case PlanNode.HashJoin j -> {
                int id = id(j);
                String n = ctx.fresh("");
                String key = "joinKey" + n;
                String matches = "matches" + n;
                String count = "count" + n;
                String index = "j" + n;
                String buildRow = "buildRow" + n;
                boolean outer = j.type() == PlanNode.JoinType.LEFT_OUTER;
                w.line("RowKey " + key + " = " + rowKey(w, access, j.probe().schema(), j.probeKeys()) + ";");
                w.line("java.util.List " + matches + " = " + key + ".hasNull() ? null : (java.util.List) build_map_"
                        + id + ".get(" + key + ");");
                w.line("int " + count + " = " + matches + " == null ? 0 : " + matches + ".size();");
                String bound = outer ? "(" + count + " == 0 ? 1 : " + count + ")" : count;
                // Only a limit above the join ends the loop over the matches; one below has passed this row.
                StringBuilder reached = new StringBuilder();
                for (PlanNode above : ops.subList(i + 1, ops.size())) {
                    if (above instanceof PlanNode.Limit l) {
                        ctx.tag("loop-flag");
                        reached.append('!').append(limitReached(l)).append(" && ");
                    }
                }
                w.open("for (int " + index + " = 0; " + reached + index + " < " + bound + "; " + index + "++)");
                w.line("Object[] " + buildRow + " = " + (outer ? count + " == 0 ? null : " : "") + "(Object[]) "
                        + matches + ".get(" + index + ");");
                InputAccess joined = new InputAccess.Concat(access, j.probe().schema().size(),
                        new InputAccess.BoxedRow(buildRow, j.build().schema(), outer));
                operators(w, ops, i + 1, joined, stop, sink);
                w.close();
            }
            default -> throw new IllegalStateException("Not a streaming operator: " + op);
        }
    }
}
