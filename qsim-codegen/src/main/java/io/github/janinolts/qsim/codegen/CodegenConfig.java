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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The settings of the generator.
 *
 * @param style the code style
 * @param profile the language profile
 * @param ansi whether overflows, division by zero and invalid casts throw;
 *        must match the interpreter that computes the reference result
 * @param comments whether the generated code contains comments
 * @param seed the seed of the plan and the data, recorded in the metadata
 * @param splitThreshold the length of source text from which the code of a
 *        projection is split into helper methods, 0 for never; 1024 by default,
 *        as in Spark
 * @param tryNesting the depth of the nested {@code try}/{@code catch}/
 *        {@code finally} statements around each projected column, 0 for none
 * @param inlineStrings whether {@code UPPER} and {@code LIKE} are loops over
 *        {@code char} in the generated code instead of calls of the helper
 *        functions
 * @param reuseSubexpressions whether a subexpression that occurs again where
 *        its first value is still in scope reuses that value instead of
 *        computing it again
 * @param constantsAsReferences whether literals are read from the references
 *        of the unit instead of being written inline; the style
 *        {@code EXPRESSION} has no references and ignores it
 * @param tryJumps whether the nested {@code try} statements of
 *        {@code tryNesting} are left by a {@code break} when the column is
 *        {@code NULL}, and each {@code finally} block holds a {@code try}
 *        statement of its own: the cases whose code Janino generates
 *        differently since 3.1.14; without nesting it has no effect
 */
public record CodegenConfig(Style style, Profile profile, boolean ansi, boolean comments, long seed,
        int splitThreshold, int tryNesting, boolean inlineStrings, boolean reuseSubexpressions,
        boolean constantsAsReferences, boolean tryJumps) {

    /** The default split threshold: 1 KB of source text. */
    public static final int DEFAULT_SPLIT_THRESHOLD = 1024;

    public CodegenConfig {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(profile, "profile");
        if (splitThreshold < 0 || tryNesting < 0 || tryNesting > 8) {
            throw new IllegalArgumentException("splitThreshold " + splitThreshold + ", tryNesting " + tryNesting);
        }
    }

    /** Returns a configuration for the profile {@code STRICT}, without comments, with the default split threshold. */
    public static CodegenConfig of(Style style, boolean ansi, long seed) {
        return new CodegenConfig(style, Profile.STRICT, ansi, false, seed, DEFAULT_SPLIT_THRESHOLD, 0, false, false,
                false, false);
    }

    public CodegenConfig withProfile(Profile p) {
        return new CodegenConfig(style, p, ansi, comments, seed, splitThreshold, tryNesting, inlineStrings,
                reuseSubexpressions, constantsAsReferences, tryJumps);
    }

    public CodegenConfig withAnsi(boolean a) {
        return new CodegenConfig(style, profile, a, comments, seed, splitThreshold, tryNesting, inlineStrings,
                reuseSubexpressions, constantsAsReferences, tryJumps);
    }

    public CodegenConfig withComments(boolean c) {
        return new CodegenConfig(style, profile, ansi, c, seed, splitThreshold, tryNesting, inlineStrings,
                reuseSubexpressions, constantsAsReferences, tryJumps);
    }

    public CodegenConfig withSplitThreshold(int threshold) {
        return new CodegenConfig(style, profile, ansi, comments, seed, threshold, tryNesting, inlineStrings,
                reuseSubexpressions, constantsAsReferences, tryJumps);
    }

    public CodegenConfig withTryNesting(int depth) {
        return new CodegenConfig(style, profile, ansi, comments, seed, splitThreshold, depth, inlineStrings,
                reuseSubexpressions, constantsAsReferences, tryJumps);
    }

    public CodegenConfig withInlineStrings(boolean inline) {
        return new CodegenConfig(style, profile, ansi, comments, seed, splitThreshold, tryNesting, inline,
                reuseSubexpressions, constantsAsReferences, tryJumps);
    }

    public CodegenConfig withReuseSubexpressions(boolean reuse) {
        return new CodegenConfig(style, profile, ansi, comments, seed, splitThreshold, tryNesting, inlineStrings,
                reuse, constantsAsReferences, tryJumps);
    }

    public CodegenConfig withTryJumps(boolean jumps) {
        return new CodegenConfig(style, profile, ansi, comments, seed, splitThreshold, tryNesting, inlineStrings,
                reuseSubexpressions, constantsAsReferences, jumps);
    }

    public CodegenConfig withConstantsAsReferences(boolean references) {
        return new CodegenConfig(style, profile, ansi, comments, seed, splitThreshold, tryNesting, inlineStrings,
                reuseSubexpressions, references, tryJumps);
    }

    /**
     * Returns a short hash of the settings that shape the code (not the seed),
     * for the metadata of a unit and the keys of the ledger.
     */
    public String hash() {
        // The settings added later only take part when they are on, so that the hashes of older units stay.
        String text = style + "|" + profile + "|" + ansi + "|" + comments + "|" + splitThreshold + "|" + tryNesting
                + "|" + inlineStrings + (reuseSubexpressions ? "|reuse" : "") + (constantsAsReferences ? "|refs" : "")
                + (tryJumps ? "|jumps" : "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
