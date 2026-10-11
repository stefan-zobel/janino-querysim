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

/**
 * An exception that a row threw during the execution of a plan. The
 * comparison of results considers the class of the {@linkplain #getCause()
 * cause}, the source and the row index, not the message.
 */
public final class RowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String source;
    private final int rowIndex;

    /**
     * Creates the exception.
     *
     * @param source the source of the pipeline: a table name, or
     *        {@code node:<id>} for an operator that collects its input
     * @param rowIndex the index of the row in the source
     * @param cause what the row threw
     */
    public RowException(String source, int rowIndex, RuntimeException cause) {
        super(source + " row " + rowIndex + ": " + cause, cause);
        this.source = source;
        this.rowIndex = rowIndex;
    }

    /** Returns the source of the pipeline in which the row threw. */
    public String source() {
        return source;
    }

    /** Returns the index of the row in its source. */
    public int rowIndex() {
        return rowIndex;
    }
}
