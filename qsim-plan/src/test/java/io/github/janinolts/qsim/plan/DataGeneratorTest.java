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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DataGeneratorTest {

    private static final Schema ALL_TYPES = Schema.of(DataType.values());

    @Test
    void sameSeedSameData() {
        Table a = DataGenerator.withSeed(4711).generate(ALL_TYPES, 300);
        Table b = DataGenerator.withSeed(4711).generate(ALL_TYPES, 300);
        for (int r = 0; r < a.size(); r++) {
            assertArrayEquals(a.rows().get(r), b.rows().get(r), "row " + r);
        }
    }

    @Test
    void otherSeedOtherData() {
        Table a = DataGenerator.withSeed(1).generate(ALL_TYPES, 100);
        Table b = DataGenerator.withSeed(2).generate(ALL_TYPES, 100);
        boolean differs = false;
        for (int r = 0; r < a.size(); r++) {
            differs |= !java.util.Arrays.equals(a.rows().get(r), b.rows().get(r));
        }
        assertTrue(differs);
    }

    @Test
    void everyEdgeValueAndNullAppear() {
        Table t = DataGenerator.withSeed(7).generate(ALL_TYPES, 64);
        for (int c = 0; c < ALL_TYPES.size(); c++) {
            Set<Object> values = new HashSet<>(column(t, c));
            for (Object edge : EdgeValues.of(ALL_TYPES.type(c))) {
                assertTrue(values.contains(edge), ALL_TYPES.type(c) + ": " + edge);
            }
            assertTrue(values.contains(null), ALL_TYPES.type(c) + ": NULL");
        }
    }

    @Test
    void valuesHaveTheTypeOfTheirColumn() {
        // The constructor of Table checks every value.
        Table t = new DataGenerator(99, 0.3, 0.2).generate(ALL_TYPES, 1024);
        assertEquals(1024, t.size());
    }

    @Test
    void nullRate() {
        Table t = new DataGenerator(5, 0.5, 0).generate(Schema.of(DataType.INT), 10_000);
        long nulls = column(t, 0).stream().filter(v -> v == null).count();
        assertTrue(nulls > 4_500 && nulls < 5_500, "nulls: " + nulls);

        Table noNulls = new DataGenerator(5, 0, 0.1).generate(Schema.of(DataType.INT), 1_000);
        assertFalse(column(noNulls, 0).contains(null));
    }

    @Test
    void fewerRowsThanEdgeValues() {
        Table t = DataGenerator.withSeed(3).generate(ALL_TYPES, 2);
        assertEquals(2, t.size());
    }

    private static List<Object> column(Table t, int c) {
        List<Object> result = new ArrayList<>();
        for (Object[] row : t.rows()) {
            result.add(row[c]);
        }
        return result;
    }
}
