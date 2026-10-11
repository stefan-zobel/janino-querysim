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

import io.github.janinolts.qsim.codegen.NegativePrograms;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A campaign: a range of seeds times styles times profiles, each seed with
 * its metamorphic variants and, optionally, a negative program per style.
 * Besides the differential comparison of every unit, the variants of a seed
 * are compared with each other: every backend must give the same result for
 * all of them.
 */
public final class Campaign {

    /**
     * Which metamorphic variants of a seed run besides {@link Variant#BASE},
     * among those of {@link Variant#rotated()}; variants on request run only
     * if the settings name them.
     */
    public enum Variants {
        /** None. */
        NONE,
        /** One per seed and style, in turn, so that a campaign covers all of them. */
        ONE,
        /** All. */
        ALL
    }

    /**
     * The settings of a campaign.
     *
     * @param firstSeed the first seed
     * @param lastSeed the last seed, inclusive
     * @param styles the styles
     * @param profiles the profiles
     * @param variants the variants besides {@code BASE}
     * @param negatives whether each seed also yields a negative program per
     *        class style, in the profile {@code STRICT}, with the mutations in
     *        turn
     * @param requested the variants {@linkplain Variant#onRequest() on
     *        request} that run for every seed, style and profile besides the
     *        others
     */
    public record Settings(long firstSeed, long lastSeed, List<Style> styles, List<Profile> profiles,
            Variants variants, boolean negatives, List<Variant> requested) {

        public Settings {
            styles = List.copyOf(styles);
            profiles = List.copyOf(profiles);
            Objects.requireNonNull(variants, "variants");
            requested = List.copyOf(requested);
            if (lastSeed < firstSeed || styles.isEmpty() || profiles.isEmpty()) {
                throw new IllegalArgumentException("An empty campaign");
            }
            if (requested.stream().anyMatch(v -> !v.onRequest())) {
                throw new IllegalArgumentException("Not a variant on request: " + requested);
            }
        }

        /** Settings without variants on request. */
        public Settings(long firstSeed, long lastSeed, List<Style> styles, List<Profile> profiles, Variants variants,
                boolean negatives) {
            this(firstSeed, lastSeed, styles, profiles, variants, negatives, List.of());
        }

        /** Returns the same settings for another range of seeds. */
        public Settings withSeeds(long first, long last) {
            return new Settings(first, last, styles, profiles, variants, negatives, requested);
        }
    }

    /**
     * The result of a campaign.
     *
     * @param units the units that exist, in the order of the run
     * @param outcomes their outcomes
     * @param inconsistencies the descriptions of the backends that gave
     *        different results for variants of the same seed
     */
    public record Result(List<RandomUnit> units, List<UnitOutcome> outcomes, List<String> inconsistencies) {
        public Result {
            units = List.copyOf(units);
            outcomes = List.copyOf(outcomes);
            inconsistencies = List.copyOf(inconsistencies);
        }
    }

    private Campaign() {
    }

    /** Returns the units of a campaign, seed by seed. */
    public static List<RandomUnit> units(Settings s) {
        List<RandomUnit> units = new ArrayList<>();
        List<Variant> rotated = Variant.rotated();
        for (long seed = s.firstSeed(); seed <= s.lastSeed(); seed++) {
            for (Profile profile : s.profiles()) {
                for (Style style : s.styles()) {
                    units.add(new RandomUnit(seed, style, profile, Variant.BASE, null));
                    switch (s.variants()) {
                        case NONE -> {
                        }
                        case ONE -> units.add(new RandomUnit(seed, style, profile,
                                rotated.get((int) Math.floorMod(seed * 7 + style.ordinal() + 3L * profile.ordinal(),
                                        (long) rotated.size())), null));
                        case ALL -> {
                            for (Variant v : rotated) {
                                units.add(new RandomUnit(seed, style, profile, v, null));
                            }
                        }
                    }
                    for (Variant v : s.requested()) {
                        units.add(new RandomUnit(seed, style, profile, v, null));
                    }
                }
            }
            if (s.negatives()) {
                NegativePrograms.Mutation[] mutations = NegativePrograms.Mutation.values();
                for (Style style : s.styles()) {
                    if (style != Style.EXPRESSION) {
                        units.add(new RandomUnit(seed, style, Profile.STRICT, Variant.BASE,
                                mutations[(int) Math.floorMod(seed + style.ordinal(), (long) mutations.length)]));
                    }
                }
            }
        }
        return units;
    }

    /** Runs the units of a campaign in several threads; units that do not exist are left out. */
    public static Result run(DifferentialRunner runner, RandomPlans generator, List<RandomUnit> units, int threads) {
        List<Supplier<UnitCase>> suppliers = new ArrayList<>();
        for (RandomUnit u : units) {
            suppliers.add(() -> u.build(generator));
        }
        List<UnitOutcome> all = CorrectnessRun.runParallel(runner, suppliers, threads);
        List<RandomUnit> ran = new ArrayList<>();
        List<UnitOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            if (all.get(i) != null) {
                ran.add(units.get(i));
                outcomes.add(all.get(i));
            }
        }
        return new Result(ran, outcomes, inconsistencies(ran, outcomes));
    }

    /**
     * Compares the variants of each seed, style and profile: per backend, the
     * results must be equivalent.
     */
    static List<String> inconsistencies(List<RandomUnit> units, List<UnitOutcome> outcomes) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            RandomUnit u = units.get(i);
            if (u.mutation() == null) {
                groups.computeIfAbsent(u.seed() + " " + u.style() + " " + u.profile(), k -> new ArrayList<>()).add(i);
            }
        }
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
            List<Integer> members = g.getValue();
            if (members.size() < 2) {
                continue;
            }
            UnitOutcome first = outcomes.get(members.get(0));
            for (UnitOutcome.BackendRun run : first.runs()) {
                for (int m = 1; m < members.size(); m++) {
                    RunResult other = outcomes.get(members.get(m)).result(run.id());
                    if (other != null && !DifferentialRunner.equivalent(run.result(), other)) {
                        result.add(g.getKey() + ": " + run.id() + " gives " + run.result() + " for "
                                + units.get(members.get(0)).variant() + ", but " + other + " for "
                                + units.get(members.get(m)).variant());
                    }
                }
            }
        }
        return result;
    }
}
