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

import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.LanguageFeature.Behavior;
import io.github.janinolts.qsim.plan.Plans;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What a unit expects of each backend. A unit of the profile {@code STRICT}
 * expects the result of the interpreter from every backend. A unit with a
 * language feature expects what the catalog of features says, for the
 * versions of the baseline and the candidate: a feature that a version of
 * Janino supports only since a later version is expected to be rejected. A
 * negative program is expected to be rejected by every backend. The
 * expectation of the candidate in its compliance mode ({@code JLTS-C})
 * follows the catalog as well: like javac for a feature that the compliance
 * mode of the candidate's version corrects, otherwise like the candidate.
 *
 * @param javac the behavior of javac
 * @param baseline the behavior of the baseline
 * @param candidate the behavior of the candidate
 * @param compliance the behavior of the candidate in its compliance mode
 * @param reason the IDs of the features or the mutation, e.g. {@code S-01} or
 *        {@code negative:UNKNOWN_NAME}; {@code null} for a unit of the profile
 *        {@code STRICT}
 */
public record Expectation(Behavior javac, Behavior baseline, Behavior candidate, Behavior compliance,
        String reason) {

    /** The expectation of a unit of the profile {@code STRICT}: every backend computes the reference. */
    public static final Expectation STRICT = new Expectation(Behavior.JAVAC, Behavior.JAVAC, Behavior.JAVAC, Behavior.JAVAC,
            null);

    public Expectation {
        Objects.requireNonNull(javac, "javac");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(compliance, "compliance");
    }

    /** Returns the expectation of a unit for the given versions of the baseline and the candidate. */
    public static Expectation of(UnitCase unit, String baselineVersion, String candidateVersion) {
        if (unit.mutation() != null) {
            return new Expectation(Behavior.REJECT, Behavior.REJECT, Behavior.REJECT, Behavior.REJECT,
                    "negative:" + unit.mutation());
        }
        List<LanguageFeature> features = Plans.features(unit.plan());
        if (features.isEmpty()) {
            return STRICT;
        }
        Behavior javac = Behavior.JAVAC;
        Behavior baseline = Behavior.JAVAC;
        Behavior candidate = Behavior.JAVAC;
        Behavior compliance = Behavior.JAVAC;
        for (LanguageFeature f : features) {
            javac = combine(javac, f.javacBehavior());
            baseline = combine(baseline, f.janinoBehavior(baselineVersion));
            candidate = combine(candidate, f.janinoBehavior(candidateVersion));
            compliance = combine(compliance, f.complianceBehavior(candidateVersion));
        }
        return new Expectation(javac, baseline, candidate, compliance,
                features.stream().map(LanguageFeature::id).distinct().collect(Collectors.joining("+")));
    }

    /** A rejection decides; otherwise the result of Janino wins over that of javac. */
    private static Behavior combine(Behavior a, Behavior b) {
        if (a == Behavior.REJECT || b == Behavior.REJECT) {
            return Behavior.REJECT;
        }
        return a == Behavior.JANINO || b == Behavior.JANINO ? Behavior.JANINO : Behavior.JAVAC;
    }

    /** Tells whether this is the expectation of a unit of the profile {@code STRICT}. */
    public boolean isStrict() {
        return reason == null;
    }

    /** Tells whether a backend is expected to compute the result of Janino. */
    public boolean needsJaninoSemantics() {
        return javac == Behavior.JANINO || baseline == Behavior.JANINO || candidate == Behavior.JANINO
                || compliance == Behavior.JANINO;
    }
}
