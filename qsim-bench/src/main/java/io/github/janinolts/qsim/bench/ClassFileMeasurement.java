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
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.ClassFileComparison;
import io.github.janinolts.qsim.harness.ClassFileInfo;
import io.github.janinolts.qsim.harness.CompiledQuery;
import io.github.janinolts.qsim.harness.QueryRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Measurement P8: the class files of the baseline and the candidate per unit,
 * in both configurations: size, number of methods, the largest method, the
 * constant pool, the methods over 8000 bytes (which HotSpot does not compile
 * with the JIT by default), and the level of the comparison. The same figures
 * serve P7, per setting of the debugging information, and the search for the
 * limits of a method in P2 ({@link LimitSearch}).
 */
public final class ClassFileMeasurement {

    /** The size from which HotSpot does not JIT-compile a method by default ({@code -XX:-DontCompileHugeMethods}). */
    public static final int HUGE_METHOD = 8000;

    /**
     * The class files of one backend for one unit in one configuration; one
     * line of {@code classfiles.jsonl}.
     *
     * @param point the measurement point
     * @param unitKey the key of the unit
     * @param config {@code normalized} or {@code as-shipped}
     * @param backend the backend
     * @param version its version
     * @param outcome the outcome of the compilation
     * @param bytes the total size of the class files
     * @param classes the number of classes
     * @param methods the number of methods
     * @param largestMethod the code length of the largest method
     * @param maxConstantPool the largest {@code constant_pool_count}
     * @param hugeMethods the methods over {@link #HUGE_METHOD} bytes
     * @param level the level of the comparison of baseline and candidate in this configuration
     * @param details what differs
     * @param featureTags the feature tags of the units, for the entries of the ledger that need them
     */
    public record Row(String point, String unitKey, String config, String backend, String version, String outcome,
            int bytes, int classes, int methods, int largestMethod, int maxConstantPool, int hugeMethods,
            String level, List<String> details, List<String> featureTags) {

        /** Returns the feature tags; none in raw data from before they were recorded. */
        public List<String> tags() {
            return featureTags == null ? List.of() : featureTags;
        }
    }

    private ClassFileMeasurement() {
    }

    /** Measures the class files of the points. */
    public static List<Row> measure(List<Measurements.Point> points, CompilerBackend baseline,
            CompilerBackend candidate, CompileOptions options) {
        List<Row> rows = new ArrayList<>();
        for (Measurements.Point point : points) {
            GeneratedQuery query = point.generate();
            for (String config : List.of("normalized", "as-shipped")) {
                CompileOptions o = config.equals("normalized") ? options.withTargetVersion(8)
                        : options.withTargetVersion(CompileOptions.DEFAULT_TARGET_VERSION);
                CompiledQuery a = QueryRunner.compile(query, baseline, o);
                CompiledQuery b = QueryRunner.compile(query, candidate, o);
                ClassFileComparison comparison = a.classFiles().isEmpty() || b.classFiles().isEmpty() ? null
                        : ClassFileComparison.compare(a.classFiles(), b.classFiles());
                for (Map.Entry<CompilerBackend, CompiledQuery> e : List.of(Map.entry(baseline, a),
                        Map.entry(candidate, b))) {
                    rows.add(row(point, query, config, e.getKey(), e.getValue(), comparison));
                }
            }
        }
        return rows;
    }

    /**
     * Measures the class files of P7 as shipped, with the debugging
     * information of each point; the configuration of a row is
     * {@code debug=<setting>}.
     */
    public static List<Row> measureDebug(List<Measurements.Point> points, CompilerBackend baseline,
            CompilerBackend candidate, CompileOptions options) {
        List<Row> rows = new ArrayList<>();
        for (Measurements.Point point : points) {
            if (!(point.work() instanceof Measurements.Work.Compile c)) {
                continue;
            }
            GeneratedQuery query = point.generate();
            CompileOptions o = c.debug().apply(options);
            CompiledQuery a = QueryRunner.compile(query, baseline, o);
            CompiledQuery b = QueryRunner.compile(query, candidate, o);
            String config = "debug=" + c.debug().label();
            rows.add(row(point, query, config, baseline, a, null));
            rows.add(row(point, query, config, candidate, b, null));
        }
        return rows;
    }

    /**
     * One backend at one level of a series without splitting methods; one line
     * of {@code limits.jsonl}.
     *
     * @param series the series, e.g. {@code columns}
     * @param level the level
     * @param sourceChars the length of the source text
     * @param backend the backend
     * @param version its version
     * @param outcome {@code OK}, {@code LIMIT} for a limit of the JVM, or the outcome of another failure
     * @param message the message of a failure, shortened, or {@code null}
     * @param bytes the total size of the class files
     * @param largestMethod the code length of the largest method
     * @param hugeMethods the methods over {@link #HUGE_METHOD} bytes
     */
    public record LimitRow(String series, int level, int sourceChars, String backend, String version,
            String outcome, String message, int bytes, int largestMethod, int hugeMethods) {
    }

    private static Row row(Measurements.Point point, GeneratedQuery query, String config, CompilerBackend backend,
            CompiledQuery r, ClassFileComparison comparison) {
        int bytes = 0;
        int methods = 0;
        int largest = 0;
        int pool = 0;
        int huge = 0;
        for (byte[] classFile : r.classFiles().values()) {
            ClassFileInfo info = ClassFileInfo.of(classFile);
            bytes += info.size();
            methods += info.methods().size();
            largest = Math.max(largest, info.largestMethod());
            pool = Math.max(pool, info.constantPoolCount());
            huge += (int) info.methods().stream().filter(m -> m.codeLength() > HUGE_METHOD).count();
        }
        return new Row(point.id(), point.unitKey(), config, backend.id(), backend.version(),
                r.failure() == null ? "OK" : r.failure().outcome().name(), bytes, r.classFiles().size(), methods,
                largest, pool, huge,
                comparison == null ? null : comparison.level().name(),
                comparison == null ? List.of() : comparison.details(),
                query.units().stream().flatMap(u -> u.metadata().featureTags().stream()).distinct().toList());
    }
}
