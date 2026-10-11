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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.harness.IsolatedBackend;
import io.github.janinolts.qsim.harness.JaninoInstallation;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The measurement pipeline in one JVM, with real backends but tiny rounds:
 * the loop produces complete rounds, the raw data survive JSON, and
 * {@code aggregate} evaluates them, including P8.
 */
class PipelineTest {

    @Test
    void measureWriteReadAggregate() throws Exception {
        List<Measurements.Point> points = Measurements.of("P1", false, "Q_TINY*");
        assertEquals(1, points.size());
        List<RoundRecord> records = new ArrayList<>();
        List<ClassFileMeasurement.Row> classFiles;
        try (IsolatedBackend a = JaninoInstallation.baseline().load();
             IsolatedBackend b = JaninoInstallation.candidate().load();
             IsolatedBackend bPrime = JaninoInstallation.candidate().load()) {
            CompileOptions options = CompileOptions.of(ApiPath.CLASS_BODY, PipelineTest.class.getClassLoader());
            MeasurementLoop.Settings settings = new MeasurementLoop.Settings(5, 2_000_000, 2, 5_000_000_000L, 1);
            for (int fork = 0; fork < 3; fork++) {
                MeasurementLoop.run(points.get(0), new MeasurementLoop.Arms(a, b, bPrime), options, settings, "tiered",
                        "local", fork, records::add);
            }
            classFiles = ClassFileMeasurement.measure(points, a, b, options);
        }
        assertEquals(3 * 5 * 4, records.size());
        Set<String> arms = new HashSet<>();
        for (RoundRecord r : records) {
            arms.add(r.arm());
            assertTrue(r.ops() >= 1 && r.nanos() > 0 && r.allocatedBytes() > 0, r::toString);
            assertEquals(r.arm().equals("B+") ? r.ops() * (1 + Arm.PLUS_FRACTION) : r.ops(), r.executedOps(), 1e-9);
        }
        assertEquals(Set.of("A", "B", "B'", "B+"), arms);

        Path dir = Files.createTempDirectory("qsim-bench").resolve("local");
        Files.createDirectories(dir);
        Gson gson = new Gson();
        StringBuilder rounds = new StringBuilder();
        records.forEach(r -> rounds.append(gson.toJson(r)).append('\n'));
        Files.writeString(dir.resolve("rounds-tiered-0.jsonl"), rounds, StandardCharsets.UTF_8);
        StringBuilder rows = new StringBuilder();
        classFiles.forEach(r -> rows.append(gson.toJson(r)).append('\n'));
        Files.writeString(dir.resolve("classfiles.jsonl"), rows, StandardCharsets.UTF_8);

        AggregateCommand.Data data = AggregateCommand.read(List.of(dir.getParent()));
        assertEquals(records, data.rounds());
        PerformanceReport report = AggregateCommand.evaluate(data, Aggregator.Options.DEFAULT, 1.01);
        assertEquals(1, report.results().size());
        assertTrue(report.classFileFindings().isEmpty(), report.classFileFindings()::toString);
        String markdown = report.markdown();
        assertTrue(markdown.contains("`Q_TINY:ROWS/EXPRESSION` | tiered"), markdown);
        assertTrue(markdown.contains("| normalized | JLTS |"), markdown);
        assertFalse(markdown.contains("NaN"), markdown);
    }
}
