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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * The result of the differential comparison of a set of units, reconciled
 * with the ledger.
 *
 * @param suite the name of the suite, e.g. {@code smoke}
 * @param backends the backends with their versions
 * @param ledger the ledger
 * @param outcomes the outcome per unit, in the order of the run
 * @param reconciliation the reconciliation with the ledger
 * @param notes additional lines for the report, e.g. the inconsistencies of
 *        metamorphic variants or the batches that did not finish
 * @param reproducers the directories of the reproducers of unexplained
 *        differences, by unit key, relative to the report
 */
public record CorrectnessRun(String suite, DifferentialRunner.Backends backends, Ledger ledger,
        List<UnitOutcome> outcomes, Ledger.Reconciliation reconciliation, List<String> notes,
        Map<UnitKey, String> reproducers) {

    public CorrectnessRun {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(backends, "backends");
        Objects.requireNonNull(ledger, "ledger");
        outcomes = List.copyOf(outcomes);
        Objects.requireNonNull(reconciliation, "reconciliation");
        notes = List.copyOf(notes);
        reproducers = Map.copyOf(reproducers);
    }

    /** Runs the comparison of all units and reconciles the differences with the ledger. */
    public static CorrectnessRun run(String suite, DifferentialRunner runner, Ledger ledger, List<UnitCase> units) {
        List<UnitOutcome> outcomes = new ArrayList<>();
        for (UnitCase unit : units) {
            outcomes.add(runner.run(unit));
        }
        return of(suite, runner.backends(), ledger, outcomes);
    }

    /**
     * Runs the comparison of units in several threads; the outcomes keep the
     * order of the units. A supplier may return {@code null} for a unit that
     * does not exist, e.g. a style that does not fit a plan.
     */
    public static List<UnitOutcome> runParallel(DifferentialRunner runner, List<Supplier<UnitCase>> units,
            int threads) {
        if (threads <= 1) {
            List<UnitOutcome> outcomes = new ArrayList<>();
            for (Supplier<UnitCase> s : units) {
                UnitCase unit = s.get();
                outcomes.add(unit == null ? null : runner.run(unit));
            }
            return outcomes;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<UnitOutcome>> futures = new ArrayList<>();
            for (Supplier<UnitCase> s : units) {
                futures.add(pool.submit(() -> {
                    UnitCase unit = s.get();
                    return unit == null ? null : runner.run(unit);
                }));
            }
            List<UnitOutcome> outcomes = new ArrayList<>();
            for (Future<UnitOutcome> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A unit failed in the harness", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Reconciles the outcomes of a run with the ledger. */
    public static CorrectnessRun of(String suite, DifferentialRunner.Backends backends, Ledger ledger,
            List<UnitOutcome> outcomes) {
        List<Difference> differences = new ArrayList<>();
        for (UnitOutcome o : outcomes) {
            differences.addAll(o.differences());
        }
        Map<UnitKey, List<String>> units = new LinkedHashMap<>();
        for (UnitOutcome o : outcomes) {
            units.put(o.key(), o.featureTags());
        }
        Ledger.Reconciliation reconciliation = ledger.reconcile(units, differences);
        return new CorrectnessRun(suite, backends, ledger, outcomes, reconciliation, List.of(), Map.of());
    }

    /** Returns a copy with additional notes and reproducers. */
    public CorrectnessRun with(List<String> moreNotes, Map<UnitKey, String> moreReproducers) {
        List<String> n = new ArrayList<>(notes);
        n.addAll(moreNotes);
        Map<UnitKey, String> r = new LinkedHashMap<>(reproducers);
        r.putAll(moreReproducers);
        return new CorrectnessRun(suite, backends, ledger, outcomes, reconciliation, n, r);
    }

    /** Tells whether every difference is explained by the ledger and no entry of it is orphaned. */
    public boolean passed() {
        return reconciliation.passed();
    }

    /** Returns all differences that the ledger must explain, in the order of the run. */
    public List<Difference> differences() {
        return outcomes.stream().flatMap(o -> o.differences().stream()).toList();
    }

    /** Returns the differences that follow from the language profiles of the units. */
    public List<Difference> expected() {
        return outcomes.stream().flatMap(o -> o.expected().stream()).toList();
    }
}
