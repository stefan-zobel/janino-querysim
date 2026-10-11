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
import java.util.List;

/**
 * Counters of the operating system that only Linux provides: the involuntary
 * context switches of the current thread ({@code /proc/thread-self/status})
 * and the steal time of the machine ({@code /proc/stat}). Elsewhere, and on a
 * read error, they are -1. On Hyper-V virtual machines, such as the runners of
 * GitHub, the kernel usually reports no steal time.
 */
final class OsCounters {

    private static final Path THREAD_STATUS = Path.of("/proc/thread-self/status");
    private static final Path STAT = Path.of("/proc/stat");

    private OsCounters() {
    }

    /** Returns the involuntary context switches of the calling thread, or -1. */
    static long involuntarySwitches() {
        try {
            for (String line : Files.readAllLines(THREAD_STATUS)) {
                if (line.startsWith("nonvoluntary_ctxt_switches:")) {
                    return Long.parseLong(line.substring(line.indexOf(':') + 1).strip());
                }
            }
        } catch (IOException | RuntimeException e) {
            // not Linux, or not readable
        }
        return -1;
    }

    /** Returns the steal time of all CPUs in clock ticks, or -1. */
    static long stealTicks() {
        try {
            List<String> lines = Files.readAllLines(STAT);
            String[] fields = lines.get(0).strip().split("\\s+");
            if (fields[0].equals("cpu") && fields.length > 8) {
                return Long.parseLong(fields[8]);
            }
        } catch (IOException | RuntimeException e) {
            // not Linux, or not readable
        }
        return -1;
    }

    /** Returns the difference of two readings, or -1 if either is unknown. */
    static long delta(long before, long after) {
        return before < 0 || after < 0 ? -1 : after - before;
    }
}
