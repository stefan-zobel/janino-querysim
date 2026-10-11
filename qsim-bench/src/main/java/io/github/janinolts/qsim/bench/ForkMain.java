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

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;

import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The entry point of a fork: a fresh JVM, started by {@code bench} with the
 * options of one mode, that measures the points of one measurement and writes
 * the rounds as JSON lines. In P1 (except in xint) and P9, javac takes part
 * as the reference {@code J}.
 */
public final class ForkMain {

    private ForkMain() {
    }

    /**
     * Arguments: {@code --measurement P1 --mode xint --replica r --fork 0
     * --rounds 40 --round-millis 150 --seed 1 --min-rounds 8 --point-seconds 240
     * --out rounds.jsonl}, optionally
     * {@code --points <patterns>} or {@code --points-file <file>} with one
     * point ID per line, and {@code --extended}.
     */
    public static void main(String[] argv) throws IOException {
        Args args = new Args(argv, Set.of("extended"));
        String measurement = args.get("measurement", "P1");
        List<Measurements.Point> points = Measurements.of(measurement, args.flag("extended"),
                args.get("points", null));
        String pointsFile = args.get("points-file", null);
        if (pointsFile != null) {
            Set<String> ids = new HashSet<>(Files.readAllLines(Path.of(pointsFile), StandardCharsets.UTF_8));
            points = points.stream().filter(p -> ids.contains(p.id())).toList();
        }
        MeasurementLoop.Settings settings = new MeasurementLoop.Settings(args.getInt("rounds", 40),
                args.getLong("round-millis", 150) * 1_000_000, 3,
                args.getLong("max-warmup-seconds", 120) * 1_000_000_000, args.getLong("seed", 1),
                Math.min(args.getInt("rounds", 40), args.getInt("min-rounds", 8)),
                args.getLong("point-seconds", 240) * 1_000_000_000);
        String mode = args.get("mode", "tiered");
        String replica = args.get("replica", "local");
        int fork = args.getInt("fork", 0);
        Path out = Path.of(args.get("out", "rounds.jsonl"));

        try (IsolatedBackend a = JaninoInstallation.baseline().load();
             IsolatedBackend b = JaninoInstallation.candidate().load();
             IsolatedBackend bPrime = JaninoInstallation.candidate().load();
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            MeasurementLoop.Arms arms = new MeasurementLoop.Arms(a, b, bPrime,
                    javacReference(measurement, mode) ? new JavacBackend() : null);
            CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, ForkMain.class.getClassLoader());
            Gson gson = new Gson();
            for (Measurements.Point point : points) {
                MeasurementLoop.run(point, arms, options, settings, mode, replica, fork, r -> {
                    try {
                        w.write(gson.toJson(r));
                        w.write('\n');
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                w.flush();
            }
        }
    }

    /**
     * Tells whether javac takes part as the reference {@code J}: in P9, the
     * runtime of the generated code, and in P1, the compile time, except in
     * xint, where javac would take very long and add nothing to the
     * classification of Janino.
     */
    static boolean javacReference(String measurement, String mode) {
        return measurement.equals("P9") || measurement.equals("P1") && !mode.equals(Mode.XINT.label());
    }
}
