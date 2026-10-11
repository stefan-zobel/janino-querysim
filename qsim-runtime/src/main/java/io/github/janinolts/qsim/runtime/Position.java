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

package io.github.janinolts.qsim.runtime;

/**
 * The row that the running query processes, so that an exception can be
 * attributed to it: the source of the current pipeline and the index of the
 * row in it. A source is a table, by its name, or an operator that collects
 * its whole input before it produces rows (an aggregation, a sort, a union),
 * by the label {@code node:<n>}.
 */
public final class Position {

    private String source = "";
    private int row = -1;

    /** Records that a source produced the row with the given index. */
    public void set(String source, int row) {
        this.source = source;
        this.row = row;
    }

    /** Returns the source of the current row, or an empty string. */
    public String source() {
        return source;
    }

    /** Returns the index of the current row in its source, or -1. */
    public int row() {
        return row;
    }

    /** Returns the label of an operator that collects its input, e.g. {@code node:3}. */
    public static String node(int id) {
        return "node:" + id;
    }
}
