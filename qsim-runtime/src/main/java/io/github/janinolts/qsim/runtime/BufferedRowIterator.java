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

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * The superclass of the iterator that a class in the whole-stage style
 * generates: the generated {@link #processNext()} runs the fused loop of a
 * pipeline and {@linkplain #append(Object[]) appends} output rows; it returns
 * when {@link #shouldStop()} says so and continues where it stopped on the
 * next call.
 * <p>
 * The generated code records the row that it processes in {@link #position},
 * so that an exception can be attributed to its row.
 */
public abstract class BufferedRowIterator {

    private final ArrayDeque<Object[]> rows = new ArrayDeque<Object[]>();

    /** The row being processed: the source of the current pipeline and the index of the row in it. */
    protected final Position position = new Position();

    /**
     * Passes the inputs: per scan of the plan, in the order of the scans, the
     * rows ({@code Object[][]}) or the column batch of its table.
     */
    public abstract void init(Object[] inputs);

    /** Processes input until at least one output row is appended or the input is exhausted. */
    protected abstract void processNext() throws IOException;

    /** Appends an output row. */
    protected void append(Object[] row) {
        rows.add(row);
    }

    /** Tells the generated loop to return because an output row is ready. */
    protected boolean shouldStop() {
        return !rows.isEmpty();
    }

    /** Tells whether there is another output row; may process input. */
    public boolean hasNext() throws IOException {
        if (rows.isEmpty()) {
            processNext();
        }
        return !rows.isEmpty();
    }

    /** Returns the next output row. */
    public Object[] next() {
        return rows.remove();
    }

    /** Returns the row being processed, for an exception. */
    public Position position() {
        return position;
    }
}
