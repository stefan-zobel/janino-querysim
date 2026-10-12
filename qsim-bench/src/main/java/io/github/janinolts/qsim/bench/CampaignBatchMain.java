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

import io.github.janinolts.qsim.harness.Campaign;
import io.github.janinolts.qsim.harness.CorrectnessReport;
import io.github.janinolts.qsim.harness.CorrectnessRun;
import io.github.janinolts.qsim.harness.Ledger;
import io.github.janinolts.qsim.harness.TextFiles;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The entry point of a child JVM of the command {@code fuzz}: runs the units
 * of a range of seeds and writes the files of {@link CampaignFiles} into its
 * directory.
 * <p>
 * Arguments: {@code --seeds 1..200 --styles all --profiles STRICT
 * --variants one [--negatives] [--try-jumps] [--compliance] [--threads 1] --out <dir>}.
 */
public final class CampaignBatchMain {

    private CampaignBatchMain() {
    }

    public static void main(String[] argv) throws Exception {
        Args args = new Args(argv, Set.of("negatives", "try-jumps", "compliance"));
        Campaign.Settings settings = FuzzCommand.settings(args);
        Path dir = Path.of(args.get("out", "."));
        Files.createDirectories(dir);
        try (Backends backends = new Backends(args.flag("compliance"))) {
            Campaign.Result result = Campaign.run(backends.runner(), RandomPlans.withDefaults(),
                    Campaign.units(settings), args.getInt("threads", 1));
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < result.units().size(); i++) {
                summary.append(CampaignFiles.summaryLine(result.units().get(i), result.outcomes().get(i)))
                        .append('\n');
            }
            CorrectnessRun run = CorrectnessRun.of("campaign", backends.runner().backends(),
                    Ledger.parse(Ledger.LINE_3_1_X, ""), result.outcomes());
            TextFiles.write(dir.resolve(CampaignFiles.UNITS), CorrectnessReport.unitsJsonl(run));
            TextFiles.write(dir.resolve(CampaignFiles.SUMMARY), summary);
            TextFiles.write(dir.resolve(CampaignFiles.INCONSISTENCIES),
                    String.join("\n", result.inconsistencies()));
            TextFiles.write(dir.resolve(CampaignFiles.DONE), result.units().size() + " units\n");
        }
        System.exit(0);
    }
}
