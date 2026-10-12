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

import io.github.janinolts.qsim.codegen.NegativePrograms;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.harness.Campaign;
import io.github.janinolts.qsim.harness.CorrectnessReport;
import io.github.janinolts.qsim.harness.CorrectnessRun;
import io.github.janinolts.qsim.harness.Difference;
import io.github.janinolts.qsim.harness.JaninoInstallation;
import io.github.janinolts.qsim.harness.Ledger;
import io.github.janinolts.qsim.harness.Minimizer;
import io.github.janinolts.qsim.harness.RandomUnit;
import io.github.janinolts.qsim.harness.Reproducer;
import io.github.janinolts.qsim.harness.TextFiles;
import io.github.janinolts.qsim.harness.UnitCase;
import io.github.janinolts.qsim.harness.UnitKey;
import io.github.janinolts.qsim.harness.UnitOutcome;
import io.github.janinolts.qsim.harness.Variant;
import io.github.janinolts.qsim.harness.Workloads;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The command {@code fuzz}: a campaign of random plans in child JVMs.
 * <p>
 * {@code fuzz [--seeds 1..2000] [--styles all] [--profiles STRICT]
 * [--variants one] [--negatives] [--try-jumps] [--compliance] [--batch 200] [--parallel <cores/2>]
 * [--threads 1] [--timeout-minutes 30] [--minimize-seconds 120]
 * [--max-reproducers 50] [--no-minimize] [--out <dir>]}
 * <p>
 * With {@code --try-jumps}, every seed, style and profile also runs in the
 * variant {@code TRY_JUMPS}, besides {@code BASE} and those of
 * {@code --variants}. With {@code --compliance}, the candidate also runs in
 * its compliance mode ({@code JLTS-C}). The seeds are split into batches; each batch runs in a child JVM with a
 * time limit, and up to {@code parallel} batches run at once. A batch whose
 * JVM exceeds the time limit or ends abnormally is split and run again,
 * down to single seeds, which are then reported as {@code TIMEOUT} or
 * {@code CRASH}. The outcomes of all batches are reconciled with the ledger
 * of the candidate. Per group of unexplained differences (pair, kind,
 * assessment, style and profile with its features), the first unit is
 * minimized and written as a reproducer under {@code repro/}; the
 * reproducers and the lines for the regression corpus are listed in the
 * report. The exit code is 0 if every difference is explained, no ledger
 * entry is orphaned, the variants agree and every batch finished.
 */
final class FuzzCommand {

    private FuzzCommand() {
    }

    /** Parses the settings of a campaign from the arguments. */
    static Campaign.Settings settings(Args args) {
        String[] range = args.get("seeds", "1..2000").split("\\.\\.");
        long first = Long.parseLong(range[0].strip());
        long last = range.length > 1 ? Long.parseLong(range[1].strip()) : first;
        List<Style> styles = new ArrayList<>();
        String s = args.get("styles", "all");
        for (String name : s.equals("all") ? Workloads.STYLES.stream().map(Enum::name).toList()
                : List.of(s.split(","))) {
            styles.add(Style.valueOf(name.strip()));
        }
        List<Profile> profiles = new ArrayList<>();
        String p = args.get("profiles", "STRICT");
        for (String name : p.equals("all") ? Stream.of(Profile.values()).map(Enum::name).toList()
                : List.of(p.split(","))) {
            profiles.add(Profile.valueOf(name.strip()));
        }
        Campaign.Variants variants = Campaign.Variants.valueOf(args.get("variants", "one").toUpperCase());
        return new Campaign.Settings(first, last, styles, profiles, variants, args.flag("negatives"),
                args.flag("try-jumps") ? List.of(Variant.TRY_JUMPS) : List.of());
    }

    static int run(String[] argv) throws Exception {
        Args args = new Args(argv, Set.of("negatives", "try-jumps", "compliance", "no-minimize"));
        Campaign.Settings settings = settings(args);
        if (args.flag("compliance")) {
            // Fails before the batches if the candidate has no compliance mode.
            new Backends(true).close();
        }
        int batch = args.getInt("batch", 200);
        int parallel = args.getInt("parallel", Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        Path out = Path.of(args.get("out", Path.of("target", "qsim-reports",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-fuzz").toString()));
        Files.createDirectories(out.resolve("batches"));
        long started = System.nanoTime();

        List<long[]> ranges = new ArrayList<>();
        for (long first = settings.firstSeed(); first <= settings.lastSeed(); first += batch) {
            ranges.add(new long[] {first, Math.min(settings.lastSeed(), first + batch - 1)});
        }
        System.out.println("Campaign: seeds " + settings.firstSeed() + ".." + settings.lastSeed() + ", "
                + ranges.size() + " batches, " + parallel + " at once; " + out.toAbsolutePath());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long[] r : ranges) {
                futures.add(pool.submit(() -> {
                    runBatch(args, settings, out, r[0], r[1], failures);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<CampaignFiles.Summary> summaries = new ArrayList<>();
        StringBuilder units = new StringBuilder();
        List<String> inconsistencies = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(out.resolve("batches"))) {
            for (Path dir : dirs.filter(d -> Files.exists(d.resolve(CampaignFiles.DONE)))
                    .sorted(Comparator.comparingLong(FuzzCommand::firstSeed)).toList()) {
                for (String line : Files.readAllLines(dir.resolve(CampaignFiles.SUMMARY), StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        summaries.add(CampaignFiles.parseSummary(line));
                    }
                }
                units.append(Files.readString(dir.resolve(CampaignFiles.UNITS), StandardCharsets.UTF_8));
                for (String line : Files.readAllLines(dir.resolve(CampaignFiles.INCONSISTENCIES))) {
                    if (!line.isBlank()) {
                        inconsistencies.add("Variants disagree: " + line);
                    }
                }
            }
        }

        try (Backends backends = new Backends(args.flag("compliance"))) {
            Ledger ledger = backends.ledger();
            List<UnitOutcome> outcomes = summaries.stream().map(CampaignFiles.Summary::outcome).toList();
            List<String> notes = new ArrayList<>();
            notes.add("Settings: " + settings + (args.flag("compliance") ? ", compliance mode" : "") + "; "
                    + summaries.size() + " units in "
                    + Duration.ofNanos(System.nanoTime() - started).toSeconds() + " s.");
            notes.addAll(failures);
            notes.addAll(inconsistencies);
            CorrectnessRun run = CorrectnessRun.of("campaign", backends.runner().backends(), ledger, outcomes)
                    .with(notes, Map.of());
            if (!args.flag("no-minimize")) {
                run = reproduce(run, summaries, backends, out, args);
            }
            TextFiles.write(out.resolve(CorrectnessReport.UNITS_FILE), units);
            TextFiles.write(out.resolve(CorrectnessReport.REPORT_FILE), CorrectnessReport.markdown(run));
            boolean passed = run.passed() && inconsistencies.isEmpty() && failures.isEmpty();
            System.out.println((passed ? "PASSED: " : "FAILED: ") + summaries.size() + " units, "
                    + run.differences().size() + " differences, " + run.reconciliation().unexplained().size()
                    + " unexplained, " + run.reconciliation().orphans().size() + " orphaned ledger entries, "
                    + run.expected().size() + " expected by the profiles, " + inconsistencies.size()
                    + " inconsistent variants, " + failures.size() + " failed batches. Report: "
                    + out.resolve(CorrectnessReport.REPORT_FILE).toAbsolutePath());
            return passed ? 0 : 1;
        }
    }

    private static long firstSeed(Path batchDir) {
        String name = batchDir.getFileName().toString();
        return Long.parseLong(name.substring(0, name.indexOf('-')));
    }

    /** Runs the seeds of a range in a child JVM; on a timeout or a crash, splits the range and runs the halves. */
    private static void runBatch(Args args, Campaign.Settings settings, Path out, long first, long last,
            List<String> failures) throws IOException, InterruptedException {
        Path dir = out.resolve("batches").resolve(first + "-" + last);
        Files.createDirectories(dir);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-D" + JaninoInstallation.BASELINE_DIR_PROPERTY + "="
                + JaninoInstallation.baseline().directory().toAbsolutePath());
        command.add("-D" + JaninoInstallation.CANDIDATE_DIR_PROPERTY + "="
                + JaninoInstallation.candidate().directory().toAbsolutePath());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CampaignBatchMain.class.getName());
        command.addAll(List.of("--seeds", first + ".." + last, "--styles", join(settings.styles()),
                "--profiles", join(settings.profiles()), "--variants", settings.variants().name(),
                "--threads", args.get("threads", "1"), "--out", dir.toString()));
        if (settings.requested().contains(Variant.TRY_JUMPS)) {
            command.add("--try-jumps");
        }
        if (settings.negatives()) {
            command.add("--negatives");
        }
        if (args.flag("compliance")) {
            command.add("--compliance");
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(dir.resolve(CampaignFiles.LOG).toFile()).start();
        String failure = null;
        if (!process.waitFor(args.getInt("timeout-minutes", 30), TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            failure = "TIMEOUT";
        } else if (process.exitValue() != 0 || !Files.exists(dir.resolve(CampaignFiles.DONE))) {
            failure = "CRASH (exit " + process.exitValue() + ")";
        }
        if (failure == null) {
            System.out.println("Seeds " + first + ".." + last + " done");
            return;
        }
        if (first == last) {
            failures.add(failure + ": seed " + first + "; see " + dir.resolve(CampaignFiles.LOG));
            return;
        }
        long mid = first + (last - first) / 2;
        System.out.println("Seeds " + first + ".." + last + ": " + failure + "; splitting");
        runBatch(args, settings, out, first, mid, failures);
        runBatch(args, settings, out, mid + 1, last, failures);
    }

    private static String join(List<? extends Enum<?>> values) {
        return String.join(",", values.stream().map(Enum::name).toList());
    }

    /**
     * Minimizes the first unit of each group of unexplained differences and
     * writes its reproducer; every difference of the group refers to it.
     */
    static CorrectnessRun reproduce(CorrectnessRun run, List<CampaignFiles.Summary> summaries, Backends backends,
            Path out, Args args) throws IOException {
        Map<UnitKey, RandomUnit> unitsByKey = new LinkedHashMap<>();
        for (CampaignFiles.Summary s : summaries) {
            unitsByKey.put(s.outcome().key(), s.unit());
        }
        Map<String, List<Difference>> groups = new LinkedHashMap<>();
        for (Difference d : run.reconciliation().unexplained()) {
            // The detail without numbers tells apart differences with other messages, e.g. other compile errors;
            // of a backend that does not behave as expected, only what it did counts.
            String text = d.detail().contains(", got ") ? d.detail().substring(d.detail().indexOf(", got "))
                    : d.detail();
            String detail = text.replaceAll("\\d+", "#").replaceAll("\\s+", " ");
            String group = d.pair().label() + " " + d.kind() + " " + d.assessment() + " " + d.key().style() + " "
                    + d.key().profile() + (d.key().workload().contains(":NEG:") ? " negative" : "") + ": "
                    + detail.substring(0, Math.min(160, detail.length()));
            groups.computeIfAbsent(group, g -> new ArrayList<>()).add(d);
        }
        int max = args.getInt("max-reproducers", 50);
        long millis = args.getInt("minimize-seconds", 120) * 1000L;
        String classPath = Backends.reproducerClassPath();
        RandomPlans generator = RandomPlans.withDefaults();
        Map<UnitKey, String> reproducers = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        StringBuilder corpus = new StringBuilder();
        int written = 0;
        for (Map.Entry<String, List<Difference>> g : groups.entrySet()) {
            if (written >= max) {
                notes.add("Not reproduced, more than " + max + " groups: " + g.getKey());
                continue;
            }
            Difference first = g.getValue().get(0);
            RandomUnit random = unitsByKey.get(first.key());
            UnitCase unit = random == null ? null : random.build(generator);
            if (unit == null) {
                notes.add("Cannot build " + first.key());
                continue;
            }
            Minimizer.Target target = Minimizer.Target.of(first);
            UnitCase minimal = unit;
            UnitOutcome outcome;
            String how;
            try {
                Minimizer.Result result = new Minimizer(backends.runner(), 600, millis).minimize(unit, target);
                minimal = result.unit();
                outcome = result.outcome();
                how = "minimized with " + result.checks() + " checks" + (result.exhausted() ? ", budget used up" : "");
            } catch (IllegalArgumentException e) {
                outcome = backends.runner().run(unit);
                how = "not minimized: the difference did not occur again in this JVM";
            }
            Difference shown = outcome.differences().stream()
                    .filter(d -> d.pair() == target.pair() && d.kind() == target.kind()).findFirst().orElse(first);
            String name = Reproducer.directoryName(first.key());
            Reproducer.write(out.resolve("repro").resolve(name), minimal, outcome, shown, random.toString(), classPath);
            written++;
            for (Difference d : g.getValue()) {
                reproducers.put(d.key(), "repro/" + name);
            }
            notes.add("Reproducer repro/" + name + " for " + g.getValue().size() + " differences " + g.getKey()
                    + " (" + how + ")");
            corpus.append(random).append("  # ").append(g.getKey()).append('\n');
            System.out.println("Reproducer " + name + ": " + how);
        }
        TextFiles.write(out.resolve("corpus-additions.txt"), corpus);
        return run.with(notes, reproducers);
    }

    /**
     * Returns the random unit that the arguments name: {@code --unit "<seed>
     * <style> <profile> <variant> [<mutation>]"}, or {@code --seed} and
     * {@code --style} with the optional {@code --profile} (default
     * {@code STRICT}), {@code --variant} (default {@code BASE}) and
     * {@code --mutation}.
     */
    static RandomUnit unit(Args args) {
        String text = args.get("unit", null);
        if (text != null) {
            return RandomUnit.parse(text);
        }
        String seed = args.get("seed", null);
        String style = args.get("style", null);
        if (seed == null || style == null) {
            throw new IllegalArgumentException("Name a unit with --unit, or with --seed and --style");
        }
        String mutation = args.get("mutation", null);
        return new RandomUnit(Long.parseLong(seed), Style.valueOf(style), Profile.valueOf(args.get("profile",
                "STRICT")), Variant.valueOf(args.get("variant", "BASE")),
                mutation == null ? null : NegativePrograms.Mutation.valueOf(mutation));
    }
}
