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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.codegen.NegativePrograms;
import io.github.janinolts.qsim.codegen.Profile;
import io.github.janinolts.qsim.codegen.Style;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.RandomPlans;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * A small campaign over all profiles, with the variants and the negative
 * programs, as a test of qsim itself: javac computes the result of the
 * interpreter, the variants of a seed agree, every feature of the profiles
 * {@code LENIENT} and {@code MODERN} and every mutation shows the differences
 * that it should. Differences of Janino are findings, not failures of this
 * test; they are in the report under {@code target/qsim-reports}, and the
 * campaigns of the command {@code fuzz} fail on them.
 */
class CampaignTest {

    @Test
    void aSmallCampaignOverAllProfiles() {
        List<CompilerBackend> all = TestBackends.all();
        DifferentialRunner runner = new DifferentialRunner(new DifferentialRunner.Backends(all.get(0), all.get(1),
                all.get(2)), CampaignTest.class.getClassLoader());
        Campaign.Settings settings = new Campaign.Settings(1, Long.getLong("qsim.test.seeds", 24),
                Workloads.STYLES, List.of(Profile.values()), Campaign.Variants.ONE, true);
        Campaign.Result result = Campaign.run(runner, RandomPlans.withDefaults(), Campaign.units(settings),
                Runtime.getRuntime().availableProcessors());
        Ledger ledger = Ledger.forVersion(runner.backends().candidate().version());
        CorrectnessRun run = CorrectnessRun.of("campaign", runner.backends(), ledger, result.outcomes())
                .with(result.inconsistencies(), Map.of());
        Path dir = Path.of("target", "qsim-reports",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-campaign");
        CorrectnessReport.write(run, dir);

        assertTrue(run.differences().stream().noneMatch(d -> d.pair() == Difference.Pair.INTERP_JAVAC),
                () -> CorrectnessReport.markdown(run));
        assertEquals(List.of(), result.inconsistencies());

        Set<String> reasons = new TreeSet<>();
        for (Difference d : run.expected()) {
            reasons.add(d.detail().substring(0, d.detail().indexOf(':')));
        }
        for (LanguageFeature f : LanguageFeature.values()) {
            if (!f.profile().equals("PROBE")) {
                assertTrue(reasons.contains(f.id()), () -> f + " not in " + reasons);
            }
        }
        for (NegativePrograms.Mutation m : NegativePrograms.Mutation.values()) {
            assertTrue(run.expected().stream().anyMatch(d -> d.detail().startsWith("negative:" + m + ":")),
                    () -> m + " is not among the expected differences");
        }
        assertTrue(result.units().stream().anyMatch(u -> u.style() == Style.EXPRESSION));
    }

    /**
     * The variants that seeds get in turn are those before {@code TRY_JUMPS}
     * was added, in the same order, so that a campaign generates the same
     * units as before; {@code TRY_JUMPS} runs only on request.
     */
    @Test
    void variantsOnRequestRunOnlyWhenRequested() {
        assertEquals(List.of(Variant.NO_SPLIT, Variant.SPLIT_SMALL, Variant.SPLIT_8K, Variant.REUSE,
                Variant.REFERENCES, Variant.COMMENTS, Variant.TRY_NESTING, Variant.INLINE_STRINGS), Variant.rotated());
        for (Campaign.Variants v : Campaign.Variants.values()) {
            Campaign.Settings plain = new Campaign.Settings(1, 50, Workloads.STYLES, List.of(Profile.values()), v,
                    false);
            assertTrue(Campaign.units(plain).stream().noneMatch(u -> u.variant() == Variant.TRY_JUMPS), v::name);
        }
        Campaign.Settings requested = new Campaign.Settings(1, 3, List.of(Style.WHOLE_STAGE), List.of(Profile.STRICT),
                Campaign.Variants.ONE, false, List.of(Variant.TRY_JUMPS));
        List<RandomUnit> units = Campaign.units(requested);
        assertEquals(3 * 3, units.size());
        assertEquals(3, units.stream().filter(u -> u.variant() == Variant.TRY_JUMPS).count());
    }

    /**
     * Random units in the variant {@code TRY_JUMPS} agree with the other
     * variants of their seed; the class files that the candidate generates
     * differently since 3.1.14 are explained by the ledger of its line.
     */
    @Test
    void tryJumpsAgreeAndAreExplained() {
        List<CompilerBackend> all = TestBackends.all();
        DifferentialRunner runner = new DifferentialRunner(new DifferentialRunner.Backends(all.get(0), all.get(1),
                all.get(2)), CampaignTest.class.getClassLoader());
        Campaign.Settings settings = new Campaign.Settings(1, 8, Workloads.STYLES, List.of(Profile.STRICT),
                Campaign.Variants.NONE, false, List.of(Variant.TRY_JUMPS));
        Campaign.Result result = Campaign.run(runner, RandomPlans.withDefaults(), Campaign.units(settings),
                Runtime.getRuntime().availableProcessors());
        Ledger ledger = Ledger.forVersion(runner.backends().candidate().version());
        CorrectnessRun run = CorrectnessRun.of("campaign", runner.backends(), ledger, result.outcomes());

        assertEquals(List.of(), result.inconsistencies());
        assertTrue(run.reconciliation().unexplained().isEmpty(), () -> CorrectnessReport.markdown(run));
        assertTrue(result.outcomes().stream().anyMatch(o -> o.featureTags().contains("try-jump")));
    }
}
