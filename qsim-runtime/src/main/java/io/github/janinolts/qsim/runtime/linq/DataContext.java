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

package io.github.janinolts.qsim.runtime.linq;

import io.github.janinolts.qsim.runtime.Position;

/**
 * What a bound query reads: the rows of its tables, per scan in the order of
 * the scans, the run-time objects that the generated code refers to by index,
 * and the position for the attribution of exceptions.
 */
public final class DataContext {

    private final Object[][][] tables;
    private final Object[] references;
    private final Position position = new Position();

    /**
     * Creates a context without references.
     *
     * @param tables per scan the rows of its table
     */
    public DataContext(Object[][][] tables) {
        this(tables, new Object[0]);
    }

    /**
     * Creates a context.
     *
     * @param tables per scan the rows of its table
     * @param references the run-time objects of the generated code, e.g.
     *        constants; not copied
     */
    public DataContext(Object[][][] tables, Object[] references) {
        this.tables = tables;
        this.references = references;
    }

    /** Returns the run-time object with the given index. */
    public Object reference(int index) {
        return references[index];
    }

    /** Returns the rows of the table of the scan with the given index. */
    public Object[][] table(int scan) {
        return tables[scan];
    }

    /** Returns the position of the running query. */
    public Position position() {
        return position;
    }
}
