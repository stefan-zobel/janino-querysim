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
import io.github.janinolts.qsim.codegen.Generator;
import io.github.janinolts.qsim.codegen.NegativePrograms;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * A unit of a campaign, described by what determines it completely: the seed
 * (the plan, the data and the decisions of the generator, among them the
 * ANSI mode and, outside of the profile {@code STRICT}, the language feature),
 * the style, the profile, the metamorphic variant and, for a negative
 * program, the mutation. Its text form, e.g.
 * {@code 4711 WHOLE_STAGE STRICT BASE} or
 * {@code 4711 WHOLE_STAGE STRICT BASE UNKNOWN_NAME}, is a line of the
 * regression corpus.
 *
 * @param seed the seed
 * @param style the style
 * @param profile the profile
 * @param variant the variant
 * @param mutation the mutation of a negative program, or {@code null}
 */
public record RandomUnit(long seed, Style style, Profile profile, Variant variant,
        NegativePrograms.Mutation mutation) {

    /** The number of rows of each table of a random plan. */
    public static final int ROWS = 64;

    public RandomUnit {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(variant, "variant");
    }

    /** Returns the workload part of the unit key: {@code seed:<n>}, for a negative program with the mutation. */
    public String workload() {
        return "seed:" + seed + (mutation == null ? "" : ":NEG:" + mutation);
    }

    /** Tells whether the seed runs in the ANSI mode: a decision of the generator, from the seed. */
    public static boolean ansi(long seed) {
        return new SplittableRandom(seed ^ 0x6A09E667F3BCC909L).nextBoolean();
    }

    /** Returns the language feature of a seed in a profile other than {@code STRICT}: the features in turn. */
    public static LanguageFeature feature(long seed, Profile profile) {
        List<LanguageFeature> features = LanguageFeature.ofProfile(profile.name());
        return features.get((int) Math.floorMod(seed, (long) features.size()));
    }

    /** Returns the plan of a seed in a profile: the random plan, with the feature of the profile placed into it. */
    public static PlanNode plan(RandomPlans generator, RandomPlans.RandomPlan random, long seed, Profile profile) {
        if (profile == Profile.STRICT) {
            return random.plan();
        }
        return RandomPlans.withFeature(random.plan(), feature(seed, profile),
                new SplittableRandom(seed * 0x9E3779B97F4A7C15L + profile.ordinal()),
                generator.settings().expressions());
    }

    /**
     * Builds the unit, or returns {@code null} if the style does not fit the
     * plan, or if a negative program has no class unit to change.
     */
    public UnitCase build(RandomPlans generator) {
        RandomPlans.RandomPlan random = generator.generate(seed);
        PlanNode plan = plan(generator, random, seed, profile);
        if (!Generator.supports(style, plan) || (mutation != null && style == Style.EXPRESSION)) {
            return null;
        }
        CodegenConfig config = variant.apply(CodegenConfig.of(style, ansi(seed), seed).withProfile(profile));
        return UnitCase.of(workload(), plan, random.data(ROWS), config, mutation);
    }

    /** Parses the text form. */
    public static RandomUnit parse(String text) {
        String[] parts = text.strip().split("\\s+");
        if (parts.length != 4 && parts.length != 5) {
            throw new IllegalArgumentException("Not a random unit: " + text);
        }
        return new RandomUnit(Long.parseLong(parts[0]), Style.valueOf(parts[1]), Profile.valueOf(parts[2]),
                Variant.valueOf(parts[3]), parts.length == 5 ? NegativePrograms.Mutation.valueOf(parts[4]) : null);
    }

    @Override
    public String toString() {
        return seed + " " + style + " " + profile + " " + variant + (mutation == null ? "" : " " + mutation);
    }
}
