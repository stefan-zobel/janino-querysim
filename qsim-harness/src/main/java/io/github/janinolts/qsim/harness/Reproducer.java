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

package io.github.janinolts.qsim.harness;

import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.Parameter;
import io.github.janinolts.qsim.backend.TypeNames;
import io.github.janinolts.qsim.backend.UnitKind;
import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.codegen.OperatorGraph;
import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.PlanIds;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Schema;
import io.github.janinolts.qsim.plan.Table;
import io.github.janinolts.qsim.runtime.BufferedRowIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the reproducer of a finding into a directory: the case
 * ({@code case.txt}), the plan ({@code plan.txt}), the data
 * ({@code data.txt}), the sources that Janino compiles
 * ({@code source-<n>.txt}) and those that javac compiles
 * ({@code <Class>.java} under {@code javac/}), the result of the
 * interpreter ({@code expected.txt}) and of each backend
 * ({@code actual-<backend>.txt}), and {@code Repro.java}: a program that
 * compiles the sources through the API path of the style with the Janino on
 * its class path, runs them on the data and compares the rows with those of
 * javac. It needs only {@code qsim-runtime} and the JARs of Janino.
 */
public final class Reproducer {

    private Reproducer() {
    }

    /**
     * Writes the reproducer.
     *
     * @param directory the directory, created if necessary
     * @param unit the (minimized) unit
     * @param outcome its outcome
     * @param difference the difference that the reproducer shows
     * @param origin where the unit comes from, e.g. the line of the
     *        regression corpus, or {@code null}
     * @param classPath the class path for {@code Repro.java}, e.g. the JARs of
     *        {@code qsim-runtime} and of the candidate
     */
    public static void write(Path directory, UnitCase unit, UnitOutcome outcome, Difference difference,
            String origin, String classPath) {
        GeneratedQuery query = unit.generate();
        try {
            Files.createDirectories(directory.resolve("javac"));
            TextFiles.write(directory.resolve("case.txt"), caseText(unit, outcome, difference, origin, classPath));
            TextFiles.write(directory.resolve("plan.txt"), plan(unit.plan(), 0));
            TextFiles.write(directory.resolve("data.txt"), data(unit));
            for (int i = 0; i < query.units().size(); i++) {
                GeneratedUnit u = query.units().get(i);
                TextFiles.write(directory.resolve("source-" + i + ".txt"), janinoSource(query, u));
                TextFiles.write(directory.resolve("javac").resolve(u.className() + ".java"),
                        UnitRenderer.compilationUnit(u));
            }
            for (UnitOutcome.BackendRun r : outcome.runs()) {
                String name = r.id().equals(DifferentialRunner.INTERPRETER) ? "expected.txt"
                        : "actual-" + r.id() + ".txt";
                TextFiles.write(directory.resolve(name), result(r.result()));
            }
            TextFiles.write(directory.resolve("Repro.java"), new ReproWriter(unit, query, outcome).write());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the class path of {@code qsim-runtime}: its JAR or class directory. */
    public static Path runtimeLocation() {
        try {
            return Path.of(BufferedRowIterator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String caseText(UnitCase unit, UnitOutcome outcome, Difference difference, String origin,
            String classPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unit:       ").append(unit.key()).append('\n');
        if (origin != null) {
            sb.append("Origin:     ").append(origin).append('\n');
        }
        sb.append("Difference: ").append(difference.pair().label()).append(' ').append(difference.kind())
                .append(" (").append(difference.assessment()).append(")\n");
        sb.append("Detail:     ").append(difference.detail().replace('\n', ' ')).append('\n');
        sb.append("Config:     ").append(unit.config()).append('\n');
        if (unit.mutation() != null) {
            sb.append("Mutation:   ").append(unit.mutation()).append('\n');
        }
        sb.append('\n');
        for (UnitOutcome.BackendRun r : outcome.runs()) {
            sb.append(String.format("%-7s %-24s %s%n", r.id(), r.version(), r.result()));
        }
        sb.append("\nAll differences of the unit:\n");
        for (Difference d : outcome.differences()) {
            sb.append("  ").append(d.pair().label()).append(' ').append(d.kind()).append(": ")
                    .append(d.detail().replace('\n', ' ')).append('\n');
        }
        sb.append("\nRun in this directory, with the JARs of the Janino version to test:\n");
        sb.append("  java -cp \"").append(classPath).append("\" Repro.java\n");
        return sb.toString();
    }

    /** Returns the plan, one operator per line, indented by depth. */
    static String plan(PlanNode node, int depth) {
        StringBuilder sb = new StringBuilder("  ".repeat(depth));
        switch (node) {
            case PlanNode.Scan s -> sb.append("Scan ").append(s.table()).append(' ').append(s.layout()).append(' ')
                    .append(fields(s.schema().fields()));
            case PlanNode.Filter f -> sb.append("Filter ").append(f.condition());
            case PlanNode.Project p -> {
                sb.append("Project");
                for (PlanNode.NamedExpression c : p.columns()) {
                    sb.append("\n").append("  ".repeat(depth + 2)).append(c.name()).append(" = ")
                            .append(c.expression());
                }
            }
            case PlanNode.HashAggregate a -> sb.append("HashAggregate groups ").append(a.groupColumns())
                    .append(' ').append(a.aggregates());
            case PlanNode.HashJoin j -> sb.append("HashJoin ").append(j.type()).append(' ').append(j.probeKeys())
                    .append(" = ").append(j.buildKeys());
            case PlanNode.Sort s -> sb.append("Sort ").append(s.keys());
            case PlanNode.Limit l -> sb.append("Limit ").append(l.count());
            case PlanNode.Union u -> sb.append("Union");
        }
        sb.append('\n');
        for (PlanNode child : node.children()) {
            sb.append(plan(child, depth + 1));
        }
        return sb.toString();
    }

    private static String fields(List<Schema.Field> fields) {
        List<String> result = new ArrayList<>();
        for (var f : fields) {
            result.add(f.name() + " " + f.type() + (f.nullable() ? "" : " NOT NULL"));
        }
        return "(" + String.join(", ", result) + ")";
    }

    /** Returns the tables that the plan scans, in the order of the scans. */
    private static String data(UnitCase unit) {
        StringBuilder sb = new StringBuilder();
        for (PlanNode.Scan scan : new PlanIds(unit.plan()).scans()) {
            Table table = unit.tables().get(scan.table());
            sb.append("Table ").append(scan.table()).append(' ').append(fields(table.schema().fields()))
                    .append('\n');
            for (Object[] row : table.rows()) {
                sb.append("  ").append(RunResult.canonical(row)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String result(RunResult r) {
        StringBuilder sb = new StringBuilder(r.toString()).append('\n');
        for (String row : r.rows()) {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    /** Returns the source text that Janino compiles through the API path of the query. */
    private static String janinoSource(GeneratedQuery query, GeneratedUnit u) {
        return switch (query.apiPath()) {
            case SIMPLE_COMPILER -> UnitRenderer.compilationUnit(u);
            case CLASS_BODY, COMMONS_COMPILER -> UnitRenderer.classBody(u);
            case EXPRESSION, SCRIPT -> u.body();
        };
    }

    /** Writes {@code Repro.java}. */
    private static final class ReproWriter {

        private final UnitCase unit;
        private final GeneratedQuery query;
        private final UnitOutcome outcome;
        private final StringBuilder sb = new StringBuilder();

        ReproWriter(UnitCase unit, GeneratedQuery query, UnitOutcome outcome) {
            this.unit = unit;
            this.query = query;
            this.outcome = outcome;
        }

        String write() {
            line("// Reproduces " + unit.key() + ".");
            line("// Compiles the files source-<n>.txt with the Janino on the class path, through the API path");
            line("// " + query.apiPath() + ", runs the code on the data below and compares the rows with those");
            line("// of javac. Run it in its directory: java -cp <qsim-runtime>;<janino>;<commons-compiler> Repro.java");
            line("");
            line("import io.github.janinolts.qsim.runtime.*;");
            line("import io.github.janinolts.qsim.runtime.linq.*;");
            line("import org.codehaus.commons.compiler.*;");
            line("import org.codehaus.janino.*;");
            line("");
            line("import java.math.BigDecimal;");
            line("import java.math.BigInteger;");
            line("import java.nio.file.Files;");
            line("import java.nio.file.Path;");
            line("import java.util.ArrayList;");
            line("import java.util.Collections;");
            line("import java.util.List;");
            line("");
            line("public class Repro {");
            line("");
            line("    static final String[] IMPORTS = " + strings(query.unit().imports()) + ";");
            List<String> names = new ArrayList<>();
            for (GeneratedUnit u : query.units()) {
                names.add(u.qualifiedName());
            }
            line("    static final String[] NAMES = " + strings(names) + ";");
            line("    static final boolean ORDERED = " + query.ordered() + ";");
            RunResult javac = outcome.result("JAVAC");
            RunResult reference = javac != null && javac.status().ran() ? javac
                    : outcome.result(DifferentialRunner.INTERPRETER);
            String origin = reference == javac ? "javac" : "the interpreter (javac did not run)";
            List<String> expected = new ArrayList<>();
            expected.add(reference.status() == RunResult.Status.THROWS ? "THROWS " + reference.exceptionClass()
                    + " at " + reference.source() + " row " + reference.rowIndex() : "OK");
            expected.addAll(reference.rows());
            line("    // The result of " + origin + ".");
            line("    static final String[] EXPECTED = " + strings(expected) + ";");
            line("");
            references();
            tables();
            line("    public static void main(String[] args) throws Exception {");
            line("        List<String> actual = new ArrayList<>();");
            line("        try {");
            switch (query.driver()) {
                case GeneratedQuery.Driver.WholeStage w -> wholeStage();
                case GeneratedQuery.Driver.PerRow p -> perRow(p);
                case GeneratedQuery.Driver.Enumerable e -> enumerable();
                case GeneratedQuery.Driver.OperatorClasses o -> operatorClasses(o.graph());
            }
            line("        } catch (CompileException e) {");
            line("            System.out.println(\"REJECTED \" + e.getMessage());");
            line("            return;");
            line("        }");
            line("        report(actual);");
            line("    }");
            line("");
            helpers();
            line("}");
            return sb.toString();
        }

        private void line(String s) {
            sb.append(s).append('\n');
        }

        private void references() {
            List<String> perUnit = new ArrayList<>();
            for (GeneratedUnit u : query.units()) {
                List<String> values = new ArrayList<>();
                for (Object r : u.references()) {
                    values.add(value(r));
                }
                perUnit.add("{" + String.join(", ", values) + "}");
            }
            line("    static final Object[][] REFERENCES = {" + String.join(", ", perUnit) + "};");
            line("");
        }

        private void tables() {
            List<PlanNode.Scan> scans = query.scans();
            for (int k = 0; k < scans.size(); k++) {
                Table t = unit.tables().get(scans.get(k).table());
                line("    /** The table " + scans.get(k).table() + " of scan " + k + ". */");
                line("    static Object[][] table" + k + "() {");
                line("        return new Object[][] {");
                for (Object[] row : t.rows()) {
                    List<String> values = new ArrayList<>();
                    for (Object v : row) {
                        values.add(value(v));
                    }
                    line("            {" + String.join(", ", values) + "},");
                }
                line("        };");
                line("    }");
                line("");
                List<String> classes = new ArrayList<>();
                for (int c = 0; c < t.schema().size(); c++) {
                    classes.add(elementClass(t.schema().type(c)));
                }
                line("    static final Class<?>[] TYPES" + k + " = {" + String.join(", ", classes) + "};");
                line("");
            }
        }

        private String source(int i) {
            return "Files.readString(Path.of(\"source-" + i + ".txt\"))";
        }

        private String fileName(int i) {
            return "\"" + query.units().get(i).sourceFileName() + "\"";
        }

        private String classes(List<String> names) {
            List<String> result = new ArrayList<>();
            for (String n : names) {
                result.add(TypeNames.sourceForm(n) + ".class");
            }
            return "new Class<?>[] {" + String.join(", ", result) + "}";
        }

        private String tablesArray() {
            List<String> result = new ArrayList<>();
            for (int k = 0; k < query.scans().size(); k++) {
                result.add("table" + k + "()");
            }
            return "new Object[][][] {" + String.join(", ", result) + "}";
        }

        private void wholeStage() {
            GeneratedUnit u = query.unit();
            line("            ClassBodyEvaluator cbe = new ClassBodyEvaluator();");
            line("            cbe.setParentClassLoader(Repro.class.getClassLoader());");
            line("            cbe.setClassName(NAMES[0]);");
            line("            cbe.setDefaultImports(IMPORTS);");
            line("            cbe.setExtendedClass(" + TypeNames.sourceForm(u.extendedClass()) + ".class);");
            line("            cbe.setImplementedInterfaces(" + classes(UnitRenderer.implementedInterfaces(u)) + ");");
            line("            cbe.cook(" + fileName(0) + ", " + source(0) + ");");
            line("            GeneratedClass g = (GeneratedClass) cbe.getClazz().getDeclaredConstructor()"
                    + ".newInstance();");
            line("            BufferedRowIterator it = (BufferedRowIterator) g.generate(REFERENCES[0]);");
            List<String> inputs = new ArrayList<>();
            for (int k = 0; k < query.scans().size(); k++) {
                inputs.add(query.scans().get(k).layout() == PlanNode.Layout.ROWS ? "table" + k + "()"
                        : "columns(table" + k + "(), TYPES" + k + ")");
            }
            line("            it.init(new Object[] {" + String.join(", ", inputs) + "});");
            line("            try {");
            line("                while (it.hasNext()) {");
            line("                    actual.add(canonical(it.next()));");
            line("                }");
            line("            } catch (Exception e) {");
            line("                threw(actual, e, it.position());");
            line("            }");
        }

        private void perRow(GeneratedQuery.Driver.PerRow p) {
            GeneratedUnit u = query.unit();
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (Parameter parameter : u.parameters()) {
                names.add(parameter.name());
                types.add(parameter.type());
            }
            boolean script = u.kind() == UnitKind.SCRIPT;
            String evaluator = script ? "ScriptEvaluator" : "ExpressionEvaluator";
            line("            " + evaluator + " ev = new " + evaluator + "();");
            line("            ev.setParentClassLoader(Repro.class.getClassLoader());");
            line("            ev.setClassName(NAMES[0]);");
            line("            ev.setDefaultImports(IMPORTS);");
            line("            ev.setParameters(" + strings(names) + ", " + classes(types) + ");");
            line("            ev." + (script ? "setReturnType" : "setExpressionType") + "("
                    + TypeNames.sourceForm(u.returnType()) + ".class);");
            line("            ev.cook(" + fileName(0) + ", " + source(0) + ");");
            line("            Object[][] rows = table0();");
            line("            for (int r = 0; r < rows.length; r++) {");
            if (p.singleValue()) {
                List<String> args = new ArrayList<>();
                for (int c : p.argumentColumns()) {
                    args.add("rows[r][" + c + "]");
                }
                line("                Object[] arguments = {" + String.join(", ", args) + "};");
            } else {
                line("                Object[] arguments = {rows[r]};");
            }
            line("                Object result;");
            line("                try {");
            line("                    result = ev.evaluate(arguments);");
            line("                } catch (java.lang.reflect.InvocationTargetException e) {");
            line("                    Position position = new Position();");
            line("                    position.set(" + quote(query.scans().get(0).table()) + ", r);");
            line("                    threw(actual, e.getCause(), position);");
            line("                    break;");
            line("                }");
            if (p.singleValue()) {
                line("                actual.add(canonical(new Object[] {result}));");
            } else {
                line("                if (result != null) {");
                line("                    actual.add(canonical((Object[]) result));");
                line("                }");
            }
            line("            }");
        }

        private void enumerable() {
            GeneratedUnit u = query.unit();
            line("            ICompilerFactory factory = CompilerFactoryFactory.getDefaultCompilerFactory("
                    + "Repro.class.getClassLoader());");
            line("            IClassBodyEvaluator cbe = factory.newClassBodyEvaluator();");
            line("            cbe.setClassName(NAMES[0]);");
            line("            cbe.setImplementedInterfaces(" + classes(UnitRenderer.implementedInterfaces(u)) + ");");
            line("            cbe.setParentClassLoader(Repro.class.getClassLoader());");
            line("            cbe.setDefaultImports(IMPORTS);");
            line("            cbe.cook(" + fileName(0) + ", new java.io.StringReader(" + source(0) + "));");
            line("            Bindable bindable = (Bindable) cbe.getClazz().getDeclaredConstructor().newInstance();");
            line("            DataContext root = new DataContext(" + tablesArray() + ", REFERENCES[0]);");
            line("            drain(actual, bindable.bind(root), root);");
        }

        private void operatorClasses(OperatorGraph.Node graph) {
            line("            Object[] f = new Object[NAMES.length];");
            line("            for (int i = 0; i < NAMES.length; i++) {");
            line("                SimpleCompiler sc = new SimpleCompiler();");
            line("                sc.setParentClassLoader(Repro.class.getClassLoader());");
            line("                sc.cook(NAMES[i].substring(NAMES[i].lastIndexOf('.') + 1) + \".java\", "
                    + "Files.readString(Path.of(\"source-\" + i + \".txt\")));");
            line("                f[i] = sc.getClassLoader().loadClass(NAMES[i]).getDeclaredConstructor(Object[].class)"
                    + ".newInstance((Object) REFERENCES[i]);");
            line("            }");
            line("            DataContext root = new DataContext(" + tablesArray() + ");");
            line("            Enumerable e = " + graph(graph) + ";");
            line("            drain(actual, e, root);");
        }

        private String graph(OperatorGraph.Node node) {
            return switch (node) {
                case OperatorGraph.Scan s -> "Enumerables.scan(root.position(), " + quote(s.table()) + ", root.table("
                        + s.scan() + "))";
                case OperatorGraph.Filter fl -> "Enumerables.where(" + graph(fl.input())
                        + ", a -> ((RowCondition) f[" + fl.condition() + "]).test((Object[]) a))";
                case OperatorGraph.Project p -> "Enumerables.select(" + graph(p.input())
                        + ", a -> ((RowFunction) f[" + p.function() + "]).apply((Object[]) a))";
                case OperatorGraph.Limit l -> "Enumerables.take(" + graph(l.input()) + ", " + l.count() + ")";
                case OperatorGraph.Union u -> {
                    List<String> inputs = new ArrayList<>();
                    for (OperatorGraph.Node in : u.inputs()) {
                        inputs.add(graph(in));
                    }
                    yield "Enumerables.union(root.position(), " + quote(u.label()) + ", new Enumerable[] {"
                            + String.join(", ", inputs) + "})";
                }
                case OperatorGraph.Sort s -> "Enumerables.orderBy(root.position(), " + quote(s.label()) + ", "
                        + graph(s.input()) + ", (a, b) -> ((RowComparator) f[" + s.comparator()
                        + "]).compare((Object[]) a, (Object[]) b))";
                case OperatorGraph.Join j -> "Enumerables.hashJoin(" + graph(j.probe()) + ", " + graph(j.build())
                        + ", a -> joinKey(((KeyFunction) f[" + j.probeKey() + "]).key((Object[]) a))"
                        + ", a -> joinKey(((KeyFunction) f[" + j.buildKey() + "]).key((Object[]) a))"
                        + ", (l, r) -> concat((Object[]) l, r == null ? new Object[" + j.buildWidth()
                        + "] : (Object[]) r), " + j.leftOuter() + ")";
                case OperatorGraph.Aggregate a -> {
                    String handler = "((AggregateHandler) f[" + a.handler() + "])";
                    yield "Enumerables.groupBy(root.position(), " + quote(a.label()) + ", " + graph(a.input())
                            + ", row -> ((KeyFunction) f[" + a.key() + "]).key((Object[]) row)"
                            + ", () -> " + handler + ".createAccumulator()"
                            + ", (acc, row) -> { " + handler + ".accumulate(acc, (Object[]) row); return acc; }"
                            + ", (k, acc) -> concat(((RowKey) k).values(), " + handler + ".getValue(acc))"
                            + ", " + a.global() + ", new RowKey(new Object[0]))";
                }
            };
        }

        private void helpers() {
            line("    static void drain(List<String> actual, Enumerable enumerable, DataContext root) {");
            line("        try {");
            line("            Enumerator e = enumerable.enumerator();");
            line("            while (e.moveNext()) {");
            line("                actual.add(canonical((Object[]) e.current()));");
            line("            }");
            line("        } catch (RuntimeException e) {");
            line("            threw(actual, e, root.position());");
            line("        }");
            line("    }");
            line("");
            line("    static void threw(List<String> actual, Throwable e, Position position) {");
            line("        actual.clear();");
            line("        actual.add(\"THROWS \" + e.getClass().getName() + \" at \" + position.source() + \" row \""
                    + " + position.row());");
            line("        e.printStackTrace(System.out);");
            line("    }");
            line("");
            line("    static RowKey joinKey(RowKey key) {");
            line("        return key.hasNull() ? null : key;");
            line("    }");
            line("");
            line("    static Object[] concat(Object[] a, Object[] b) {");
            line("        Object[] result = new Object[a.length + b.length];");
            line("        System.arraycopy(a, 0, result, 0, a.length);");
            line("        System.arraycopy(b, 0, result, a.length, b.length);");
            line("        return result;");
            line("    }");
            line("");
            line("    /** The table in columnar form: per column an array of values and an array of null flags. */");
            line("    static ColumnBatch columns(Object[][] rows, Class<?>[] types) {");
            line("        Object[] values = new Object[types.length];");
            line("        boolean[][] nulls = new boolean[types.length][rows.length];");
            line("        for (int c = 0; c < types.length; c++) {");
            line("            values[c] = java.lang.reflect.Array.newInstance(types[c], rows.length);");
            line("            for (int r = 0; r < rows.length; r++) {");
            line("                if (rows[r][c] == null) {");
            line("                    nulls[c][r] = true;");
            line("                } else {");
            line("                    java.lang.reflect.Array.set(values[c], r, rows[r][c]);");
            line("                }");
            line("            }");
            line("        }");
            line("        return new ColumnBatch(rows.length, values, nulls);");
            line("    }");
            line("");
            line("    /** The canonical form of a row, as qsim compares rows. */");
            line("    static String canonical(Object[] row) {");
            line("        StringBuilder sb = new StringBuilder();");
            line("        for (Object v : row) {");
            line("            if (sb.length() > 0) {");
            line("                sb.append('|');");
            line("            }");
            line("            if (v == null) {");
            line("                sb.append(\"null\");");
            line("            } else if (v instanceof Float f) {");
            line("                sb.append(\"F:\").append(Float.isNaN(f) ? \"NaN\" : Integer.toHexString("
                    + "Float.floatToRawIntBits(f)));");
            line("            } else if (v instanceof Double d) {");
            line("                sb.append(\"D:\").append(Double.isNaN(d) ? \"NaN\" : Long.toHexString("
                    + "Double.doubleToRawLongBits(d)));");
            line("            } else if (v instanceof BigDecimal b) {");
            line("                sb.append(\"M:\").append(b.unscaledValue()).append('e').append(b.scale());");
            line("            } else if (v instanceof String s) {");
            line("                sb.append(\"T\").append(s.length()).append(':').append(s);");
            line("            } else {");
            line("                sb.append(v.getClass().getSimpleName()).append(':').append(v);");
            line("            }");
            line("        }");
            line("        return sb.toString();");
            line("    }");
            line("");
            line("    static void report(List<String> actual) {");
            line("        if (actual.isEmpty() || !actual.get(0).startsWith(\"THROWS\")) {");
            line("            actual.add(0, \"OK\");");
            line("        }");
            line("        if (!ORDERED) {");
            line("            Collections.sort(actual.subList(1, actual.size()));");
            line("        }");
            line("        actual.forEach(System.out::println);");
            line("        System.out.println(java.util.Arrays.asList(EXPECTED).equals(actual) ? \"SAME AS JAVAC\""
                    + " : \"DIFFERENT FROM JAVAC: \" + java.util.Arrays.asList(EXPECTED));");
            line("    }");
        }

        private static String strings(List<String> values) {
            List<String> result = new ArrayList<>();
            for (String v : values) {
                result.add(quote(v));
            }
            return "new String[] {" + String.join(", ", result) + "}";
        }

        private static String elementClass(DataType type) {
            return switch (type) {
                case BOOLEAN -> "boolean.class";
                case BYTE -> "byte.class";
                case SHORT -> "short.class";
                case INT, DATE -> "int.class";
                case LONG -> "long.class";
                case FLOAT -> "float.class";
                case DOUBLE -> "double.class";
                case STRING -> "String.class";
                case DECIMAL -> "BigDecimal.class";
            };
        }

        /** Returns a Java expression for a boxed value of a table or a reference. */
        static String value(Object v) {
            return switch (v) {
                case null -> "null";
                case Boolean b -> b ? "Boolean.TRUE" : "Boolean.FALSE";
                case Byte b -> "Byte.valueOf((byte) " + b + ")";
                case Short s -> "Short.valueOf((short) " + s + ")";
                case Integer i -> i == Integer.MIN_VALUE ? "Integer.valueOf(Integer.MIN_VALUE)"
                        : "Integer.valueOf(" + i + ")";
                case Long l -> l == Long.MIN_VALUE ? "Long.valueOf(Long.MIN_VALUE)" : "Long.valueOf(" + l + "L)";
                case Float f -> "Float.valueOf(Float.intBitsToFloat(0x"
                        + Integer.toHexString(Float.floatToRawIntBits(f)) + "))";
                case Double d -> "Double.valueOf(Double.longBitsToDouble(0x"
                        + Long.toHexString(Double.doubleToRawLongBits(d)) + "L))";
                case String s -> quote(s);
                case BigDecimal b -> "new BigDecimal(new BigInteger(\"" + b.unscaledValue() + "\"), " + b.scale() + ")";
                default -> throw new IllegalArgumentException("Cannot write a " + v.getClass());
            };
        }

        /** Returns a string literal; characters outside of printable ASCII are escaped. */
        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c >= 0x20 && c < 0x7f) {
                    sb.append(c);
                } else if (c < 0x80) {
                    // An octal escape: a Unicode escape of a line terminator would end the literal.
                    sb.append(String.format("\\%03o", (int) c));
                } else {
                    sb.append(String.format("\\u%04x", (int) c));
                }
            }
            return sb.append('"').toString();
        }
    }

    /** Returns a file-system-safe name for the directory of the reproducer of a unit. */
    public static String directoryName(UnitKey key) {
        return key.toString().replaceAll("[^A-Za-z0-9_.-]+", "_");
    }
}
