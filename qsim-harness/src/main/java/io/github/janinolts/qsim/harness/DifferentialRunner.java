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

import io.github.janinolts.qsim.backend.ApiPath;
import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.codegen.GeneratedQuery;
import io.github.janinolts.qsim.harness.Difference.Assessment;
import io.github.janinolts.qsim.harness.Difference.Kind;
import io.github.janinolts.qsim.harness.Difference.Pair;
import io.github.janinolts.qsim.harness.RunResult.Status;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.LanguageFeature.Behavior;
import io.github.janinolts.qsim.plan.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the differential comparison of one unit, in six steps:
 * <ol>
 * <li>the interpreter computes the reference result;</li>
 * <li>the generator generates the unit;</li>
 * <li>javac, the baseline and the candidate compile it, in the configuration
 * {@code as-shipped}, and the candidate in its compliance mode if the run
 * includes it;</li>
 * <li>the compiled code runs on the same data;</li>
 * <li>the baseline and the candidate compile it again with target version 8
 * ({@code normalized}), and their class files are compared;</li>
 * <li>each pair of backends is classified.</li>
 * </ol>
 * A unit with a language feature or a negative program has an
 * {@link Expectation} per backend. A difference between two backends that
 * both behave as expected follows from the profile and is reported as
 * expected; a backend that does not behave as expected is a difference that
 * the ledger must explain.
 */
public final class DifferentialRunner {

    /** The identifier of the interpreter in reports. */
    public static final String INTERPRETER = "INTERP";

    /**
     * The compiler backends of a comparison.
     *
     * @param javac javac
     * @param baseline the baseline, Janino 3.1.12 by default
     * @param candidate the candidate, janino-lts
     * @param compliance the candidate in its compliance mode, or {@code null}
     *        if the run does not include it
     */
    public record Backends(CompilerBackend javac, CompilerBackend baseline, CompilerBackend candidate,
            CompilerBackend compliance) {
        public Backends {
            Objects.requireNonNull(javac, "javac");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(candidate, "candidate");
        }

        /** The backends of a run without the compliance mode. */
        public Backends(CompilerBackend javac, CompilerBackend baseline, CompilerBackend candidate) {
            this(javac, baseline, candidate, null);
        }

        /** Returns the compiler backends in the order of the reports, the compliance mode last if present. */
        public List<CompilerBackend> all() {
            return compliance == null ? List.of(javac, baseline, candidate)
                    : List.of(javac, baseline, candidate, compliance);
        }
    }

    private final Backends backends;
    private final CompileOptions options;

    /**
     * Creates a runner.
     *
     * @param backends the backends
     * @param parent the class loader that sees {@code qsim-runtime}
     */
    public DifferentialRunner(Backends backends, ClassLoader parent) {
        this.backends = backends;
        this.options = CompileOptions.of(ApiPath.CLASS_BODY, parent);
    }

    public Backends backends() {
        return backends;
    }

    /** Returns the options of the configuration {@code as-shipped}; the API path comes from the query. */
    public CompileOptions options() {
        return options;
    }

    /** Returns the expectation of a unit for the versions of the backends of this runner. */
    public Expectation expectation(UnitCase unit) {
        return Expectation.of(unit, backends.baseline().version(), backends.candidate().version());
    }

    /** Runs the comparison of one unit. */
    public UnitOutcome run(UnitCase unit) {
        Map<String, Table> tables = unit.tables();
        RunResult interpreted = QueryRunner.interpret(unit.plan(), unit.config().ansi(), tables);
        GeneratedQuery query = unit.generate();

        List<UnitOutcome.BackendRun> runs = new ArrayList<>();
        runs.add(new UnitOutcome.BackendRun(INTERPRETER, "", interpreted, 0, 0));
        RunResult javac = run(query, backends.javac(), tables, runs);
        RunResult baseline = run(query, backends.baseline(), tables, runs);
        RunResult candidate = run(query, backends.candidate(), tables, runs);
        RunResult compliance = backends.compliance() == null ? null : run(query, backends.compliance(), tables, runs);

        CompileOptions normalized = options.withTargetVersion(8);
        Map<String, byte[]> a = QueryRunner.compile(query, backends.baseline(), normalized).classFiles();
        Map<String, byte[]> b = QueryRunner.compile(query, backends.candidate(), normalized).classFiles();
        // A unit that failed has no class files; compare only complete sets.
        ClassFileComparison classFiles = a.isEmpty() || b.isEmpty() ? null : ClassFileComparison.compare(a, b);

        Expectation expectation = expectation(unit);
        List<Difference> differences = new ArrayList<>();
        List<Difference> expected = new ArrayList<>();
        if (expectation.isStrict()) {
            strict(unit.key(), interpreted, javac, baseline, candidate, classFiles, differences);
            if (compliance != null) {
                strictCompliance(unit.key(), javac, candidate, compliance, differences);
            }
        } else {
            RunResult janino = expectation.needsJaninoSemantics()
                    ? QueryRunner.interpret(unit.plan(), unit.config().ansi(), tables, LanguageFeature.Semantics.JANINO)
                    : interpreted;
            new Expected(unit.key(), expectation, interpreted, janino, differences, expected)
                    .compare(javac, baseline, candidate, compliance, classFiles);
        }
        return new UnitOutcome(unit.key(), query.unit().metadata().featureTags(), runs, classFiles, differences,
                expected);
    }

    /** The pairs of a unit of the profile {@code STRICT}: every backend must compute the reference. */
    private static void strict(UnitKey key, RunResult interpreted, RunResult javac, RunResult baseline,
            RunResult candidate, ClassFileComparison classFiles, List<Difference> differences) {
        // The interpreter has no limits of the JVM; javac hitting one is not a defect of qsim.
        Kind k = javac.status() == Status.LIMIT ? null : classify(interpreted, javac, false);
        if (k != null) {
            differences.add(new Difference(key, Pair.INTERP_JAVAC, k, Assessment.QSIM_DEFECT,
                    "INTERP: " + interpreted + " / JAVAC: " + javac));
        }
        k = classify(javac, candidate, false);
        if (k != null) {
            Assessment assessment = equivalent(baseline, candidate) ? Assessment.DEVIATION
                    : equivalent(baseline, javac) ? Assessment.REGRESSION : Assessment.UNCLEAR;
            differences.add(new Difference(key, Pair.JAVAC_JLTS, k, assessment,
                    "JAVAC: " + javac + " / JLTS: " + candidate));
        }
        k = classify(baseline, candidate, true);
        if (k != null) {
            Assessment assessment = equivalent(candidate, javac) ? Assessment.CORRECTION
                    : equivalent(baseline, javac) ? Assessment.REGRESSION : Assessment.UNCLEAR;
            differences.add(new Difference(key, Pair.J3112_JLTS, k, assessment,
                    "J3112: " + baseline + " / JLTS: " + candidate));
        } else {
            bytes(key, classFiles, differences);
        }
    }

    /**
     * The pair {@code JAVAC:JLTS-C} of a unit of the profile {@code STRICT}:
     * the compliance mode must compute the reference as well.
     */
    private static void strictCompliance(UnitKey key, RunResult javac, RunResult candidate, RunResult compliance,
            List<Difference> differences) {
        if (javac.status() == Status.LIMIT) {
            return;
        }
        Kind k = classify(javac, compliance, false);
        if (k != null) {
            Assessment assessment = equivalent(candidate, compliance) ? Assessment.DEVIATION
                    : equivalent(candidate, javac) ? Assessment.REGRESSION : Assessment.UNCLEAR;
            differences.add(new Difference(key, Pair.JAVAC_JLTSC, k, assessment,
                    "JAVAC: " + javac + " / JLTS-C: " + compliance));
        }
    }

    private static void bytes(UnitKey key, ClassFileComparison classFiles, List<Difference> differences) {
        if (classFiles != null && classFiles.level() != ClassFileComparison.Level.L0) {
            differences.add(new Difference(key, Pair.J3112_JLTS, Kind.BYTES, Assessment.CHANGED_CODE,
                    classFiles.level() + ": " + String.join("; ", classFiles.details())));
        }
    }

    /** The comparison of a unit with a language feature or a negative program, against its expectation. */
    private static final class Expected {

        private final UnitKey key;
        private final Expectation expectation;
        private final RunResult interpreted;
        private final RunResult janino;
        private final List<Difference> differences;
        private final List<Difference> expected;

        Expected(UnitKey key, Expectation expectation, RunResult interpreted, RunResult janino,
                List<Difference> differences, List<Difference> expected) {
            this.key = key;
            this.expectation = expectation;
            this.interpreted = interpreted;
            this.janino = janino;
            this.differences = differences;
            this.expected = expected;
        }

        /** Compares the backends; {@code compliance} is {@code null} if the run does not include it. */
        void compare(RunResult javac, RunResult baseline, RunResult candidate, RunResult compliance,
                ClassFileComparison classFiles) {
            RunResult eJavac = result(expectation.javac());
            RunResult eBaseline = result(expectation.baseline());
            RunResult eCandidate = result(expectation.candidate());
            boolean javacOk = conforms(javac, eJavac);
            boolean baselineOk = conforms(baseline, eBaseline);
            boolean candidateOk = conforms(candidate, eCandidate);

            pair(Pair.INTERP_JAVAC, "INTERP", interpreted, true, "JAVAC", javac, javacOk,
                    classify(interpreted, eJavac, false), false, Assessment.QSIM_DEFECT);
            pair(Pair.JAVAC_JLTS, "JAVAC", javac, javacOk, "JLTS", candidate, candidateOk,
                    classify(eJavac, eCandidate, false), false, Assessment.UNCLEAR);
            pair(Pair.J3112_JLTS, "J3112", baseline, baselineOk, "JLTS", candidate, candidateOk,
                    classify(eBaseline, eCandidate, false), true, Assessment.UNCLEAR);

            if (!javacOk) {
                differences.add(new Difference(key, Pair.INTERP_JAVAC, kind(eJavac, javac), Assessment.QSIM_DEFECT,
                        notAsExpected("JAVAC", expectation.javac(), eJavac, javac)));
            }
            if (!baselineOk) {
                differences.add(new Difference(key, Pair.J3112_JLTS, kind(eBaseline, baseline),
                        Assessment.NOT_AS_EXPECTED, notAsExpected("J3112", expectation.baseline(), eBaseline,
                                baseline)));
            }
            if (!candidateOk) {
                differences.add(new Difference(key, baselineOk ? Pair.J3112_JLTS : Pair.JAVAC_JLTS,
                        kind(eCandidate, candidate), Assessment.NOT_AS_EXPECTED, notAsExpected("JLTS",
                                expectation.candidate(), eCandidate, candidate)));
            }
            if (baselineOk && candidateOk && classify(baseline, candidate, true) == null) {
                bytes(key, classFiles, differences);
            }
            if (compliance != null) {
                RunResult eCompliance = result(expectation.compliance());
                boolean complianceOk = conforms(compliance, eCompliance);
                pair(Pair.JAVAC_JLTSC, "JAVAC", javac, javacOk, "JLTS-C", compliance, complianceOk,
                        classify(eJavac, eCompliance, false), false, Assessment.UNCLEAR);
                if (!complianceOk) {
                    differences.add(new Difference(key, Pair.JAVAC_JLTSC, kind(eCompliance, compliance),
                            Assessment.NOT_AS_EXPECTED, notAsExpected(ComplianceBackend.ID, expectation.compliance(),
                                    eCompliance, compliance)));
                }
            }
        }

        /**
         * Classifies a pair whose backends both behave as expected: a
         * difference of the expected kind is expected, any other is not.
         */
        private void pair(Pair pair, String idA, RunResult a, boolean okA, String idB, RunResult b, boolean okB,
                Kind expectedKind, boolean compareLocation, Assessment unexpected) {
            if (!okA || !okB) {
                return;
            }
            Kind k = classify(a, b, compareLocation);
            if (k == null) {
                return;
            }
            String detail = idA + ": " + a + " / " + idB + ": " + b;
            if (k == expectedKind) {
                expected.add(new Difference(key, pair, k, Assessment.EXPECTED, expectation.reason() + ": " + detail));
            } else {
                differences.add(new Difference(key, pair, k, unexpected, expectation.reason() + ": " + detail));
            }
        }

        /** Returns the expected result of a behavior: the result of the interpreter, or a rejection. */
        private RunResult result(Behavior behavior) {
            return switch (behavior) {
                case JAVAC -> interpreted;
                case JANINO -> janino;
                case REJECT -> RunResult.rejected("expected to be rejected", 0, 0);
            };
        }

        private String notAsExpected(String id, Behavior behavior, RunResult expectedResult, RunResult actual) {
            String wanted = behavior == Behavior.REJECT ? "REJECTED" : expectedResult.toString();
            return expectation.reason() + ": " + id + " expected " + behavior + " (" + wanted + "), got " + actual;
        }
    }

    /** Tells whether a backend behaves as expected: rejected if a rejection is expected, else the expected result. */
    static boolean conforms(RunResult actual, RunResult expected) {
        if (expected.status() == Status.REJECTED) {
            return actual.status() == Status.REJECTED;
        }
        return equivalent(actual, expected);
    }

    private static Kind kind(RunResult expected, RunResult actual) {
        Kind k = classify(expected, actual, false);
        return k == null ? Kind.WRONG : k;
    }

    private RunResult run(GeneratedQuery query, CompilerBackend backend,
            Map<String, Table> tables, List<UnitOutcome.BackendRun> runs) {
        CompiledQuery compiled = QueryRunner.compile(query, backend, options);
        RunResult result = QueryRunner.execute(query, compiled, tables);
        Map<String, byte[]> classFiles = compiled.classFiles();
        int bytes = classFiles.values().stream().mapToInt(c -> c.length).sum();
        runs.add(new UnitOutcome.BackendRun(backend.id(), backend.version(), result, bytes, classFiles.size()));
        return result;
    }

    /**
     * Tells whether two backends behave alike: the same result if both ran,
     * otherwise the same status (compile errors are not compared by location,
     * which depends on the rendering of the unit).
     */
    static boolean equivalent(RunResult a, RunResult b) {
        return a.status().ran() && b.status().ran() ? a.sameAs(b) : a.status() == b.status();
    }

    /**
     * Classifies the difference between two results, or returns {@code null}
     * if there is none.
     *
     * @param compareLocation whether two compile errors must have the same
     *        location and the same message (the latter compared with
     *        {@code startsWith}, because {@code master} appends explanations)
     */
    static Kind classify(RunResult a, RunResult b, boolean compareLocation) {
        if (a.status() == Status.UNSUPPORTED || b.status() == Status.UNSUPPORTED) {
            return null;
        }
        if (a.status().ran() && b.status().ran()) {
            return a.sameAs(b) ? null : Kind.WRONG;
        }
        if (a.status().ran() || b.status().ran()) {
            Status failure = a.status().ran() ? b.status() : a.status();
            return failure == Status.REJECTED || failure == Status.LIMIT ? Kind.REJECTED : Kind.INVALID;
        }
        if (a.status() != b.status()) {
            return isBroken(a.status()) || isBroken(b.status()) ? Kind.INVALID : Kind.REJECTED;
        }
        if (compareLocation && a.status() == Status.REJECTED) {
            boolean sameLocation = a.line() == b.line() && a.column() == b.column();
            boolean sameMessage = a.message() != null && b.message() != null && b.message().startsWith(a.message());
            return sameLocation && sameMessage ? null : Kind.LOCATION;
        }
        return null;
    }

    private static boolean isBroken(Status s) {
        return s == Status.INVALID_CLASS || s == Status.INTERNAL_ERROR || s == Status.TIMEOUT || s == Status.CRASH;
    }
}
