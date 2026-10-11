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

import io.github.janinolts.qsim.backend.UnitMetadata;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.PlanIds;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Plans;
import io.github.janinolts.qsim.runtime.BufferedRowIterator;
import io.github.janinolts.qsim.runtime.ColumnBatch;
import io.github.janinolts.qsim.runtime.Conversions;
import io.github.janinolts.qsim.runtime.DecimalFunctions;
import io.github.janinolts.qsim.runtime.Keys;
import io.github.janinolts.qsim.runtime.RowKey;
import io.github.janinolts.qsim.runtime.StringFunctions;

import java.util.List;

/**
 * Translates a plan into generated code.
 * <p>
 * The generator is deterministic: the same plan and configuration give the
 * same source text, byte for byte. It uses no clock, no hash order and no
 * global counter.
 */
public final class Generator {

    /** The package of every generated class. */
    static final String PACKAGE = "qsim.generated";

    /**
     * The imports of every unit, as engines set default imports; generated
     * code uses these classes by their simple names.
     */
    static final String[] IMPORTS = {
        "java.io.IOException",
        "java.math.BigDecimal",
        "java.math.RoundingMode",
        BufferedRowIterator.class.getName(),
        ColumnBatch.class.getName(),
        Conversions.class.getName(),
        DecimalFunctions.class.getName(),
        Keys.class.getName(),
        RowKey.class.getName(),
        StringFunctions.class.getName(),
    };

    private Generator() {
    }

    /**
     * Generates the code of a plan.
     *
     * @throws UnsupportedOperationException for a style that does not fit
     *         the plan
     * @throws IllegalArgumentException if the plan contains a language feature
     *         of another profile than that of the configuration
     */
    public static GeneratedQuery generate(PlanNode plan, CodegenConfig config) {
        if (!supports(config.style(), plan)) {
            throw new UnsupportedOperationException("The style " + config.style() + " does not fit the plan");
        }
        for (LanguageFeature f : Plans.features(plan)) {
            if (!f.profile().equals(config.profile().name())) {
                throw new IllegalArgumentException("The feature " + f + " belongs to the profile " + f.profile()
                        + ", not to " + config.profile());
            }
        }
        return switch (config.style()) {
            case WHOLE_STAGE -> WholeStageCodegen.generate(plan, config);
            case EXPRESSION -> ExpressionStyleCodegen.generate(plan, config);
            case OPERATOR_CLASSES -> OperatorClassesCodegen.generate(plan, config);
            case ENUMERABLE -> EnumerableCodegen.generate(plan, config);
        };
    }

    /**
     * Tells whether a style fits a plan: every class style fits every plan;
     * the style {@code EXPRESSION} only filters and projections over one scan.
     */
    public static boolean supports(Style style, PlanNode plan) {
        return style != Style.EXPRESSION || ExpressionStyleCodegen.supports(plan);
    }

    /** Returns the index of a scan among the scans of a plan, by identity. */
    static int scanIndex(PlanIds ids, PlanNode.Scan scan) {
        List<PlanNode.Scan> scans = ids.scans();
        for (int k = 0; k < scans.size(); k++) {
            if (scans.get(k) == scan) {
                return k;
            }
        }
        throw new IllegalArgumentException("Not a scan of the plan");
    }

    static UnitMetadata metadata(CodegenConfig config, CodegenContext ctx) {
        return new UnitMetadata(config.seed(), config.style().name(), config.profile().name(), config.hash(),
                ctx.featureTags());
    }
}
