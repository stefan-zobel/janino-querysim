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

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.codegen.InputData;
import io.github.janinolts.qsim.codegen.OperatorGraph;
import io.github.janinolts.qsim.plan.Interpreter;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.RowException;
import io.github.janinolts.qsim.plan.Scenario;
import io.github.janinolts.qsim.plan.Table;
import io.github.janinolts.qsim.runtime.BufferedRowIterator;
import io.github.janinolts.qsim.runtime.Evaluator;
import io.github.janinolts.qsim.runtime.GeneratedClass;
import io.github.janinolts.qsim.runtime.linq.Bindable;
import io.github.janinolts.qsim.runtime.linq.DataContext;
import io.github.janinolts.qsim.runtime.linq.Enumerable;
import io.github.janinolts.qsim.runtime.linq.Enumerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs a query: with the interpreter, or as generated code compiled by a
 * backend.
 */
public final class QueryRunner {

    private QueryRunner() {
    }

    /** Runs a plan with the interpreter, with language features as javac compiles them. */
    public static RunResult interpret(PlanNode plan, boolean ansi, Map<String, Table> tables) {
        return interpret(plan, ansi, tables, LanguageFeature.Semantics.JAVAC);
    }

    /** Runs a plan with the interpreter, with language features as javac or as Janino compiles them. */
    public static RunResult interpret(PlanNode plan, boolean ansi, Map<String, Table> tables,
            LanguageFeature.Semantics semantics) {
        try {
            return RunResult.ok(new Interpreter(ansi, semantics).execute(plan, tables), Scenario.ordered(plan));
        } catch (RowException e) {
            return RunResult.threw(e.getCause(), e.source(), e.rowIndex());
        }
    }

    /**
     * Compiles generated code with a backend through the API path of its
     * style, and runs it.
     *
     * @param options the options; the API path is replaced by the one of the
     *        query
     */
    public static RunResult run(GeneratedQuery query, CompilerBackend backend, CompileOptions options,
            Map<String, Table> tables) {
        return execute(query, compile(query, backend, options), tables);
    }

    /** Compiles every unit of a query, each with a compiler instance of its own. */
    public static CompiledQuery compile(GeneratedQuery query, CompilerBackend backend, CompileOptions options) {
        List<CompileResult> results = new ArrayList<>();
        for (GeneratedUnit unit : query.units()) {
            results.add(backend.compile(unit, options.withApiPath(query.apiPath())));
        }
        return new CompiledQuery(results);
    }

    /** Runs compiled code, or reports why it did not compile. */
    public static RunResult execute(GeneratedQuery query, CompiledQuery compiled, Map<String, Table> tables) {
        List<Object[]> rows = new ArrayList<>();
        RunResult failure = drive(query, compiled, tables, rows::add);
        return failure != null ? failure : RunResult.ok(rows, query.ordered());
    }

    /**
     * Runs compiled code and folds every output row into a checksum, without
     * keeping the rows; for measuring the generated code.
     *
     * @throws IllegalStateException if the code did not compile or a row threw
     */
    public static long checksum(GeneratedQuery query, CompiledQuery compiled, Map<String, Table> tables) {
        long[] sum = {0};
        RunResult failure = drive(query, compiled, tables, row -> sum[0] = 31 * sum[0] + Arrays.hashCode(row));
        if (failure != null) {
            throw new IllegalStateException("The generated code did not run: " + failure);
        }
        return sum[0];
    }

    /** Runs compiled code and passes the output rows to the sink; returns why it failed, or {@code null}. */
    private static RunResult drive(GeneratedQuery query, CompiledQuery compiled, Map<String, Table> tables,
            Consumer<Object[]> sink) {
        CompileResult failure = compiled.failure();
        if (failure != null) {
            if (Limits.isLimit(failure)) {
                return RunResult.failed(RunResult.Status.LIMIT, failure.message());
            }
            return switch (failure.outcome()) {
                case REJECTED -> RunResult.rejected(failure.message(), failure.line(), failure.column());
                case INVALID_CLASS -> RunResult.failed(RunResult.Status.INVALID_CLASS, failure.message());
                case UNSUPPORTED -> RunResult.failed(RunResult.Status.UNSUPPORTED, failure.message());
                default -> RunResult.failed(RunResult.Status.INTERNAL_ERROR, failure.message());
            };
        }
        try {
            return switch (query.driver()) {
                case GeneratedQuery.Driver.WholeStage w -> wholeStage(query, compiled, tables, sink);
                case GeneratedQuery.Driver.PerRow p ->
                        perRow(query, p, compiled.results().get(0).evaluator(), tables, sink);
                case GeneratedQuery.Driver.Enumerable e -> enumerable(query, compiled, tables, sink);
                case GeneratedQuery.Driver.OperatorClasses o ->
                        operatorClasses(query, o.graph(), compiled, tables, sink);
            };
        } catch (LinkageError e) {
            return RunResult.failed(RunResult.Status.INVALID_CLASS, e.toString());
        }
    }

    private static RunResult wholeStage(GeneratedQuery query, CompiledQuery compiled, Map<String, Table> tables,
            Consumer<Object[]> sink) {
        BufferedRowIterator iterator;
        try {
            GeneratedClass instance = (GeneratedClass) Instances.instantiate(query.unit(), compiled.mainClass(0));
            iterator = (BufferedRowIterator) instance.generate(query.unit().references());
            iterator.init(InputData.wholeStage(query.scans(), tables));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return RunResult.failed(RunResult.Status.INVALID_CLASS, "Cannot set up the generated class: " + e);
        }
        try {
            while (iterator.hasNext()) {
                sink.accept(iterator.next());
            }
        } catch (Exception e) {
            return RunResult.threw(e, iterator.position().source(), iterator.position().row());
        }
        return null;
    }

    private static RunResult perRow(GeneratedQuery query, GeneratedQuery.Driver.PerRow driver, Evaluator evaluator,
            Map<String, Table> tables, Consumer<Object[]> sink) {
        String source = query.scans().get(0).table();
        Table table = tables.get(source);
        for (int r = 0; r < table.size(); r++) {
            Object[] row = table.rows().get(r);
            Object[] arguments;
            if (driver.singleValue()) {
                arguments = new Object[driver.argumentColumns().size()];
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = row[driver.argumentColumns().get(i)];
                }
            } else {
                arguments = new Object[] {row};
            }
            Object result;
            try {
                result = evaluator.evaluate(arguments);
            } catch (Exception e) {
                return RunResult.threw(e, source, r);
            }
            if (driver.singleValue()) {
                sink.accept(new Object[] {result});
            } else if (result != null) {
                sink.accept((Object[]) result);
            }
        }
        return null;
    }

    private static RunResult enumerable(GeneratedQuery query, CompiledQuery compiled, Map<String, Table> tables,
            Consumer<Object[]> sink) {
        DataContext root = new DataContext(InputData.rows(query.scans(), tables), query.unit().references());
        Enumerable enumerable;
        try {
            Bindable bindable = (Bindable) Instances.instantiate(query.unit(), compiled.mainClass(0));
            enumerable = bindable.bind(root);
        } catch (ReflectiveOperationException e) {
            return RunResult.failed(RunResult.Status.INVALID_CLASS, "Cannot set up the generated class: " + e);
        }
        return drain(enumerable, root, sink);
    }

    private static RunResult operatorClasses(GeneratedQuery query, OperatorGraph.Node graph, CompiledQuery compiled,
            Map<String, Table> tables, Consumer<Object[]> sink) {
        Object[] functions = new Object[query.units().size()];
        try {
            for (int i = 0; i < functions.length; i++) {
                functions[i] = Instances.instantiate(query.units().get(i), compiled.mainClass(i));
            }
        } catch (ReflectiveOperationException e) {
            return RunResult.failed(RunResult.Status.INVALID_CLASS, "Cannot set up the generated classes: " + e);
        }
        DataContext root = new DataContext(InputData.rows(query.scans(), tables));
        return drain(OperatorGraph.build(graph, functions, root), root, sink);
    }

    private static RunResult drain(Enumerable enumerable, DataContext root, Consumer<Object[]> sink) {
        try {
            Enumerator e = enumerable.enumerator();
            while (e.moveNext()) {
                sink.accept((Object[]) e.current());
            }
        } catch (RuntimeException e) {
            return RunResult.threw(e, root.position().source(), root.position().row());
        }
        return null;
    }
}
