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

import io.github.janinolts.qsim.harness.Difference;
import io.github.janinolts.qsim.harness.Ledger;
import io.github.janinolts.qsim.harness.UnitKey;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The command {@code aggregate}: reads the raw data of any number of
 * replicas, computes the intervals, classifies, checks the allocation limit
 * and the class file levels against the ledger, and writes
 * {@code report-performance.md} and, for P2, {@code scaling/*.csv} and
 * {@code scaling/*.svg}.
 * <p>
 * {@code aggregate [--delta 0.02] [--q 0.05] [--allocation-limit 1.01]
 * [--out <dir>] <dir>...}
 * <p>
 * The exit code is 1 if the allocation in {@code xint} exceeds the limit at a
 * point without an {@code ALLOC} entry in the ledger, if such an entry is
 * orphaned, or if the class files of P8 are not at the expected level
 * ({@code L0} normalized, at most {@code L1} as shipped) without a
 * {@code BYTES} entry. Time ratios never fail the run.
 */
final class AggregateCommand {

    private static final Gson GSON = new Gson();

    private AggregateCommand() {
    }

    /**
     * The data of a run.
     *
     * @param rounds the rounds of all replicas
     * @param classFiles the rows of P8
     * @param environments the environments of the replicas
     * @param points the points as {@code bench} selected them, of all replicas
     * @param limits the search for the limits of a method in P2
     * @param debugClassFiles the class files of P7
     */
    record Data(List<RoundRecord> rounds, List<ClassFileMeasurement.Row> classFiles, List<Environment> environments,
            List<PointInfo> points, List<ClassFileMeasurement.LimitRow> limits,
            List<ClassFileMeasurement.Row> debugClassFiles) {

        /** The data of P1 and P8 only, as before the other measurements. */
        Data(List<RoundRecord> rounds, List<ClassFileMeasurement.Row> classFiles, List<Environment> environments) {
            this(rounds, classFiles, environments, List.of(), List.of(), List.of());
        }
    }

    static int run(String[] argv) throws IOException {
        Args args = new Args(argv, Set.of());
        if (args.positional().isEmpty()) {
            throw new IllegalArgumentException("aggregate needs at least one directory with raw data");
        }
        Data data = read(args.positional().stream().map(Path::of).toList());
        Aggregator.Options options = new Aggregator.Options(args.getDouble("delta", 0.02), args.getDouble("q", 0.05));
        double limit = args.getDouble("allocation-limit", 1.01);
        Path out = Path.of(args.get("out", args.positional().get(0)));

        PerformanceReport report = evaluate(data, options, limit);
        Files.createDirectories(out);
        Path file = out.resolve(PerformanceReport.FILE);
        Files.writeString(file, report.markdown(), StandardCharsets.UTF_8);
        List<Scaling.Curve> curves = Scaling.curves(report.results(), data.points());
        if (!curves.isEmpty()) {
            Path scaling = out.resolve("scaling");
            Files.createDirectories(scaling);
            for (Scaling.Curve c : curves) {
                Files.writeString(scaling.resolve(c.name() + ".csv"), Scaling.csv(c), StandardCharsets.UTF_8);
                Files.writeString(scaling.resolve(c.name() + ".svg"), Scaling.svg(c), StandardCharsets.UTF_8);
            }
        }
        System.out.println(report.summary());
        System.out.println("Report: " + file.toAbsolutePath());
        return report.passed() ? 0 : 1;
    }

    /** Evaluates the data of a run. */
    static PerformanceReport evaluate(Data data, Aggregator.Options options, double limit) {
        List<Aggregator.PointResult> results = Aggregator.aggregate(data.rounds(), options);
        String candidateVersion = data.rounds().isEmpty()
                ? data.environments().stream().map(Environment::candidateVersion).findFirst().orElse("")
                : data.rounds().get(0).candidateVersion();
        Ledger ledger = candidateVersion.isEmpty()
                ? Ledger.forLine(Ledger.lineOf(candidateVersion))
                : Ledger.forVersion(candidateVersion);

        Set<UnitKey> measured = new LinkedHashSet<>();
        for (Aggregator.PointResult r : results) {
            if (r.mode().equals(Mode.XINT.label())) {
                measured.add(UnitKey.parse(r.unitKey()));
            }
        }
        List<Difference> violations = new ArrayList<>();
        for (Aggregator.PointResult r : Aggregator.allocationViolations(results, limit)) {
            violations.add(new Difference(UnitKey.parse(r.unitKey()), Difference.Pair.J3112_JLTS,
                    Difference.Kind.ALLOC, Difference.Assessment.CHANGED_CODE,
                    String.format("%s: allocation B/A %.4f in xint", r.point(), r.allocation().ratio())));
        }
        Ledger.Reconciliation allocation = ledger.reconcileAllocation(measured, violations);

        List<String> classFileFindings = new ArrayList<>();
        for (ClassFileMeasurement.Row row : data.classFiles()) {
            if (!row.backend().equals("JLTS") || row.level() == null) {
                continue;
            }
            String expected = row.config().equals("normalized") ? "L0" : "L1";
            if (row.level().compareTo(expected) > 0) {
                Difference d = new Difference(UnitKey.parse(row.unitKey()), Difference.Pair.J3112_JLTS,
                        Difference.Kind.BYTES, Difference.Assessment.CHANGED_CODE, row.level());
                if (!ledger.explains(d, row.tags())) {
                    classFileFindings.add(row.point() + " " + row.config() + ": " + row.level() + " "
                            + String.join("; ", row.details()));
                }
            }
        }
        return new PerformanceReport(data, results, options, limit, ledger, allocation, classFileFindings);
    }

    /** Reads the raw data from directories, recursively. */
    static Data read(List<Path> directories) throws IOException {
        List<RoundRecord> rounds = new ArrayList<>();
        List<ClassFileMeasurement.Row> classFiles = new ArrayList<>();
        List<Environment> environments = new ArrayList<>();
        List<PointInfo> points = new ArrayList<>();
        List<ClassFileMeasurement.LimitRow> limits = new ArrayList<>();
        List<ClassFileMeasurement.Row> debugClassFiles = new ArrayList<>();
        for (Path dir : directories) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (name.startsWith("rounds-") && name.endsWith(".jsonl")) {
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) {
                            rounds.add(GSON.fromJson(line, RoundRecord.class));
                        }
                    }
                } else if (name.equals("classfiles.jsonl")) {
                    readLines(f, ClassFileMeasurement.Row.class, classFiles);
                } else if (name.equals("debug-classfiles.jsonl")) {
                    readLines(f, ClassFileMeasurement.Row.class, debugClassFiles);
                } else if (name.equals("limits.jsonl")) {
                    readLines(f, ClassFileMeasurement.LimitRow.class, limits);
                } else if (name.equals(PointInfo.FILE)) {
                    readLines(f, PointInfo.class, points);
                } else if (name.equals(Environment.FILE)) {
                    environments.add(GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Environment.class));
                }
            }
        }
        return new Data(rounds, classFiles, environments, points, limits, debugClassFiles);
    }

    private static <T> void readLines(Path file, Class<T> type, List<T> into) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                into.add(GSON.fromJson(line, type));
            }
        }
    }
}
