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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The description of the environment of a replica, written as
 * {@code environment.json}.
 *
 * @param replica the replica
 * @param jdkVendor the vendor of the JDK
 * @param jdkVersion the version of the JDK
 * @param os the operating system and its version
 * @param runnerImage the image of the GitHub runner, or empty
 * @param cpuModel the model of the CPU, if known
 * @param cores the available processors
 * @param jvmOptions the JVM options per mode
 * @param baselineVersion the version of the baseline
 * @param candidateVersion the version of the candidate
 * @param commit the commit of qsim, or {@code unknown}
 * @param time the time of the run
 * @param seconds the duration in seconds of the selection of the points
 *        ({@code select}, which includes P8 and the search for the limits
 *        of P2) and of each measurement and mode, e.g. {@code P1 xint};
 *        {@code null} in raw data written before
 * @param copies which copies of the candidate the arms use (see
 *        {@link PlusLoader}); {@code null} in raw data written before, in
 *        which {@code B+} shared the copy of {@code B}
 */
public record Environment(String replica, String jdkVendor, String jdkVersion, String os, String runnerImage,
        String cpuModel, int cores, Map<String, List<String>> jvmOptions, String baselineVersion,
        String candidateVersion, String commit, String time, Map<String, Long> seconds, String copies) {

    /** The name of the file. */
    public static final String FILE = "environment.json";

    /** Describes the current environment, with the default copies of the candidate. */
    static Environment current(String replica, Map<String, List<String>> jvmOptions, String baselineVersion,
            String candidateVersion, Map<String, Long> seconds) {
        return current(replica, jvmOptions, baselineVersion, candidateVersion, seconds,
                PlusLoader.OWN.describe(false));
    }

    /** Describes the current environment. */
    static Environment current(String replica, Map<String, List<String>> jvmOptions, String baselineVersion,
            String candidateVersion, Map<String, Long> seconds, String copies) {
        String image = System.getenv().getOrDefault("ImageOS", "") + " "
                + System.getenv().getOrDefault("ImageVersion", "");
        return new Environment(replica, System.getProperty("java.vendor"), Runtime.version().toString(),
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"),
                image.strip(), readCpuModel(), Runtime.getRuntime().availableProcessors(), jvmOptions, baselineVersion,
                candidateVersion, System.getenv().getOrDefault("GITHUB_SHA", "unknown"), Instant.now().toString(),
                Map.copyOf(seconds), copies);
    }

    private static String readCpuModel() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).strip();
                }
            }
        } catch (IOException | RuntimeException e) {
            // not Linux
        }
        return System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown");
    }
}
