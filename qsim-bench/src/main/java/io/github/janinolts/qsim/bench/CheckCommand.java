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

import io.github.janinolts.qsim.harness.CorrectnessReport;
import io.github.janinolts.qsim.harness.CorrectnessRun;
import io.github.janinolts.qsim.harness.Ledger;
import io.github.janinolts.qsim.harness.UnitCase;
import io.github.janinolts.qsim.harness.Workloads;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * The command {@code check}: runs a suite and reconciles it with the ledger
 * of the candidate.
 * <p>
 * {@code check --suite smoke|regression [--compliance] [--out <dir>]}
 * <p>
 * With {@code --compliance}, the candidate also runs in its compliance mode
 * ({@code JLTS-C}); that needs a candidate of the development line
 * {@code master}.
 * <p>
 * The exit code is 0 if every difference is explained and no entry of the
 * ledger is orphaned.
 */
final class CheckCommand {

    private CheckCommand() {
    }

    static int run(String[] argv) throws Exception {
        Args args = new Args(argv, Set.of("compliance"));
        String suite = args.get("suite", "smoke");
        Path out = Path.of(args.get("out", Path.of("target", "qsim-reports",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + suite).toString()));
        try (Backends backends = new Backends(args.flag("compliance"))) {
            Ledger ledger = backends.ledger();
            List<UnitCase> units = switch (suite) {
                case "smoke" -> Workloads.smoke();
                case "regression" -> Workloads.regression(ledger, RandomPlans.withDefaults());
                default -> throw new IllegalArgumentException("Unknown suite: " + suite);
            };
            CorrectnessRun run = CorrectnessRun.run(suite, backends.runner(), ledger, units);
            CorrectnessReport.write(run, out);
            System.out.println((run.passed() ? "PASSED: " : "FAILED: ") + units.size() + " units, "
                    + run.reconciliation().unexplained().size() + " unexplained differences, "
                    + run.reconciliation().orphans().size() + " orphaned ledger entries. Report: "
                    + out.resolve(CorrectnessReport.REPORT_FILE).toAbsolutePath());
            return run.passed() ? 0 : 1;
        }
    }
}
