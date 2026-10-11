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

import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.harness.RandomUnit;
import io.github.janinolts.qsim.harness.UnitCase;
import io.github.janinolts.qsim.harness.Workloads;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.RandomPlans;
import io.github.janinolts.qsim.plan.Scenario;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The command {@code dump}: writes the generated source text of a unit, one
 * compilation unit per generated class, into a directory.
 * <p>
 * {@code dump (--unit "<seed> <style> <profile> <variant> [<mutation>]" |
 * --seed <n> --style <style> [--profile] [--variant] [--mutation] |
 * --scenario Q_AGG --style <style> [--size S] [--layout ROWS] [--ansi])
 * [--out dump]}
 */
final class DumpCommand {

    private DumpCommand() {
    }

    static int run(String[] argv) throws Exception {
        Args args = new Args(argv, Set.of("ansi"));
        UnitCase unit;
        String scenario = args.get("scenario", null);
        if (scenario != null) {
            Scenario.Size size = Scenario.Size.valueOf(args.get("size", "S"));
            PlanNode.Layout layout = PlanNode.Layout.valueOf(args.get("layout", "ROWS"));
            Scenario s = Scenario.of(scenario, size, layout);
            Style style = Style.valueOf(args.get("style", "WHOLE_STAGE"));
            unit = UnitCase.of(Workloads.workload(scenario, size, layout), s.plan(),
                    s.data(Workloads.SCENARIO_SEED, Workloads.ROWS),
                    Workloads.config(scenario, style, args.flag("ansi"), Workloads.SCENARIO_SEED));
        } else {
            RandomUnit random = FuzzCommand.unit(args);
            unit = random.build(RandomPlans.withDefaults());
            if (unit == null) {
                System.err.println("The style " + random.style() + " does not fit the plan of seed "
                        + random.seed());
                return 1;
            }
        }
        GeneratedQuery query = unit.generate();
        Path dir = Path.of(args.get("out", "dump"));
        Files.createDirectories(dir);
        for (GeneratedUnit u : query.units()) {
            Path file = dir.resolve(u.className() + ".java");
            Files.writeString(file, UnitRenderer.compilationUnit(u), StandardCharsets.UTF_8);
            System.out.println(file.toAbsolutePath());
        }
        System.out.println(unit.key() + ", API path " + query.apiPath());
        return 0;
    }
}
