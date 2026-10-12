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

package io.github.janinolts.qsim.bench;

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.CompilerPhases;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.CompiledQuery;
import io.github.janinolts.qsim.harness.Instances;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.QueryRunner;
import io.github.janinolts.qsim.plan.Table;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * What the arms of a measurement point do: one operation, and its
 * preparation outside the measured time. {@link MeasurementLoop} measures
 * {@link #execute} only.
 */
abstract class Operation implements AutoCloseable {

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    final GeneratedQuery query;
    final MeasurementLoop.Arms arms;
    final CompileOptions options;

    Operation(GeneratedQuery query, MeasurementLoop.Arms arms, CompileOptions options) {
        this.query = query;
        this.arms = arms;
        this.options = options;
    }

    /** Returns the operation of a point. */
    static Operation of(Measurements.Point point, MeasurementLoop.Arms arms, CompileOptions options) {
        GeneratedQuery query = point.generate();
        return switch (point.work()) {
            case Measurements.Work.Compile c -> new Compile(query, arms, c.debug().apply(options));
            case Measurements.Work.OnePhase p -> new Phases(query, arms, options, p.phase());
            case Measurements.Work.Threads t -> new Threads(query, arms, options, t.threads());
            case Measurements.Work.Run r -> new Run(query, arms, options, point.tables());
            case Measurements.Work.Load l -> new Load(query, arms, options);
            case Measurements.Work.ColdStart c ->
                    throw new IllegalArgumentException("A cold start runs in a JVM of its own, not in a loop");
        };
    }

    /** Returns the arms that take part, in a fixed order; the loop shuffles them per round. */
    List<Arm> arms() {
        return Arm.CORE;
    }

    /** Returns the most operations per round, e.g. when the preparation holds much memory. */
    long maxOps() {
        return Long.MAX_VALUE;
    }

    /** Prepares operations of an arm outside the measured time. */
    Object prepare(Arm arm, long count) {
        return null;
    }

    /**
     * Executes operations of an arm: the measured time.
     *
     * @return a value that depends on the work, against dead code elimination
     */
    abstract int execute(Arm arm, long count, Object prepared);

    /** Returns the bytes that other threads allocated in the last {@link #execute}, or -1 if none worked. */
    long foreignAllocatedBytes() {
        return -1;
    }

    @Override
    public void close() {
    }

    /** Returns the backend of an arm; {@code B+} compiles with a copy of the candidate. */
    CompilerBackend backend(Arm arm) {
        return arms.of(arm);
    }

    /** Compile, load, initialize; with javac as a reference. */
    static final class Compile extends Operation {

        private final boolean reference;

        Compile(GeneratedQuery query, MeasurementLoop.Arms arms, CompileOptions options) {
            super(query, arms, options);
            // javac rejects some units that Janino compiles, e.g. methods over 64 KB; then there is no reference.
            reference = arms.javac() != null && QueryRunner.compile(query, arms.javac(), options).failure() == null;
        }

        @Override
        List<Arm> arms() {
            return reference ? Arm.WITH_REFERENCE : Arm.CORE;
        }

        @Override
        int execute(Arm arm, long count, Object prepared) {
            return MeasurementLoop.execute(query, backend(arm), options, count);
        }
    }

    /** One phase of the compilation, through {@link CompilerPhases}, on the compilation units of the query. */
    static final class Phases extends Operation {

        /** The limit of prepared operations: each instantiation needs freshly loaded classes. */
        static final long MAX_PREPARED = 2000;

        private final Measurements.Phase phase;
        private final Map<Arm, Map<String, byte[]>> classFiles = new EnumMap<>(Arm.class);

        Phases(GeneratedQuery query, MeasurementLoop.Arms arms, CompileOptions options, Measurements.Phase phase) {
            super(query, arms, options);
            this.phase = phase;
            for (Arm arm : Arm.CORE) {
                phases(arm);
            }
        }

        private CompilerPhases phases(Arm arm) {
            CompilerBackend backend = backend(arm);
            CompilerPhases phases = backend instanceof IsolatedBackend ib ? ib.phases() : null;
            if (phases == null) {
                throw new IllegalStateException(backend.id() + " cannot separate the phases of a compilation");
            }
            return phases;
        }

        private Object[] parse(Arm arm) {
            CompilerPhases phases = phases(arm);
            Object[] trees = new Object[query.units().size()];
            for (int u = 0; u < trees.length; u++) {
                try {
                    trees[u] = phases.parse(query.units().get(u), options);
                } catch (Exception e) {
                    throw new IllegalStateException(backend(arm).id() + " does not parse " + query.unit(), e);
                }
            }
            return trees;
        }

        private Map<String, byte[]> compile(Arm arm, Object[] trees) {
            CompilerPhases phases = phases(arm);
            Map<String, byte[]> all = new LinkedHashMap<>();
            for (Object tree : trees) {
                try {
                    all.putAll(phases.compile(tree, options));
                } catch (Exception e) {
                    throw new IllegalStateException(backend(arm).id() + " does not compile " + query.unit(), e);
                }
            }
            return all;
        }

        private Map<String, byte[]> classFiles(Arm arm) {
            return classFiles.computeIfAbsent(arm, x -> compile(x, parse(x)));
        }

        @Override
        long maxOps() {
            return phase == Measurements.Phase.COMPILE || phase == Measurements.Phase.INSTANTIATE ? MAX_PREPARED
                    : Long.MAX_VALUE;
        }

        @Override
        Object prepare(Arm arm, long count) {
            return switch (phase) {
                case PARSE, LOAD -> null;
                case COMPILE -> {
                    List<Object[]> trees = new ArrayList<>();
                    for (long i = 0; i < count; i++) {
                        trees.add(parse(arm));
                    }
                    yield trees;
                }
                case INSTANTIATE -> {
                    List<Class<?>[]> mainClasses = new ArrayList<>();
                    for (long i = 0; i < count; i++) {
                        mainClasses.add(mainClasses(ClassFileLoader.loadAll(options.parent(), classFiles(arm))));
                    }
                    yield mainClasses;
                }
            };
        }

        private Class<?>[] mainClasses(Class<?>[] loaded) {
            Map<String, Class<?>> byName = new LinkedHashMap<>();
            for (Class<?> c : loaded) {
                byName.put(c.getName(), c);
            }
            Class<?>[] result = new Class<?>[query.units().size()];
            for (int u = 0; u < result.length; u++) {
                result[u] = byName.get(query.units().get(u).qualifiedName());
                if (result[u] == null) {
                    throw new IllegalStateException("No class " + query.units().get(u).qualifiedName());
                }
            }
            return result;
        }

        @Override
        @SuppressWarnings("unchecked")
        int execute(Arm arm, long count, Object prepared) {
            int h = 0;
            switch (phase) {
                case PARSE -> {
                    for (long i = 0; i < count; i++) {
                        h += parse(arm).length;
                    }
                }
                case COMPILE -> {
                    for (Object[] trees : (List<Object[]>) prepared) {
                        h += compile(arm, trees).size();
                    }
                }
                case LOAD -> {
                    Map<String, byte[]> files = classFiles(arm);
                    for (long i = 0; i < count; i++) {
                        h += ClassFileLoader.loadAll(options.parent(), files).length;
                    }
                }
                case INSTANTIATE -> {
                    for (Class<?>[] mainClasses : (List<Class<?>[]>) prepared) {
                        for (int u = 0; u < mainClasses.length; u++) {
                            h += instantiate(query.units().get(u), mainClasses[u]).hashCode();
                        }
                    }
                }
            }
            return h;
        }
    }

    /** Compile, load, initialize in each of several threads. */
    static final class Threads extends Operation {

        private final int threads;
        private final ExecutorService pool;
        private long foreign;

        Threads(GeneratedQuery query, MeasurementLoop.Arms arms, CompileOptions options, int threads) {
            super(query, arms, options);
            this.threads = threads;
            this.pool = Executors.newFixedThreadPool(threads);
        }

        @Override
        int execute(Arm arm, long count, Object prepared) {
            CompilerBackend backend = backend(arm);
            List<Future<long[]>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    long alloc0 = THREADS.getCurrentThreadAllocatedBytes();
                    int h = MeasurementLoop.execute(query, backend, options, count);
                    return new long[] {h, THREADS.getCurrentThreadAllocatedBytes() - alloc0};
                }));
            }
            int h = 0;
            long allocated = 0;
            for (Future<long[]> f : futures) {
                try {
                    long[] r = f.get();
                    h += (int) r[0];
                    allocated += r[1];
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            foreign = allocated;
            return h;
        }

        @Override
        long foreignAllocatedBytes() {
            return foreign;
        }

        @Override
        public void close() {
            pool.shutdownNow();
        }
    }

    /** Run the code that each arm compiled over the data; with the code of javac as a reference. */
    static final class Run extends Operation {

        private final Map<String, Table> tables;
        private final Map<Arm, CompiledQuery> compiled = new EnumMap<>(Arm.class);

        Run(GeneratedQuery query, MeasurementLoop.Arms arms, CompileOptions options, Map<String, Table> tables) {
            super(query, arms, options);
            this.tables = tables;
            compiled.put(Arm.A, compile(arms.a()));
            compiled.put(Arm.B, compile(arms.b()));
            compiled.put(Arm.B_PRIME, compile(arms.bPrime()));
            compiled.put(Arm.B_PLUS, arms.bPlus() == arms.b() ? compiled.get(Arm.B)
                    : arms.bPlus() == arms.bPrime() ? compiled.get(Arm.B_PRIME) : compile(arms.bPlus()));
            if (arms.javac() != null) {
                // javac rejects some units that Janino compiles, e.g. methods over 64 KB; then there is no reference.
                CompiledQuery reference = QueryRunner.compile(query, arms.javac(), options);
                if (reference.failure() == null) {
                    compiled.put(Arm.J, reference);
                }
            }
        }

        private CompiledQuery compile(CompilerBackend backend) {
            CompiledQuery c = QueryRunner.compile(query, backend, options);
            CompileResult failure = c.failure();
            if (failure != null) {
                throw new IllegalStateException(backend.id() + " does not compile " + query.unit() + ": " + failure);
            }
            return c;
        }

        @Override
        List<Arm> arms() {
            return compiled.containsKey(Arm.J) ? Arm.WITH_REFERENCE : Arm.CORE;
        }

        /** Returns the code that an arm runs. */
        CompiledQuery compiled(Arm arm) {
            return compiled.get(arm);
        }

        @Override
        int execute(Arm arm, long count, Object prepared) {
            CompiledQuery c = compiled.get(arm);
            long h = 0;
            for (long i = 0; i < count; i++) {
                h += QueryRunner.checksum(query, c, tables);
            }
            return (int) (h ^ h >>> 32);
        }
    }

    /**
     * Load the class files of the baseline with verification: as shipped in
     * arm A, with target version 8 in the others; {@code B'} compiles them
     * once more.
     */
    static final class Load extends Operation {

        private final Map<Arm, Map<String, byte[]>> classFiles = new EnumMap<>(Arm.class);

        Load(GeneratedQuery query, MeasurementLoop.Arms arms, CompileOptions options) {
            super(query, arms, options);
            Function<CompileOptions, Map<String, byte[]>> compile = o -> {
                CompiledQuery c = QueryRunner.compile(query, arms.a(), o);
                if (c.failure() != null) {
                    throw new IllegalStateException(arms.a().id() + " does not compile " + query.unit() + ": "
                            + c.failure());
                }
                return c.classFiles();
            };
            classFiles.put(Arm.A, compile.apply(options.withTargetVersion(CompileOptions.DEFAULT_TARGET_VERSION)));
            classFiles.put(Arm.B, compile.apply(options.withTargetVersion(8)));
            classFiles.put(Arm.B_PRIME, compile.apply(options.withTargetVersion(8)));
            classFiles.put(Arm.B_PLUS, classFiles.get(Arm.B));
        }

        @Override
        int execute(Arm arm, long count, Object prepared) {
            Map<String, byte[]> files = classFiles.get(arm);
            int h = 0;
            for (long i = 0; i < count; i++) {
                h += ClassFileLoader.loadAll(options.parent(), files).length;
            }
            return h;
        }
    }

    private static Object instantiate(GeneratedUnit unit, Class<?> mainClass) {
        try {
            return Instances.instantiate(unit, mainClass);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + mainClass.getName(), e);
        }
    }
}
