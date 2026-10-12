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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the reports of a {@link CorrectnessRun}: the raw data
 * {@code units.jsonl}, one line per unit and backend, and the summary
 * {@code report-correctness.md}.
 */
public final class CorrectnessReport {

    /** The name of the file with the raw data. */
    public static final String UNITS_FILE = "units.jsonl";

    /** The name of the summary. */
    public static final String REPORT_FILE = "report-correctness.md";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private CorrectnessReport() {
    }

    /** Writes both files into a directory, which is created if necessary. */
    public static void write(CorrectnessRun run, Path directory) {
        try {
            Files.createDirectories(directory);
            TextFiles.write(directory.resolve(UNITS_FILE), unitsJsonl(run));
            TextFiles.write(directory.resolve(REPORT_FILE), markdown(run));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the raw data: per unit and backend one JSON object, one per line. */
    public static String unitsJsonl(CorrectnessRun run) {
        StringBuilder sb = new StringBuilder();
        for (UnitOutcome o : run.outcomes()) {
            for (UnitOutcome.BackendRun r : o.runs()) {
                JsonObject line = new JsonObject();
                line.addProperty("suite", run.suite());
                line.addProperty("key", o.key().toString());
                line.addProperty("backend", r.id());
                line.addProperty("version", r.version());
                JsonArray tags = new JsonArray();
                o.featureTags().forEach(tags::add);
                line.add("featureTags", tags);
                line.addProperty("outcome", r.result().status().name());
                line.addProperty("rows", r.result().rows().size());
                line.addProperty("digest", r.result().digest());
                line.addProperty("exception", r.result().exceptionClass());
                line.addProperty("rowIndex", r.result().rowIndex());
                line.addProperty("line", r.result().line());
                line.addProperty("column", r.result().column());
                line.addProperty("message", r.result().message());
                line.addProperty("classFileBytes", r.classFileBytes());
                line.addProperty("classCount", r.classCount());
                line.addProperty("level", o.classFiles() == null ? null : o.classFiles().level().name());
                line.addProperty("differences", o.differences().size());
                line.addProperty("expected", o.expected().size());
                sb.append(GSON.toJson(line)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Returns the summary in Markdown. */
    public static String markdown(CorrectnessRun run) {
        Ledger.Reconciliation rec = run.reconciliation();
        StringBuilder sb = new StringBuilder();
        sb.append("# Correctness: ").append(run.suite()).append("\n\n");
        sb.append("Result: **").append(run.passed() ? "PASSED" : "FAILED").append("**. ")
                .append(run.outcomes().size()).append(" units, ").append(run.differences().size())
                .append(" differences: ").append(rec.explained().size()).append(" explained by the ledger, ")
                .append(rec.unexplained().size()).append(" unexplained; ").append(rec.orphans().size())
                .append(" orphaned ledger entries.\n\n");

        sb.append("| Backend | Version |\n| --- | --- |\n");
        DifferentialRunner.Backends b = run.backends();
        for (var backend : b.all()) {
            sb.append("| `").append(backend.id()).append("` | ").append(backend.version())
                    .append(backend == b.compliance() ? " (compliance mode)" : "").append(" |\n");
        }
        sb.append("\nLedger: line `").append(run.ledger().line()).append("`, ")
                .append(run.ledger().entries().size()).append(" entries.\n\n");

        sb.append("## Differences by pair and kind\n\n");
        Map<String, int[]> byPairKind = new TreeMap<>();
        for (Difference d : run.differences()) {
            int[] counts = byPairKind.computeIfAbsent(d.pair().label() + " | " + d.kind(), x -> new int[2]);
            counts[rec.explained().contains(d) ? 0 : 1]++;
        }
        if (byPairKind.isEmpty()) {
            sb.append("None.\n\n");
        } else {
            sb.append("| Pair | Kind | Explained | Unexplained |\n| --- | --- | ---: | ---: |\n");
            byPairKind.forEach((k, c) -> sb.append("| ").append(k).append(" | ").append(c[0]).append(" | ")
                    .append(c[1]).append(" |\n"));
            sb.append('\n');
        }

        sb.append("## Differences by feature tag\n\n");
        Map<String, Integer> byTag = new TreeMap<>();
        for (UnitOutcome o : run.outcomes()) {
            for (String tag : o.featureTags()) {
                byTag.merge(tag, o.differences().size(), Integer::sum);
            }
        }
        sb.append("| Tag | Differences |\n| --- | ---: |\n");
        byTag.forEach((tag, n) -> sb.append("| `").append(tag).append("` | ").append(n).append(" |\n"));
        sb.append('\n');

        sb.append("## Class files of baseline and candidate (normalized)\n\n");
        Map<String, Integer> byLevel = new TreeMap<>();
        for (UnitOutcome o : run.outcomes()) {
            byLevel.merge(o.classFiles() == null ? "none" : o.classFiles().level().name(), 1, Integer::sum);
        }
        sb.append("| Level | Units |\n| --- | ---: |\n");
        byLevel.forEach((level, n) -> sb.append("| ").append(level).append(" | ").append(n).append(" |\n"));
        sb.append('\n');

        sb.append("## Expected by the language profile\n\n");
        Map<String, Integer> byReason = new TreeMap<>();
        for (Difference d : run.expected()) {
            String reason = d.detail().substring(0, Math.max(0, d.detail().indexOf(':')));
            byReason.merge(reason + " | " + d.pair().label() + " | " + d.kind(), 1, Integer::sum);
        }
        if (byReason.isEmpty()) {
            sb.append("None.\n\n");
        } else {
            sb.append("| Feature or mutation | Pair | Kind | Differences |\n| --- | --- | --- | ---: |\n");
            byReason.forEach((k, n) -> sb.append("| ").append(k).append(" | ").append(n).append(" |\n"));
            sb.append('\n');
        }

        sb.append("## Unexplained differences\n\n");
        appendDifferences(sb, rec.unexplained(), run, rec);
        sb.append("## Orphaned ledger entries\n\n");
        if (rec.orphans().isEmpty()) {
            sb.append("None.\n\n");
        } else {
            for (Ledger.Entry e : rec.orphans()) {
                sb.append("- ").append(e.markdown()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## Ledger entries that explain differences\n\n");
        if (rec.usedEntries().isEmpty()) {
            sb.append("None.\n\n");
        } else {
            rec.usedEntries().forEach((e, n) -> sb.append("- ").append(n)
                    .append(n == 1 ? " difference: " : " differences: ").append(e.markdown()).append('\n'));
            sb.append('\n');
        }
        if (!run.notes().isEmpty()) {
            sb.append("## Notes\n\n");
            for (String note : run.notes()) {
                sb.append("- ").append(note.replace('\n', ' ')).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## Explained differences\n\n");
        appendDifferences(sb, rec.explained(), null, rec);
        return sb.toString();
    }

    /**
     * Appends a list of differences, each with the ledger entry that explains
     * it, if any; with a run, also the reproducer of each, or that there is
     * none.
     */
    private static void appendDifferences(StringBuilder sb, List<Difference> differences, CorrectnessRun run,
            Ledger.Reconciliation rec) {
        if (differences.isEmpty()) {
            sb.append("None.\n\n");
            return;
        }
        for (Difference d : differences) {
            sb.append("- `").append(d.key()).append("` ").append(d.pair().label()).append(' ').append(d.kind())
                    .append(" (").append(d.assessment()).append("): ").append(d.detail().replace('\n', ' '));
            Ledger.Entry entry = rec.explanations().get(d);
            if (entry != null) {
                sb.append(" (ledger: ").append(entry.markdownReference()).append(", line ").append(entry.lineNumber())
                        .append(')');
            }
            if (run != null) {
                String repro = run.reproducers().get(d.key());
                sb.append(repro == null ? " Reproducer: none." : " Reproducer: `" + repro + "`.");
            }
            sb.append('\n');
        }
        sb.append('\n');
    }
}
