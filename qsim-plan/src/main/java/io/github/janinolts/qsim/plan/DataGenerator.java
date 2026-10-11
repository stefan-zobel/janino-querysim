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

package io.github.janinolts.qsim.plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates table data deterministically from a seed.
 * <p>
 * Each column starts with all {@linkplain EdgeValues edge values} of its type,
 * plus one {@code NULL} if the null rate is above zero, in an order that
 * depends on the seed and the column; with fewer rows, only as many of them as
 * fit. The remaining rows are {@code NULL} with the null rate (never in a
 * column without {@code NULL}), an edge value
 * with the edge rate, and a random value otherwise. The same seed, schema and
 * row count always give the same data.
 *
 * @param seed the seed
 * @param nullRate the probability of {@code NULL} in the random rows, from 0
 *        to 1
 * @param edgeRate the probability of an edge value in the random rows, from 0
 *        to 1
 */
public record DataGenerator(long seed, double nullRate, double edgeRate) {

    /** The code units of random strings; the pair is one character outside the Basic Multilingual Plane. */
    private static final List<String> STRING_PARTS = List.of(
            "a", "b", "c", "x", "y", "z", "A", "B", "Z", "0", "1", "9", " ", "%", "_", "-", ".",
            "\u00DF", "\u00E9", "\u0131", "\uD834\uDD1E");

    public DataGenerator {
        if (nullRate < 0 || edgeRate < 0 || nullRate + edgeRate > 1) {
            throw new IllegalArgumentException("nullRate " + nullRate + ", edgeRate " + edgeRate);
        }
    }

    /** Returns a generator with a null rate of 10 % and an edge rate of 10 %. */
    public static DataGenerator withSeed(long seed) {
        return new DataGenerator(seed, 0.1, 0.1);
    }

    /** Generates a table. */
    public Table generate(Schema schema, int rows) {
        List<Object[]> data = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            data.add(new Object[schema.size()]);
        }
        for (int c = 0; c < schema.size(); c++) {
            fillColumn(schema.type(c), schema.nullable(c) ? nullRate : 0, c, data);
        }
        return new Table(schema, data);
    }

    private void fillColumn(DataType type, double nulls, int column, List<Object[]> data) {
        SplittableRandom random = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + column);
        List<Object> edges = new ArrayList<>(EdgeValues.of(type));
        if (nulls > 0) {
            edges.add(null);
        }
        shuffle(edges, random);
        for (int r = 0; r < data.size(); r++) {
            Object value;
            if (r < edges.size()) {
                value = edges.get(r);
            } else {
                double p = random.nextDouble();
                if (p < nulls) {
                    value = null;
                } else if (p < nulls + edgeRate) {
                    value = edges.get(random.nextInt(edges.size()));
                } else {
                    value = randomValue(type, random);
                }
            }
            data.get(r)[column] = value;
        }
    }

    /** A Fisher-Yates shuffle with the given random, independent of {@link Collections#shuffle}. */
    private static void shuffle(List<Object> list, SplittableRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, random.nextInt(i + 1));
        }
    }

    private static Object randomValue(DataType type, SplittableRandom random) {
        boolean small = random.nextBoolean();
        return switch (type) {
            case BOOLEAN -> random.nextBoolean();
            case BYTE -> (byte) random.nextInt();
            case SHORT -> (short) random.nextInt();
            case INT -> small ? random.nextInt(-1000, 1001) : random.nextInt();
            case LONG -> small ? random.nextLong(-1000, 1001) : random.nextLong();
            case FLOAT -> small ? (float) (random.nextInt(-100_000, 100_001) / 100.0)
                    : Float.intBitsToFloat(random.nextInt());
            case DOUBLE -> small ? random.nextInt(-100_000, 100_001) / 100.0
                    : Double.longBitsToDouble(random.nextLong());
            case STRING -> {
                StringBuilder sb = new StringBuilder();
                int length = random.nextInt(0, 9);
                for (int i = 0; i < length; i++) {
                    sb.append(STRING_PARTS.get(random.nextInt(STRING_PARTS.size())));
                }
                yield sb.toString();
            }
            case DECIMAL -> BigDecimal.valueOf(random.nextLong(-1_000_000_000L, 1_000_000_001L), random.nextInt(0, 7));
            case DATE -> random.nextInt(-50_000, 50_001);
        };
    }
}
