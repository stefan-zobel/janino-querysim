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
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.codegen.Generator;
import io.github.janinolts.qsim.codegen.NegativePrograms;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Plans;
import io.github.janinolts.qsim.plan.Table;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

/**
 * One unit of the differential comparison: a plan with its data and the
 * configuration of the generator, and for a negative program the mutation
 * that makes the generated code invalid.
 *
 * @param key the key of the unit
 * @param plan the plan
 * @param tables the tables, by name
 * @param config the configuration of the generator
 * @param mutation the mutation of a negative program, or {@code null}
 */
public record UnitCase(UnitKey key, PlanNode plan, Map<String, Table> tables, CodegenConfig config,
        NegativePrograms.Mutation mutation) {

    public UnitCase {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(plan, "plan");
        tables = Map.copyOf(tables);
        Objects.requireNonNull(config, "config");
    }

    /** Creates a unit that is not a negative program. */
    public UnitCase(UnitKey key, PlanNode plan, Map<String, Table> tables, CodegenConfig config) {
        this(key, plan, tables, config, null);
    }

    /**
     * Creates a unit whose key consists of the workload, the style, the
     * profile with the IDs of the features of the plan, e.g.
     * {@code PROBE:S-01}, and the hash of the configuration.
     */
    public static UnitCase of(String workload, PlanNode plan, Map<String, Table> tables, CodegenConfig config) {
        return of(workload, plan, tables, config, null);
    }

    /** Creates a unit as {@link #of(String, PlanNode, Map, CodegenConfig)}, possibly a negative program. */
    public static UnitCase of(String workload, PlanNode plan, Map<String, Table> tables, CodegenConfig config,
            NegativePrograms.Mutation mutation) {
        return new UnitCase(new UnitKey(workload, config.style().name(), profileLabel(config.profile(), plan),
                config.hash()), plan, tables, config, mutation);
    }

    /** Returns the profile part of a unit key: the profile, followed by the IDs of the features of the plan. */
    static String profileLabel(Profile profile, PlanNode plan) {
        List<LanguageFeature> features = Plans.features(plan);
        if (features.isEmpty()) {
            return profile.name();
        }
        return profile.name() + ":" + features.stream().map(LanguageFeature::id).distinct()
                .collect(Collectors.joining("+"));
    }

    /** Returns a copy with another plan, other tables and another configuration, under the same key. */
    public UnitCase with(PlanNode p, Map<String, Table> t, CodegenConfig c) {
        return new UnitCase(key, p, t, c, mutation);
    }

    /**
     * Generates the code of the unit: the code of the plan, with the mutation
     * of a negative program applied at a place chosen by the seed.
     */
    public GeneratedQuery generate() {
        GeneratedQuery query = Generator.generate(plan, config);
        if (mutation == null) {
            return query;
        }
        return NegativePrograms.apply(query, mutation, new SplittableRandom(config.seed() * 0x2545F4914F6CDD1DL + 1));
    }
}
