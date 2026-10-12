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

import java.util.Arrays;

/**
 * The command line of {@code qsim.jar}.
 */
public final class Main {

    private static final String USAGE = """
            Usage: java -jar qsim.jar <command> [options]

              bench      [--mode xint,tiered] [--forks 3] [--rounds 40] [--round-millis 150]
                         [--min-rounds 8] [--point-seconds 240] [--replica local] [--seed 1]
                         [--points <pattern>,...] [--extended]
                         [--fork-timeout-minutes 120] [--cold-forks 3] [--cold-rounds 10]
                         [--cold-timeout-seconds 600] [--limit-timeout-seconds 600] [--out results]
                         [P1 ... P10]
                         Measures in fresh JVMs and writes raw data to <out>/<replica>/;
                         without measurements P1 and P8. The measured rounds of a point
                         end after --point-seconds, but not before --min-rounds.
              aggregate  [--delta 0.02] [--q 0.05] [--allocation-limit 1.01] [--out <dir>] <dir>...
                         Combines the raw data of all replicas and writes report-performance.md,
                         for P2 also scaling/*.csv and scaling/*.svg.
              check      --suite smoke|regression [--compliance] [--out <dir>]
                         Runs a suite and reconciles it with the ledger of the candidate;
                         with --compliance also the candidate in its compliance mode (JLTS-C).
              fuzz       [--seeds 1..2000] [--styles all] [--profiles STRICT|all|LENIENT,...]
                         [--variants none|one|all] [--negatives] [--try-jumps] [--compliance]
                         [--batch 200] [--parallel <n>]
                         [--threads 1] [--timeout-minutes 30] [--minimize-seconds 120]
                         [--max-reproducers 50] [--no-minimize] [--out <dir>]
                         A campaign of random plans in child JVMs, with minimized reproducers.
              minimize   (--unit "<seed> <style> <profile> <variant> [<mutation>]" | --seed <n>
                         --style <style> [--profile] [--variant] [--mutation]) --pair <pair>
                         [--kind <kind>] [--seconds 300] [--out <dir>]
                         Minimizes a unit with a difference and writes its reproducer
                         (with the pair JAVAC:JLTS-C in the compliance mode).
              dump       (--unit "..." | --seed <n> --style <style> ... | --scenario <name> --style <style>
                         [--size S] [--layout ROWS] [--ansi]) [--out dump]
                         Writes the generated source text of a unit.

            The JARs of the two Janino versions are taken from janino-baseline and janino-candidate
            next to qsim.jar, or from -Dqsim.baseline.dir and -Dqsim.candidate.dir.
            """;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.print(USAGE);
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int exit;
        try {
            exit = switch (args[0]) {
                case "bench" -> BenchCommand.run(rest);
                case "aggregate" -> AggregateCommand.run(rest);
                case "check" -> CheckCommand.run(rest);
                case "fuzz" -> FuzzCommand.run(rest);
                case "minimize" -> MinimizeCommand.run(rest);
                case "dump" -> DumpCommand.run(rest);
                default -> {
                    System.err.print("Unknown command: " + args[0] + "\n\n" + USAGE);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            exit = 2;
        }
        System.exit(exit);
    }
}
