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
import io.github.janinolts.qsim.plan.PlanIds;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Schema;
import io.github.janinolts.qsim.runtime.linq.Bindable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The style {@link Style#ENUMERABLE}, after Calcite: a class body with a
 * method {@code bind(DataContext)} that combines the operators of the runtime
 * library with chains of anonymous inner classes, static key classes with
 * {@code equals} and {@code hashCode} over boxed values, fully qualified
 * names; compiled through the {@code commons-compiler} interfaces.
 */
final class EnumerableCodegen {

    static final String CLASS_NAME = "Baz";

    private static final String LINQ = "io.github.janinolts.qsim.runtime.linq.";
    private static final String ENUMERABLE = LINQ + "Enumerable";

    private final CodegenContext ctx;
    private final PlanIds ids;
    private final CodeWriter bind = new CodeWriter(2);

    private EnumerableCodegen(PlanNode plan, CodegenConfig config) {
        this.ctx = new CodegenContext(config.withTryNesting(0));
        this.ids = new PlanIds(plan);
        // Calcite passes run-time objects through the data context.
        ctx.enableReferences("root.reference(%d)");
    }

    static GeneratedQuery generate(PlanNode plan, CodegenConfig config) {
        EnumerableCodegen g = new EnumerableCodegen(plan, config);
        g.ctx.tag("inner");
        g.ctx.tag("boxing");
        String result = g.node(plan);
        CodeWriter w = new CodeWriter(1);
        w.open("public " + ENUMERABLE + " bind(final " + LINQ + "DataContext root)");
        w.raw(g.bind.toString());
        w.line("return " + result + ";");
        w.close();
        for (String nested : g.ctx.nestedClasses()) {
            w.blank();
            w.raw(nested);
        }
        GeneratedUnit unit = GeneratedUnit.ofClass(Generator.PACKAGE, CLASS_NAME, w.toString())
                .imports(Generator.IMPORTS)
                .implementedInterfaces(Bindable.class.getName())
                .references(g.ctx.references())
                .metadata(Generator.metadata(config, g.ctx))
                .build();
        return new GeneratedQuery(List.of(unit), ApiPath.COMMONS_COMPILER, new GeneratedQuery.Driver.Enumerable(),
                g.ids.scans(), Scenario.ordered(plan));
    }

    /** Writes the statement of a node, after those of its inputs, and returns the name of its variable. */
    private String node(PlanNode node) {
        int id = ids.id(node);
        String var = "e" + id;
        String declaration = "final " + ENUMERABLE + " " + var + " = " + LINQ + "Enumerables.";
        switch (node) {
            case PlanNode.Scan s -> bind.line(declaration + "scan(root.position(), "
                    + JavaTypes.stringLiteral(s.table()) + ", root.table(" + Generator.scanIndex(ids, s) + "));");
            case PlanNode.Filter f -> {
                String input = node(f.input());
                call(declaration + "where(" + input + ",", List.of(anonymous("Predicate1",
                        "public boolean apply(Object a0)", w -> {
                            w.line("final Object[] current = (Object[]) a0;");
                            ExprCode c = new ExprCodegen(ctx, w, InputAccess.boxedRow("current", f.input().schema()))
                                    .gen(f.condition());
                            w.line("return " + (c.isNull().equals("false") ? "" : "!" + c.isNull() + " && ")
                                    + c.value() + ";");
                        })), ");");
            }
            case PlanNode.Project p -> {
                String input = node(p.input());
                call(declaration + "select(" + input + ",", List.of(anonymous("Function1",
                        "public Object apply(Object a0)", w -> {
                            w.line("final Object[] current = (Object[]) a0;");
                            List<ExprCode> codes = PipelineCodegen.project(ctx, w,
                                    InputAccess.boxedRow("current", p.input().schema()),
                                    p.columns().stream().map(PlanNode.NamedExpression::expression).toList(), false);
                            w.line("return " + PipelineCodegen.outputRow(codes) + ";");
                        })), ");");
            }
            case PlanNode.Limit l -> bind.line(declaration + "take(" + node(l.input()) + ", " + l.count() + ");");
            case PlanNode.Union u -> {
                List<String> inputs = new ArrayList<>();
                for (PlanNode input : u.inputs()) {
                    inputs.add(node(input));
                }
                bind.line(declaration + "union(root.position(), " + JavaTypes.stringLiteral(ids.sourceLabel(u))
                        + ", new " + ENUMERABLE + "[] {" + String.join(", ", inputs) + "});");
            }
            case PlanNode.Sort s -> {
                String input = node(s.input());
                call(declaration + "orderBy(root.position(), " + JavaTypes.stringLiteral(ids.sourceLabel(s)) + ", "
                        + input + ",", List.of(anonymousOf("java.util.Comparator",
                        "public int compare(Object a0, Object b0)", w -> {
                            w.line("final Object[] a = (Object[]) a0;");
                            w.line("final Object[] b = (Object[]) b0;");
                            SortCodegen.body(w, ctx, s, "a", "b");
                        })), ");");
            }
            case PlanNode.HashJoin j -> {
                String probe = node(j.probe());
                String build = node(j.build());
                String key = keyClass(id, j.probe().schema(), j.probeKeys());
                int width = j.probe().schema().size();
                int buildWidth = j.build().schema().size();
                call(declaration + "hashJoin(" + probe + ", " + build + ",", List.of(
                        keySelector(key, j.probe().schema(), j.probeKeys(), true),
                        keySelector(key, j.build().schema(), j.buildKeys(), true),
                        anonymous("Function2", "public Object apply(Object left0, Object right0)", w -> {
                            w.line("final Object[] left = (Object[]) left0;");
                            w.line("final Object[] right = right0 == null ? new Object[" + buildWidth
                                    + "] : (Object[]) right0;");
                            w.line("final Object[] out = new Object[" + (width + buildWidth) + "];");
                            w.line("System.arraycopy(left, 0, out, 0, " + width + ");");
                            w.line("System.arraycopy(right, 0, out, " + width + ", " + buildWidth + ");");
                            w.line("return out;");
                        })), ", " + (j.type() == PlanNode.JoinType.LEFT_OUTER) + ");");
            }
            case PlanNode.HashAggregate a -> {
                String input = node(a.input());
                String key = keyClass(id, a.input().schema(), a.groupColumns());
                String acc = "Acc_" + id;
                AggregateCodegen aggregates = new AggregateCodegen(a, ctx);
                ctx.addNestedClass(aggregates.bufferClass(acc, "static final ", 1));
                List<String> keyValues = new ArrayList<>();
                for (int i = 0; i < a.groupColumns().size(); i++) {
                    keyValues.add("key.f" + i);
                }
                call(declaration + "groupBy(root.position(), " + JavaTypes.stringLiteral(ids.sourceLabel(a)) + ", "
                        + input + ",", List.of(
                        keySelector(key, a.input().schema(), a.groupColumns(), false),
                        anonymous("Function0", "public Object apply()", w -> w.line("return new " + acc + "();")),
                        anonymous("Function2", "public Object apply(Object acc0, Object a0)", w -> {
                            w.line("final " + acc + " acc = (" + acc + ") acc0;");
                            w.line("final Object[] current = (Object[]) a0;");
                            aggregates.update(w, "acc", InputAccess.boxedRow("current", a.input().schema()));
                            w.line("return acc;");
                        }),
                        anonymous("Function2", "public Object apply(Object key0, Object acc0)", w -> {
                            w.line("final " + key + " key = (" + key + ") key0;");
                            w.line("final " + acc + " acc = (" + acc + ") acc0;");
                            List<String> values = new ArrayList<>(keyValues);
                            for (ExprCode r : aggregates.results(w, "acc")) {
                                values.add(PipelineCodegen.boxed(r));
                            }
                            w.line("return new Object[] {" + String.join(", ", values) + "};");
                        })), ", " + a.groupColumns().isEmpty() + ", new " + key + "(" + nulls(a.groupColumns().size())
                        + "));");
            }
        }
        return var;
    }

    private static String nulls(int n) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add("null");
        }
        return String.join(", ", result);
    }

    /** Writes a call whose arguments are anonymous classes, one after the other, separated by commas. */
    private void call(String opening, List<String> arguments, String closing) {
        bind.line(opening);
        for (int i = 0; i < arguments.size(); i++) {
            String text = arguments.get(i);
            String suffix = i + 1 < arguments.size() ? "," : closing;
            // The text ends with "}\n"; the separator goes after the closing brace.
            bind.raw(text.substring(0, text.length() - 1) + suffix + "\n");
        }
    }

    private String anonymous(String linqType, String method, Consumer<CodeWriter> body) {
        return anonymousOf(LINQ + linqType, method, body);
    }

    /** Returns the text of an anonymous class with one method, indented as an argument of a call in {@code bind}. */
    private String anonymousOf(String type, String method, Consumer<CodeWriter> body) {
        CodeWriter w = new CodeWriter(3);
        w.open("new " + type + "()");
        w.open(method);
        body.accept(w);
        w.close();
        w.close();
        return w.toString();
    }

    /**
     * Returns an anonymous key selector that creates the key object of some
     * columns; for a join, {@code null} if a value is {@code NULL}.
     */
    private String keySelector(String keyClass, Schema schema, List<Integer> columns, boolean join) {
        return anonymous("Function1", "public Object apply(Object a0)", w -> {
            w.line("final Object[] current = (Object[]) a0;");
            InputAccess access = InputAccess.boxedRow("current", schema);
            List<String> values = new ArrayList<>();
            for (int c : columns) {
                values.add(PipelineCodegen.boxed(access.column(c, schema.type(c), w, ctx)));
            }
            w.line("final " + keyClass + " key = new " + keyClass + "(" + String.join(", ", values) + ");");
            w.line("return " + (join ? "key.hasNull() ? null : key" : "key") + ";");
        });
    }

    /**
     * Declares a static key class: boxed fields in canonical form, compared
     * with {@code Keys}, and a hash code over them.
     */
    private String keyClass(int id, Schema schema, List<Integer> columns) {
        String name = "Key_" + id;
        CodeWriter w = new CodeWriter(1);
        w.open("static final class " + name);
        List<String> parameters = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String boxed = JavaTypes.boxedType(schema.type(columns.get(i)));
            w.line("final " + boxed + " f" + i + ";");
            parameters.add(boxed + " f" + i);
        }
        w.blank();
        w.open(name + "(" + String.join(", ", parameters) + ")");
        for (int i = 0; i < columns.size(); i++) {
            DataType type = schema.type(columns.get(i));
            boolean canonical = type == DataType.DOUBLE || type == DataType.FLOAT || type == DataType.DECIMAL;
            w.line("this.f" + i + " = " + (canonical ? "(" + JavaTypes.boxedType(type) + ") Keys.canonical(f" + i + ")"
                    : "f" + i) + ";");
        }
        w.close();
        w.blank();
        w.open("boolean hasNull()");
        List<String> nullChecks = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            nullChecks.add("f" + i + " == null");
        }
        w.line("return " + (nullChecks.isEmpty() ? "false" : String.join(" || ", nullChecks)) + ";");
        w.close();
        w.blank();
        w.open("public boolean equals(Object o)");
        w.open("if (!(o instanceof " + name + "))");
        w.line("return false;");
        w.close();
        w.line("final " + name + " that = (" + name + ") o;");
        List<String> equal = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            equal.add("Keys.equalObjects(f" + i + ", that.f" + i + ")");
        }
        w.line("return " + (equal.isEmpty() ? "true" : String.join(" && ", equal)) + ";");
        w.close();
        w.blank();
        w.open("public int hashCode()");
        w.line("int h = 17;");
        for (int i = 0; i < columns.size(); i++) {
            w.line("h = h * 31 + Keys.hash(f" + i + ");");
        }
        w.line("return h;");
        w.close();
        w.close();
        ctx.addNestedClass(w.toString());
        return name;
    }
}
