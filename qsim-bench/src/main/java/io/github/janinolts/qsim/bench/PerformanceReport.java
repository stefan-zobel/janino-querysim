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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The evaluation of a measurement run and its report
 * {@code report-performance.md}: one section per measurement, each with the
 * ratio B/A, its interval, the classification, both controls and the minimum
 * detectable difference per point and mode, and what the measurement adds,
 * e.g. the phase shares of P3.
 *
 * @param data the raw data
 * @param results the results per point and mode
 * @param options the parameters of the classification
 * @param allocationLimit the limit of the allocation B/A in xint
 * @param ledger the ledger of the line of the candidate
 * @param allocation the reconciliation of the allocation violations
 * @param classFileFindings the class file levels of P8 that the ledger does
 *        not explain
 */
record PerformanceReport(AggregateCommand.Data data, List<Aggregator.PointResult> results,
        Aggregator.Options options, double allocationLimit, Ledger ledger, Ledger.Reconciliation allocation,
        List<String> classFileFindings) {

    static final String FILE = "report-performance.md";

    /** The titles of the measurements. */
    static final Map<String, String> TITLES = Map.of(
            "P1", "compile time per scenario",
            "P2", "scaling",
            "P3", "phases",
            "P4", "cold start",
            "P5", "concurrency",
            "P6", "class resolution",
            "P7", "debugging information",
            "P8", "class files",
            "P9", "runtime of the generated code",
            "P10", "target version");

    /** What an operation is, per measurement. */
    static final Map<String, String> OPERATIONS = Map.of(
            "P1", "a new compiler instance, compiling, loading and initializing the classes",
            "P2", "as in P1, at each level of a series",
            "P3", "one phase: parsing or compiling the compilation units, loading the class files with verification, "
                    + "or the first instance of freshly loaded classes",
            "P4", "a fresh JVM, from the start of main to the first instance, including the loading of Janino; "
                    + "the four arms alternate, one JVM each",
            "P5", "as in P1 in each of the threads at once, each with compiler instances of its own; the time is the "
                    + "wall time of all threads, the allocation that of all threads",
            "P6", "as in P1, for a class that refers to many runtime classes or calls overloaded methods",
            "P7", "as in P1, with the given debugging information",
            "P9", "running the generated code over the data; J is the code of javac",
            "P10", "loading the class files of the baseline with verification: A as shipped (version 50), "
                    + "B, B' and B+ with target version 8 (version 52)");

    /** Tells whether the run passes: allocation and class files as expected. Time ratios never fail it. */
    boolean passed() {
        return allocation.passed() && classFileFindings.isEmpty();
    }

    /** Returns one line per classification, for the console. */
    String summary() {
        Map<String, Integer> counts = new TreeMap<>();
        results.forEach(r -> counts.merge(r.classification().name(), 1, Integer::sum));
        return "Points: " + results.size() + " " + counts + "; allocation: "
                + (allocation.passed() ? "ok" : "FAILED") + "; class files: "
                + (classFileFindings.isEmpty() ? "ok" : "FAILED");
    }

    String markdown() {
        StringBuilder sb = new StringBuilder("# Performance\n\n");
        sb.append("Result: **").append(passed() ? "PASSED" : "FAILED").append("**. ").append(summary())
                .append(".\n\n");
        Map<String, String> versions = new TreeMap<>();
        for (RoundRecord r : data.rounds()) {
            versions.putIfAbsent("baseline", r.baselineVersion());
            versions.putIfAbsent("candidate", r.candidateVersion());
        }
        for (Environment e : data.environments()) {
            versions.putIfAbsent("baseline", e.baselineVersion());
            versions.putIfAbsent("candidate", e.candidateVersion());
        }
        sb.append("Baseline ").append(versions.getOrDefault("baseline", "?")).append(", candidate ")
                .append(versions.getOrDefault("candidate", "?")).append("; ")
                .append(data.environments().size()).append(" replicas; equivalence margin ")
                .append(pct(options.delta())).append(", q = ").append(options.q())
                .append(". B/A below 1 means the candidate is faster. Each table gives the 95 % interval of B/A, the "
                        + "classification, the negative control B'/B, the positive control B+/B with its interval and "
                        + "the minimum detectable difference (MDD, the half width of the interval).\n\n");

        Set<String> measured = new LinkedHashSet<>();
        for (String m : Measurements.ALL) {
            if (results.stream().anyMatch(r -> r.measurement().equals(m))
                    || data.points().stream().anyMatch(p -> p.measurement().equals(m))
                    || m.equals("P8") && !data.classFiles().isEmpty()) {
                measured.add(m);
            }
        }
        for (String m : measured) {
            sb.append("## ").append(m).append(": ").append(TITLES.get(m)).append("\n\n");
            if (m.equals("P8")) {
                appendClassFiles(sb);
                continue;
            }
            if (OPERATIONS.containsKey(m)) {
                sb.append("Operation: ").append(OPERATIONS.get(m)).append(".\n\n");
            }
            List<Aggregator.PointResult> rs = inPointOrder(m);
            appendResults(sb, m, rs);
            switch (m) {
                case "P2" -> appendScaling(sb, rs);
                case "P3" -> appendPhases(sb, rs);
                case "P5" -> appendThreads(sb, rs);
                case "P7" -> appendDebugClassFiles(sb);
                default -> {
                }
            }
            appendNotMeasured(sb, m);
        }

        sb.append("## Allocation in xint\n\n");
        sb.append("Limit: B/A at most ").append(allocationLimit).append(" per point, unless the ledger (line `")
                .append(ledger.line()).append("`) has an `ALLOC` entry. Not for ")
                .append(String.join(" and ", Measurements.ALL.stream()
                        .filter(Aggregator.WITHOUT_ALLOCATION_LIMIT::contains).toList()))
                .append(".\n\n");
        appendReconciliation(sb, allocation);

        sb.append("## Per fork\n\n");
        sb.append("| Measurement | Point | Mode | Replica | Fork | Rounds | A [ms] | B [ms] | B/A | p10 | p90 | A [MB]"
                + " | B [MB] |\n");
        sb.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Aggregator.PointResult r : results) {
            for (Aggregator.ForkSummary f : r.forks()) {
                sb.append("| ").append(r.measurement()).append(" | `").append(r.point()).append("` | ")
                        .append(r.mode()).append(" | ").append(f.replica()).append(" | ").append(f.fork())
                        .append(" | ").append(f.rounds()).append(" | ")
                        .append(ms(f.secondsA())).append(" | ")
                        .append(ms(f.secondsB())).append(" | ").append(num(f.median())).append(" | ")
                        .append(num(f.p10())).append(" | ").append(num(f.p90())).append(" | ")
                        .append(String.format(Locale.ROOT, "%.2f", f.bytesA() / 1e6)).append(" | ")
                        .append(String.format(Locale.ROOT, "%.2f", f.bytesB() / 1e6)).append(" |\n");
            }
        }
        sb.append('\n');

        sb.append("## Environment\n\n");
        for (Environment e : data.environments()) {
            sb.append("- `").append(e.replica()).append("`: ").append(e.jdkVendor()).append(' ').append(e.jdkVersion())
                    .append(", ").append(e.os()).append(e.runnerImage().isEmpty() ? "" : ", image " + e.runnerImage())
                    .append(", ").append(e.cpuModel()).append(", ").append(e.cores()).append(" cores, JVM ")
                    .append(e.jvmOptions()).append(", commit ").append(e.commit()).append(", ").append(e.time());
            if (e.seconds() != null && !e.seconds().isEmpty()) {
                sb.append("; duration");
                String separator = " ";
                for (Map.Entry<String, Long> d : new TreeMap<>(e.seconds()).entrySet()) {
                    sb.append(separator).append(d.getKey()).append(' ').append(d.getValue()).append(" s");
                    separator = ", ";
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void appendResults(StringBuilder sb, String measurement, List<Aggregator.PointResult> rs) {
        if (rs.isEmpty()) {
            sb.append("No measured points.\n\n");
            return;
        }
        boolean reference = measurement.equals("P9");
        sb.append("| Point | Mode | A [ms] | B [ms] | B/A | 95 % interval | Class | B'/B | B+/B (95 %) | MDD | Alloc B/A");
        sb.append(reference ? " | J/A | Rows/s A | Rows/s B" : "");
        sb.append(" | Units | Notes |\n");
        sb.append("| --- | --- | ---: | ---: | ---: | --- | --- | ---: | ---: | ---: | ---: |");
        sb.append(reference ? " ---: | ---: | ---: |" : "");
        sb.append(" ---: | --- |\n");
        for (Aggregator.PointResult r : rs) {
            double[] ci = r.ratio().interval(0.95);
            double sa = secondsA(r);
            double sbb = secondsB(r);
            sb.append("| `").append(r.point()).append("` | ").append(r.mode()).append(" | ").append(ms(sa))
                    .append(" | ").append(ms(sbb)).append(" | ").append(num(r.ratio().ratio())).append(" | ")
                    .append(r.ratio().hasInterval() ? num(ci[0]) + " .. " + num(ci[1]) : "-")
                    .append(" | **").append(r.classification()).append("** | ").append(num(r.negative().ratio()))
                    .append(" | ").append(withInterval(r.positive())).append(" | ")
                    .append(r.ratio().hasInterval() ? pct(r.ratio().minimumDetectableDifference()) : "-")
                    .append(" | ").append(num(r.allocation().ratio()));
            if (reference) {
                double rows = Measurements.RUN_ROWS;
                sb.append(" | ").append(withInterval(r.reference())).append(" | ").append(rate(rows, sa))
                        .append(" | ").append(rate(rows, sbb));
            }
            sb.append(" | ").append(r.ratio().units()).append(r.unitsAreForks() ? " forks" : " replicas")
                    .append(" | ").append(String.join("; ", r.notes())).append(" |\n");
        }
        sb.append('\n');
    }

    private void appendScaling(StringBuilder sb, List<Aggregator.PointResult> rs) {
        List<Scaling.Curve> curves = Scaling.curves(rs, data.points());
        for (Scaling.Curve c : curves) {
            sb.append("### ").append(c.series()).append(", ").append(c.mode()).append("\n\n");
            sb.append("Exponent of a power law over the source length: time A ")
                    .append(Scaling.num2(c.exponent(Scaling.Row::secondsA))).append(", B ")
                    .append(Scaling.num2(c.exponent(Scaling.Row::secondsB))).append("; allocation A ")
                    .append(Scaling.num2(c.exponent(Scaling.Row::bytesA))).append(", B ")
                    .append(Scaling.num2(c.exponent(Scaling.Row::bytesB)))
                    .append(". Above 1, a version grows faster than the source text. Curve: `scaling/")
                    .append(c.name()).append(".svg`, points: `scaling/").append(c.name()).append(".csv`.\n\n");
            sb.append("| Level | Source chars | A [ms] | B [ms] | A [MB] | B [MB] | B/A | Class |\n");
            sb.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
            for (Scaling.Row r : c.rows()) {
                sb.append("| ").append(r.level()).append(" | ").append(r.chars()).append(" | ")
                        .append(ms(r.secondsA())).append(" | ").append(ms(r.secondsB())).append(" | ")
                        .append(String.format(Locale.ROOT, "%.2f", r.bytesA() / 1e6)).append(" | ")
                        .append(String.format(Locale.ROOT, "%.2f", r.bytesB() / 1e6)).append(" | ")
                        .append(num(r.result().ratio().ratio())).append(" | ").append(r.result().classification())
                        .append(" |\n");
            }
            sb.append('\n');
        }
        if (!data.limits().isEmpty()) {
            sb.append("### Limits of a method, without splitting\n\n");
            sb.append("Each level compiled once by each backend, as shipped. `LIMIT` is a limit of the JVM, e.g. "
                    + "\"code too large\"; methods over ").append(ClassFileMeasurement.HUGE_METHOD)
                    .append(" bytes are not compiled by the JIT of HotSpot.\n\n");
            sb.append("| Series | Level | Source chars | Backend | Version | Outcome | Largest method | > 8000 B | Bytes"
                    + " | Message |\n");
            sb.append("| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | --- |\n");
            for (ClassFileMeasurement.LimitRow r : data.limits()) {
                sb.append("| ").append(r.series()).append(" | ").append(r.level()).append(" | ")
                        .append(r.sourceChars()).append(" | ").append(r.backend()).append(" | ").append(r.version())
                        .append(" | ").append(r.outcome()).append(" | ").append(r.largestMethod()).append(" | ")
                        .append(r.hugeMethods()).append(" | ").append(r.bytes()).append(" | ")
                        .append(r.message() == null ? "" : r.message().replace("|", "\\|")).append(" |\n");
            }
            sb.append('\n');
        }
    }

    private void appendPhases(StringBuilder sb, List<Aggregator.PointResult> rs) {
        // unit point | mode -> phase -> result
        Map<String, Map<String, Aggregator.PointResult>> groups = new LinkedHashMap<>();
        for (Aggregator.PointResult r : rs) {
            int slash = r.point().lastIndexOf('/');
            groups.computeIfAbsent(r.point().substring(0, slash) + "|" + r.mode(), k -> new LinkedHashMap<>())
                    .put(r.point().substring(slash + 1), r);
        }
        if (groups.isEmpty()) {
            return;
        }
        sb.append("### Shares of the phases\n\n");
        sb.append("The share of a phase in the sum of the four phases, per version.\n\n");
        sb.append("| Unit | Mode | Phase | A [ms] | Share A | B [ms] | Share B | B/A | Class |\n");
        sb.append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (Map.Entry<String, Map<String, Aggregator.PointResult>> g : groups.entrySet()) {
            String[] key = g.getKey().split("\\|");
            double totalA = 0;
            double totalB = 0;
            for (Aggregator.PointResult r : g.getValue().values()) {
                totalA += nanIsZero(secondsA(r));
                totalB += nanIsZero(secondsB(r));
            }
            for (Measurements.Phase phase : Measurements.Phase.values()) {
                Aggregator.PointResult r = g.getValue().get(phase.label());
                if (r == null) {
                    continue;
                }
                sb.append("| `").append(key[0]).append("` | ").append(key[1]).append(" | ").append(phase.label())
                        .append(" | ").append(ms(secondsA(r))).append(" | ").append(pct(secondsA(r) / totalA))
                        .append(" | ").append(ms(secondsB(r))).append(" | ").append(pct(secondsB(r) / totalB))
                        .append(" | ").append(num(r.ratio().ratio())).append(" | ").append(r.classification())
                        .append(" |\n");
            }
        }
        sb.append('\n');
    }

    private void appendThreads(StringBuilder sb, List<Aggregator.PointResult> rs) {
        Map<String, List<Aggregator.PointResult>> groups = new LinkedHashMap<>();
        for (Aggregator.PointResult r : rs) {
            int slash = r.point().lastIndexOf("/threads=");
            if (slash >= 0) {
                groups.computeIfAbsent(r.point().substring(0, slash) + "|" + r.mode(), k -> new ArrayList<>()).add(r);
            }
        }
        if (groups.isEmpty()) {
            return;
        }
        sb.append("### Throughput\n\n");
        sb.append("Operations per second of all threads, and the scaling factor against one thread. The JVMs of P5 use "
                + "the parallel collector and are not bound to cores; on a GitHub runner, the runner agent shares "
                + "the cores.\n\n");
        sb.append("| Unit | Mode | Threads | A [ops/s] | B [ops/s] | Scaling A | Scaling B | B/A | Class |\n");
        sb.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (Map.Entry<String, List<Aggregator.PointResult>> g : groups.entrySet()) {
            String[] key = g.getKey().split("\\|");
            double oneA = Double.NaN;
            double oneB = Double.NaN;
            for (Aggregator.PointResult r : g.getValue()) {
                int threads = threads(r.point());
                double a = threads / secondsA(r);
                double b = threads / secondsB(r);
                if (threads == 1) {
                    oneA = a;
                    oneB = b;
                }
                sb.append("| `").append(key[0]).append("` | ").append(key[1]).append(" | ").append(threads)
                        .append(" | ").append(String.format(Locale.ROOT, "%.1f", a)).append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", b)).append(" | ").append(num2(a / oneA))
                        .append(" | ").append(num2(b / oneB)).append(" | ").append(num(r.ratio().ratio()))
                        .append(" | ").append(r.classification()).append(" |\n");
            }
        }
        sb.append('\n');
    }

    private void appendDebugClassFiles(StringBuilder sb) {
        if (data.debugClassFiles().isEmpty()) {
            return;
        }
        sb.append("### Class files per setting\n\n");
        sb.append("`none`: no debugging information; `lines`: line numbers only; `all`: source file, line numbers and "
                + "local variables.\n\n");
        sb.append("| Point | Setting | Backend | Version | Bytes | Largest method |\n");
        sb.append("| --- | --- | --- | --- | ---: | ---: |\n");
        for (ClassFileMeasurement.Row r : data.debugClassFiles()) {
            sb.append("| `").append(r.point()).append("` | ").append(r.config()).append(" | ").append(r.backend())
                    .append(" | ").append(r.version()).append(" | ").append(r.bytes()).append(" | ")
                    .append(r.largestMethod()).append(" |\n");
        }
        sb.append('\n');
    }

    private void appendNotMeasured(StringBuilder sb, String measurement) {
        Map<String, String> skipped = new LinkedHashMap<>();
        for (PointInfo p : data.points()) {
            if (p.measurement().equals(measurement) && !p.measured()) {
                skipped.putIfAbsent(p.point(), p.reason());
            }
        }
        if (skipped.isEmpty()) {
            return;
        }
        sb.append(measurement.equals("P9") ? "Not measured (P9 measures only units whose method bodies differ, level"
                + " L3):\n\n" : measurement.equals("P10") ? "Not measured (P10 measures only units whose class files"
                + " of the baseline load in both configurations):\n\n" : "Not measured:\n\n");
        skipped.forEach((point, reason) -> sb.append("- `").append(point).append("`: ").append(reason).append('\n'));
        sb.append('\n');
    }

    private void appendClassFiles(StringBuilder sb) {
        if (data.classFiles().isEmpty()) {
            sb.append("Not measured.\n\n");
            return;
        }
        sb.append("Expected: `L0` normalized and at most `L1` as shipped, unless the ledger has a `BYTES` entry.\n\n");
        sb.append("| Point | Config | Backend | Version | Bytes | Classes | Methods | Largest method | Max pool"
                + " | > 8000 B | Level |\n");
        sb.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (ClassFileMeasurement.Row r : data.classFiles()) {
            sb.append("| `").append(r.point()).append("` | ").append(r.config()).append(" | ").append(r.backend())
                    .append(" | ").append(r.version()).append(" | ").append(r.bytes()).append(" | ")
                    .append(r.classes()).append(" | ").append(r.methods()).append(" | ")
                    .append(r.largestMethod()).append(" | ").append(r.maxConstantPool()).append(" | ")
                    .append(r.hugeMethods()).append(" | ").append(r.level() == null ? "-" : r.level())
                    .append(" |\n");
        }
        sb.append('\n');
        if (classFileFindings.isEmpty()) {
            sb.append("All levels as expected or explained.\n\n");
        } else {
            sb.append("Unexplained levels:\n\n");
            classFileFindings.forEach(f -> sb.append("- ").append(f).append('\n'));
            sb.append('\n');
        }
    }

    private static void appendReconciliation(StringBuilder sb, Ledger.Reconciliation rec) {
        if (rec.explained().isEmpty() && rec.unexplained().isEmpty() && rec.orphans().isEmpty()) {
            sb.append("No violations.\n\n");
            return;
        }
        for (Difference d : rec.unexplained()) {
            sb.append("- unexplained: `").append(d.key()).append("` ").append(d.detail()).append('\n');
        }
        for (Difference d : rec.explained()) {
            sb.append("- explained: `").append(d.key()).append("` ").append(d.detail()).append("; ledger: ")
                    .append(rec.explanations().get(d).markdownReference()).append('\n');
        }
        for (Ledger.Entry e : rec.orphans()) {
            sb.append("- orphaned entry: ").append(e.markdown()).append('\n');
        }
        sb.append('\n');
    }

    /** Returns the results of a measurement in the order of its points in {@code points.jsonl}, then by mode. */
    private List<Aggregator.PointResult> inPointOrder(String measurement) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (PointInfo p : data.points()) {
            if (p.measurement().equals(measurement)) {
                order.putIfAbsent(p.point(), order.size());
            }
        }
        List<Aggregator.PointResult> rs = new ArrayList<>(results.stream()
                .filter(r -> r.measurement().equals(measurement)).toList());
        rs.sort(Comparator.<Aggregator.PointResult>comparingInt(
                r -> order.getOrDefault(r.point(), Integer.MAX_VALUE)).thenComparing(Aggregator.PointResult::mode));
        return rs;
    }

    /** Returns the time per operation of A: the mean of the fork medians. */
    static double secondsA(Aggregator.PointResult r) {
        return r.forks().stream().mapToDouble(Aggregator.ForkSummary::secondsA).average().orElse(Double.NaN);
    }

    /** Returns the time per operation of B: the mean of the fork medians. */
    static double secondsB(Aggregator.PointResult r) {
        return r.forks().stream().mapToDouble(Aggregator.ForkSummary::secondsB).average().orElse(Double.NaN);
    }

    private static int threads(String point) {
        return Integer.parseInt(point.substring(point.lastIndexOf('=') + 1));
    }

    private static double nanIsZero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    /** Returns an estimate with its 95 % interval, e.g. {@code 1.0292 (1.0139 .. 1.0421)}. */
    private static String withInterval(Aggregator.Estimate e) {
        if (!e.hasInterval()) {
            return num(e.ratio());
        }
        double[] ci = e.interval(0.95);
        return num(e.ratio()) + " (" + num(ci[0]) + " .. " + num(ci[1]) + ")";
    }

    private static String ms(double seconds) {
        return Double.isNaN(seconds) ? "-" : String.format(Locale.ROOT, "%.3f", seconds * 1e3);
    }

    private static String rate(double rows, double seconds) {
        return Double.isNaN(seconds) || seconds <= 0 ? "-" : String.format(Locale.ROOT, "%.0f", rows / seconds);
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.4f", v);
    }

    private static String num2(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "-" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pct(double fraction) {
        return Double.isNaN(fraction) ? "-" : String.format(Locale.ROOT, "%.1f %%", fraction * 100);
    }
}
