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

import java.util.ArrayDeque;

/**
 * Writes source code line by line, with indentation of four spaces per level.
 * It numbers the blocks that it opens, so that a generator can tell whether a
 * variable declared in a block is still in scope.
 */
final class CodeWriter {

    private static final String INDENT = "    ";

    private final StringBuilder sb = new StringBuilder();
    private final ArrayDeque<Integer> blocks = new ArrayDeque<>();
    private int nextBlock = 1;
    private final int initialLevel;
    private int level;
    private int lines;

    /** Creates a writer whose first line has the given indentation level. */
    CodeWriter(int level) {
        this.initialLevel = level;
        this.level = level;
    }

    /** Writes one line. */
    CodeWriter line(String text) {
        sb.append(INDENT.repeat(level)).append(text).append('\n');
        lines++;
        return this;
    }

    /** Writes an empty line. */
    CodeWriter blank() {
        sb.append('\n');
        lines++;
        return this;
    }

    /** Writes the text and an opening brace, and indents the following lines. */
    CodeWriter open(String text) {
        line(text + " {");
        level++;
        blocks.push(nextBlock++);
        return this;
    }

    /** Ends a block with a closing brace. */
    CodeWriter close() {
        return close("");
    }

    /** Ends a block with a closing brace followed by a suffix, e.g. {@code " while (false);"}. */
    CodeWriter close(String suffix) {
        if (level == 0) {
            throw new IllegalStateException("No open block");
        }
        level--;
        blocks.poll();
        return line("}" + suffix);
    }

    /** Ends a block and opens the next one on the same line, e.g. for {@code else}. */
    CodeWriter reopen(String text) {
        level--;
        line("} " + text + " {");
        level++;
        blocks.poll();
        blocks.push(nextBlock++);
        return this;
    }

    /**
     * Returns the number of the innermost block that this writer opened and
     * did not close yet, 0 outside of all of them.
     */
    int block() {
        Integer b = blocks.peek();
        return b == null ? 0 : b;
    }

    /** Tells whether a block, by its number from {@link #block()}, is still open; 0 always is. */
    boolean isOpen(int block) {
        return block == 0 || blocks.contains(block);
    }

    /**
     * Appends the code of another writer, which was written for the current
     * indentation level, e.g. a loop body generated before the loop.
     */
    CodeWriter append(CodeWriter other) {
        if (other.level != level) {
            throw new IllegalStateException("Indentation " + other.level + " appended at " + level);
        }
        sb.append(other.sb);
        lines += other.lines;
        return this;
    }

    /** Writes text that is already indented for the current level, e.g. a method generated before. */
    CodeWriter raw(String text) {
        sb.append(text);
        lines += (int) text.chars().filter(c -> c == '\n').count();
        return this;
    }

    /** Writes a comment if comments are enabled. */
    CodeWriter comment(boolean enabled, String text) {
        return enabled ? line("// " + text) : this;
    }

    /** Returns the current indentation level. */
    int level() {
        return level;
    }

    /** Tells whether the next line is not inside a block that this writer opened. */
    boolean atInitialLevel() {
        return level == initialLevel;
    }

    /** Returns the number of characters written. */
    int length() {
        return sb.length();
    }

    /** Returns the number of lines written. */
    int lineCount() {
        return lines;
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
