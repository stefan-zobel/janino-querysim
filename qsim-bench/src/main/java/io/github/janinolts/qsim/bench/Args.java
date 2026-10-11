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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The arguments of a command: options {@code --name value}, flags
 * {@code --name}, and positional arguments.
 */
final class Args {

    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    /**
     * Parses arguments.
     *
     * @param flags the names of the options without a value
     * @throws IllegalArgumentException for an option without its value
     */
    Args(String[] args, Set<String> flags) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String name = a.substring(2);
                if (flags.contains(name)) {
                    options.put(name, "true");
                } else if (i + 1 < args.length) {
                    options.put(name, args[++i]);
                } else {
                    throw new IllegalArgumentException("Option " + a + " needs a value");
                }
            } else {
                positional.add(a);
            }
        }
    }

    String get(String name, String defaultValue) {
        return options.getOrDefault(name, defaultValue);
    }

    int getInt(String name, int defaultValue) {
        return options.containsKey(name) ? Integer.parseInt(options.get(name)) : defaultValue;
    }

    long getLong(String name, long defaultValue) {
        return options.containsKey(name) ? Long.parseLong(options.get(name)) : defaultValue;
    }

    double getDouble(String name, double defaultValue) {
        return options.containsKey(name) ? Double.parseDouble(options.get(name)) : defaultValue;
    }

    boolean flag(String name) {
        return options.containsKey(name);
    }

    List<String> positional() {
        return positional;
    }
}
