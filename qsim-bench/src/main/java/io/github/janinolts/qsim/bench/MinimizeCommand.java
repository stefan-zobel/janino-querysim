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
import io.github.janinolts.qsim.harness.Minimizer;
import io.github.janinolts.qsim.harness.RandomUnit;
import io.github.janinolts.qsim.harness.Reproducer;
import io.github.janinolts.qsim.harness.UnitCase;
import io.github.janinolts.qsim.harness.UnitOutcome;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.nio.file.Path;
import java.util.Set;

/**
 * The command {@code minimize}: minimizes a random unit with a difference
 * and writes its reproducer.
 * <p>
 * {@code minimize (--unit "<seed> <style> <profile> <variant> [<mutation>]" |
 * --seed <n> --style <style> [--profile STRICT] [--variant BASE]
 * [--mutation <m>]) --pair J3112:JLTS [--kind WRONG] [--seconds 300]
 * [--out repro/<key>]}
 * <p>
 * Without {@code --kind}, the first difference of the pair is kept. The exit
 * code is 1 if the unit shows no such difference.
 */
final class MinimizeCommand {

    private MinimizeCommand() {
    }

    static int run(String[] argv) throws Exception {
        Args args = new Args(argv, Set.of());
        RandomUnit random = FuzzCommand.unit(args);
        Difference.Pair pair = Difference.Pair.ofLabel(args.get("pair", "J3112:JLTS"));
        String kindName = args.get("kind", null);
        UnitCase unit = random.build(RandomPlans.withDefaults());
        if (unit == null) {
            System.err.println("The style " + random.style() + " does not fit the plan of seed " + random.seed());
            return 1;
        }
        try (Backends backends = new Backends()) {
            UnitOutcome outcome = backends.runner().run(unit);
            Difference first = outcome.differences().stream()
                    .filter(d -> d.pair() == pair && (kindName == null || d.kind().name().equals(kindName)))
                    .findFirst().orElse(null);
            if (first == null) {
                System.err.println("No difference " + pair.label() + (kindName == null ? "" : " " + kindName)
                        + " for " + unit.key() + "; its differences: " + outcome.differences());
                return 1;
            }
            Minimizer.Result result = new Minimizer(backends.runner(), 2000, args.getInt("seconds", 300) * 1000L)
                    .minimize(unit, Minimizer.Target.of(first));
            Difference shown = result.outcome().differences().stream()
                    .filter(d -> d.pair() == first.pair() && d.kind() == first.kind()).findFirst().orElseThrow();
            Path dir = Path.of(args.get("out", Path.of("repro", Reproducer.directoryName(unit.key())).toString()));
            Reproducer.write(dir, result.unit(), result.outcome(), shown, random.toString(),
                    Backends.reproducerClassPath());
            System.out.println("Minimized with " + result.checks() + " checks" + (result.exhausted()
                    ? " (budget used up)" : "") + "; reproducer in " + dir.toAbsolutePath());
            return 0;
        }
    }
}
