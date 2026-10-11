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

import io.github.janinolts.qsim.codegen.CodegenConfig;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * A metamorphic variant: the same plan, translated with other settings of the
 * generator. Every backend must give the same result for every variant; the
 * differential comparison checks each variant against the reference, and a
 * campaign also compares the variants of a seed with each other. Fused code
 * and code operator by operator are the styles {@code WHOLE_STAGE} and
 * {@code OPERATOR_CLASSES}, which every campaign runs.
 */
public enum Variant {

    /** The default settings. */
    BASE(c -> c),

    /** No method splitting. */
    NO_SPLIT(c -> c.withSplitThreshold(0)),

    /** Methods split from 256 characters of source text on, so that small plans are split, too. */
    SPLIT_SMALL(c -> c.withSplitThreshold(256)),

    /** Methods split from 8 KB of source text on. */
    SPLIT_8K(c -> c.withSplitThreshold(8192)),

    /** Common subexpressions computed once. */
    REUSE(c -> c.withReuseSubexpressions(true)),

    /** Constants read from the references instead of written inline. */
    REFERENCES(c -> c.withConstantsAsReferences(true)),

    /** Comments in the generated code. */
    COMMENTS(c -> c.withComments(true)),

    /** Every projected column in two nested {@code try} statements. */
    TRY_NESTING(c -> c.withTryNesting(2)),

    /** {@code UPPER} and {@code LIKE} as loops over {@code char}. */
    INLINE_STRINGS(c -> c.withInlineStrings(true)),

    /**
     * Every projected column in two nested {@code try} statements that a
     * {@code break} leaves, with a {@code try} statement in each
     * {@code finally} block: the code that Janino generates differently since
     * 3.1.14. Only on request ({@code fuzz --try-jumps}), so that the
     * variants that the seeds of a campaign get in turn stay the same.
     */
    TRY_JUMPS(c -> c.withTryNesting(2).withTryJumps(true), true);

    private final UnaryOperator<CodegenConfig> change;
    private final boolean onRequest;

    Variant(UnaryOperator<CodegenConfig> change) {
        this(change, false);
    }

    Variant(UnaryOperator<CodegenConfig> change, boolean onRequest) {
        this.change = change;
        this.onRequest = onRequest;
    }

    /** Tells whether a campaign runs this variant only on request, never in turn or with all variants. */
    public boolean onRequest() {
        return onRequest;
    }

    /** Returns the variants besides {@link #BASE} that campaigns run in turn or all together: those not on request. */
    public static List<Variant> rotated() {
        return Stream.of(values()).filter(v -> v != BASE && !v.onRequest).toList();
    }

    /** Applies the settings of the variant to a configuration. */
    public CodegenConfig apply(CodegenConfig config) {
        return change.apply(config);
    }
}
