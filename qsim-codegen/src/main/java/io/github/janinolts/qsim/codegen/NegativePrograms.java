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

package io.github.janinolts.qsim.codegen;

import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.UnitKind;
import io.github.janinolts.qsim.backend.UnitMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

/**
 * Negative programs: a targeted mutation makes valid generated code invalid.
 * Every backend must reject the result, and the baseline and the candidate
 * must report the same line and column. The mutation inserts statements at
 * the start of a method, constructor or anonymous class method of a class
 * unit, chosen by a random source; code that Janino accepts although it is
 * invalid (the leniencies of the register) belongs to the profile
 * {@code LENIENT}, not here.
 */
public final class NegativePrograms {

    /** The mutations. */
    public enum Mutation {
        /** A name that nothing declares: {@code int negative_0 = undefinedName;}. */
        UNKNOWN_NAME,
        /** A value of the wrong type: {@code int negative_0 = "negative";}. */
        TYPE_MISMATCH,
        /** A method of a local class that can complete without a {@code return}. */
        MISSING_RETURN,
        /** Two local variables with the same name in one block. */
        DUPLICATE_VARIABLE,
        /** A checked exception that the method neither catches nor declares. */
        UNREPORTED_EXCEPTION
    }

    /** A line that starts a method or a constructor body: a header like {@code public boolean apply(Object a0)}. */
    private static final Pattern METHOD_HEADER =
            Pattern.compile("\\s*(public|protected|private)\\s[^=;]*\\)(\\s+throws\\s+[\\w.]+)?\\s*\\{");

    private NegativePrograms() {
    }

    /** Tells whether a query has a unit that a mutation can change: a class with a method. */
    public static boolean applies(GeneratedQuery query) {
        return query.units().stream().anyMatch(u -> u.kind() == UnitKind.CLASS && !headers(u.body()).isEmpty());
    }

    /**
     * Applies a mutation to one class unit of a query.
     *
     * @throws IllegalArgumentException if no unit can be changed
     */
    public static GeneratedQuery apply(GeneratedQuery query, Mutation mutation, SplittableRandom random) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < query.units().size(); i++) {
            GeneratedUnit u = query.units().get(i);
            if (u.kind() == UnitKind.CLASS && !headers(u.body()).isEmpty()) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No class unit with a method");
        }
        int index = candidates.get(random.nextInt(candidates.size()));
        GeneratedUnit u = query.units().get(index);
        String[] lines = u.body().split("\n", -1);
        List<Integer> headers = headers(u.body());
        int header = headers.get(random.nextInt(headers.size()));
        String indent = " ".repeat(leadingSpaces(lines[header]) + 4);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            body.append(lines[i]);
            if (i + 1 < lines.length) {
                body.append('\n');
            }
            if (i == header) {
                for (String statement : statements(mutation)) {
                    body.append(indent).append(statement).append('\n');
                }
            }
        }
        UnitMetadata m = u.metadata();
        List<String> tags = new ArrayList<>(m.featureTags());
        tags.add("negative");
        GeneratedUnit mutated = new GeneratedUnit(u.kind(), u.packageName(), u.className(), u.imports(),
                u.extendedClass(), u.implementedInterfaces(), body.toString(), u.parameters(), u.returnType(),
                u.instantiation(), u.references(), new UnitMetadata(m.seed(), m.style(), m.profile(), m.configHash(),
                        tags));
        List<GeneratedUnit> units = new ArrayList<>(query.units());
        units.set(index, mutated);
        return new GeneratedQuery(units, query.apiPath(), query.driver(), query.scans(), query.ordered());
    }

    private static List<String> statements(Mutation mutation) {
        return switch (mutation) {
            case UNKNOWN_NAME -> List.of("int negative_0 = undefinedName;");
            case TYPE_MISMATCH -> List.of("int negative_0 = \"negative\";");
            case MISSING_RETURN -> List.of(
                    "class Negative_0 {",
                    "    int f(int x) {",
                    "        if (x > 0) {",
                    "            return 1;",
                    "        }",
                    "    }",
                    "}");
            case DUPLICATE_VARIABLE -> List.of("int negative_0 = 0;", "int negative_0 = 1;");
            case UNREPORTED_EXCEPTION -> List.of(
                    "if (System.nanoTime() == 0L) {",
                    "    throw new CloneNotSupportedException(\"negative\");",
                    "}");
        };
    }

    /** Returns the indexes of the lines of a body that start a method, constructor or anonymous class method. */
    private static List<Integer> headers(String body) {
        List<Integer> result = new ArrayList<>();
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (METHOD_HEADER.matcher(lines[i]).matches()) {
                result.add(i);
            }
        }
        return result;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}
