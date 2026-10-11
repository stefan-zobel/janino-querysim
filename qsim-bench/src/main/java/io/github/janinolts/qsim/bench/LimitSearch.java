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
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.javac.JavacBackend;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.ClassFileInfo;
import io.github.janinolts.qsim.harness.CompiledQuery;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;
import io.github.janinolts.qsim.harness.Limits;
import io.github.janinolts.qsim.harness.QueryRunner;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The search for the limits of a method in P2: the levels of a series without
 * splitting, compiled once by each backend, as shipped. Every compilation
 * runs in a JVM of its own with the heap of the measuring JVMs and a time
 * limit, because a compiler may exhaust the memory or take very long on such
 * methods; that is a result, with the outcome {@code OUT_OF_MEMORY},
 * {@code TIMEOUT} or {@code CRASH}.
 */
final class LimitSearch {

    /** The JVM options of a compilation. */
    static final List<String> JVM_OPTIONS = List.of("-Xms2g", "-Xmx2g", "-XX:+UseSerialGC");

    private LimitSearch() {
    }

    /**
     * Runs the search.
     *
     * @param versions the version of each backend, by ID
     * @param installations the system properties that name the directories of the Janino versions
     * @param dir the directory for the logs of the compilations
     */
    static List<ClassFileMeasurement.LimitRow> run(List<Series> series, Map<String, String> versions,
            List<String> installations, long timeoutSeconds, Path dir) throws IOException, InterruptedException {
        Gson gson = new Gson();
        List<ClassFileMeasurement.LimitRow> rows = new ArrayList<>();
        for (Series s : series) {
            for (int level : s.levels()) {
                int chars = s.unsplitUnit(level).generate().units().stream().mapToInt(u -> u.body().length()).sum();
                for (Map.Entry<String, String> backend : versions.entrySet()) {
                    List<String> command = new ArrayList<>();
                    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                    command.addAll(JVM_OPTIONS);
                    command.addAll(installations);
                    command.add("-cp");
                    command.add(System.getProperty("java.class.path"));
                    command.add(LimitSearch.class.getName());
                    command.addAll(List.of("--series", s.label(), "--level", Integer.toString(level), "--backend",
                            backend.getKey()));
                    Path log = dir.resolve("limits-" + s.label() + "-" + level + "-" + backend.getKey() + ".log");
                    Process process = new ProcessBuilder(command).redirectErrorStream(true)
                            .redirectOutput(log.toFile()).start();
                    ClassFileMeasurement.LimitRow row;
                    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor();
                        row = failed(s, level, chars, backend, "TIMEOUT", "more than " + timeoutSeconds + " s");
                    } else {
                        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                        String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
                        row = process.exitValue() == 0 && last.startsWith("{")
                                ? gson.fromJson(last, ClassFileMeasurement.LimitRow.class)
                                : failed(s, level, chars, backend, "CRASH", "exit " + process.exitValue() + ": "
                                        + firstLine(lines));
                    }
                    rows.add(row);
                    System.out.println("P2 limits " + s.label() + " " + level + " " + backend.getKey() + ": "
                            + row.outcome());
                }
            }
        }
        return rows;
    }

    private static ClassFileMeasurement.LimitRow failed(Series s, int level, int chars,
            Map.Entry<String, String> backend, String outcome, String message) {
        return new ClassFileMeasurement.LimitRow(s.label(), level, chars, backend.getKey(), backend.getValue(),
                outcome, message, 0, 0, 0);
    }

    private static String firstLine(List<String> lines) {
        for (String line : lines) {
            if (!line.isBlank()) {
                return line.length() > 160 ? line.substring(0, 160) + "..." : line;
            }
        }
        return "";
    }

    /**
     * The child: {@code --series <label> --level <n> --backend JAVAC|J3112|JLTS};
     * prints one line of JSON.
     */
    public static void main(String[] argv) throws IOException {
        Args args = new Args(argv, Set.of());
        Series series = Series.ofLabel(args.get("series", null));
        int level = args.getInt("level", 0);
        String id = args.get("backend", null);
        GeneratedQuery query = series.unsplitUnit(level).generate();
        int chars = query.units().stream().mapToInt(u -> u.body().length()).sum();
        CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, LimitSearch.class.getClassLoader());
        IsolatedBackend janino = switch (id) {
            case "J3112" -> JaninoInstallation.baseline().load();
            case "JLTS" -> JaninoInstallation.candidate().load();
            default -> null;
        };
        CompilerBackend backend = janino != null ? janino : new JavacBackend();
        ClassFileMeasurement.LimitRow row;
        try {
            row = limit(series, level, chars, backend, options, query);
        } catch (OutOfMemoryError e) {
            row = new ClassFileMeasurement.LimitRow(series.label(), level, chars, backend.id(), backend.version(),
                    "OUT_OF_MEMORY", "heap of " + JVM_OPTIONS.get(1).substring(4) + " exhausted", 0, 0, 0);
        }
        System.out.println(new Gson().toJson(row));
        if (janino != null) {
            janino.close();
        }
    }

    /** Compiles one level with one backend and describes the class files. */
    static ClassFileMeasurement.LimitRow limit(Series series, int level, int chars, CompilerBackend backend,
            CompileOptions options, GeneratedQuery query) {
        CompiledQuery r = QueryRunner.compile(query, backend, options);
        CompileResult failure = r.failure();
        String outcome = failure == null ? "OK" : Limits.isLimit(failure) ? "LIMIT" : failure.outcome().name();
        String message = failure == null ? null : BenchCommand.rootMessage(failure, 160);
        int bytes = 0;
        int largest = 0;
        int huge = 0;
        for (byte[] classFile : r.classFiles().values()) {
            ClassFileInfo info = ClassFileInfo.of(classFile);
            bytes += info.size();
            largest = Math.max(largest, info.largestMethod());
            huge += (int) info.methods().stream()
                    .filter(m -> m.codeLength() > ClassFileMeasurement.HUGE_METHOD).count();
        }
        return new ClassFileMeasurement.LimitRow(series.label(), level, chars, backend.id(), backend.version(),
                outcome, message, bytes, largest, huge);
    }
}
