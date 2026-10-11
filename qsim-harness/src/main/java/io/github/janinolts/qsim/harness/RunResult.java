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

package io.github.janinolts.qsim.harness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * What a backend, or the interpreter, made of a query: the output rows, an
 * exception with the index of the input row that threw it, or a failure to
 * compile or load. Two results are {@linkplain #sameAs equal} if their status,
 * rows, exception class and row index are; messages are only recorded.
 *
 * @param status how the run ended
 * @param rows the output rows in canonical form, if {@link Status#OK}: in
 *        their order if the query defines one, otherwise sorted (compared as a
 *        multiset)
 * @param exceptionClass the class of the exception, if {@link Status#THROWS}
 * @param source the source of the pipeline in which the row threw, if
 *        {@link Status#THROWS}: a table, or {@code node:<id>}
 * @param rowIndex the index of the row in its source that threw, if
 *        {@link Status#THROWS}
 * @param message the message of the exception or of the compile error
 * @param line the line of the compile error, or 0
 * @param column the column of the compile error, or 0
 */
public record RunResult(Status status, List<String> rows, String exceptionClass, String source, int rowIndex,
        String message, int line, int column) {

    /** How a run ended. */
    public enum Status {
        /** Compiled, loaded and ran; the rows are there. */
        OK,
        /** Ran, and an input row threw an exception. */
        THROWS,
        /** A compile error. */
        REJECTED,
        /** The compiler failed. */
        INTERNAL_ERROR,
        /** The JVM rejected the class files, e.g. with a {@code VerifyError}. */
        INVALID_CLASS,
        /**
         * A limit of the JVM was reached: a method over 64 KB, too many
         * constants or parameters, or nesting too deep for the compiler. Janino
         * and javac report these differently; see {@link Limits}.
         */
        LIMIT,
        /** The backend does not support an option. */
        UNSUPPORTED,
        /** The run took longer than the time limit (in a child JVM). */
        TIMEOUT,
        /** The child JVM of the run ended abnormally. */
        CRASH;

        /** Tells whether the code compiled and ran, with or without an exception. */
        public boolean ran() {
            return this == OK || this == THROWS;
        }
    }

    public RunResult {
        Objects.requireNonNull(status, "status");
        rows = List.copyOf(rows);
    }

    /**
     * A successful run with the given output rows.
     *
     * @param ordered whether the order of the rows is defined; otherwise they
     *        are compared as a multiset
     */
    static RunResult ok(List<Object[]> rows, boolean ordered) {
        List<String> canonical = new ArrayList<>();
        for (Object[] row : rows) {
            canonical.add(canonical(row));
        }
        if (!ordered) {
            Collections.sort(canonical);
        }
        return new RunResult(Status.OK, canonical, null, null, -1, null, 0, 0);
    }

    /** A successful run whose rows are compared as a multiset. */
    static RunResult ok(List<Object[]> rows) {
        return ok(rows, false);
    }

    /** A run in which a row threw. */
    static RunResult threw(Throwable exception, String source, int rowIndex) {
        return new RunResult(Status.THROWS, List.of(), exception.getClass().getName(), source, rowIndex,
                exception.getMessage(), 0, 0);
    }

    /** A run that ended before the code ran. */
    static RunResult failed(Status status, String message) {
        return new RunResult(status, List.of(), null, null, -1, message, 0, 0);
    }

    /** A compile error at the given location. */
    static RunResult rejected(String message, int line, int column) {
        return new RunResult(Status.REJECTED, List.of(), null, null, -1, message, line, column);
    }

    /** Returns a short hash of the rows, to compare results in reports. */
    public String digest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String row : rows) {
                md.update(row.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 10);
            }
            return HexFormat.of().formatHex(md.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tells whether two results are the same, ignoring messages. */
    public boolean sameAs(RunResult other) {
        return status == other.status && rows.equals(other.rows) && Objects.equals(exceptionClass,
                other.exceptionClass) && Objects.equals(source, other.source) && rowIndex == other.rowIndex;
    }

    @Override
    public String toString() {
        return switch (status) {
            case OK -> "OK " + rows.size() + " rows";
            case THROWS -> "THROWS " + exceptionClass + " at " + source + " row " + rowIndex + " (" + message + ")";
            case REJECTED -> "REJECTED at " + line + ":" + column + ": " + message;
            default -> status + ": " + message;
        };
    }

    /**
     * Returns the canonical form of a row: per value its type and its exact
     * representation; {@code float} and {@code double} bitwise, with all NaNs
     * as one; {@code BigDecimal} with its unscaled value and its scale.
     */
    static String canonical(Object[] row) {
        StringBuilder sb = new StringBuilder();
        for (Object v : row) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            switch (v) {
                case null -> sb.append("null");
                case Float f -> sb.append("F:").append(Float.isNaN(f) ? "NaN"
                        : Integer.toHexString(Float.floatToRawIntBits(f)));
                case Double d -> sb.append("D:").append(Double.isNaN(d) ? "NaN"
                        : Long.toHexString(Double.doubleToRawLongBits(d)));
                case BigDecimal b -> sb.append("M:").append(b.unscaledValue()).append('e').append(b.scale());
                case String s -> sb.append("T").append(s.length()).append(':').append(s);
                default -> sb.append(v.getClass().getSimpleName()).append(':').append(v);
            }
        }
        return sb.toString();
    }
}
