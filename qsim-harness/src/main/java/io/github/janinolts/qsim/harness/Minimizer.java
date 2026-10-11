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
import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.Plans;
import io.github.janinolts.qsim.plan.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Reduces a unit with a finding by delta debugging, as long as the same
 * difference (the same pair and kind, not explained by the profile) remains:
 * settings of the generator back to the defaults, operators and columns
 * removed, subexpressions replaced by their operands or by literals, rows of
 * the tables removed. Every candidate is checked with a full differential
 * comparison; the search stops at a fixpoint or when its budget is used up.
 */
public final class Minimizer {

    /**
     * The difference to keep.
     *
     * @param pair the pair
     * @param kind the kind
     */
    public record Target(Difference.Pair pair, Difference.Kind kind) {
        public Target {
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(kind, "kind");
        }

        /** Returns the target of a difference. */
        public static Target of(Difference d) {
            return new Target(d.pair(), d.kind());
        }
    }

    /**
     * The result of a minimization.
     *
     * @param unit the smallest unit found that still shows the difference
     * @param outcome its outcome
     * @param checks the number of candidates compared
     * @param exhausted whether the budget ran out before a fixpoint
     */
    public record Result(UnitCase unit, UnitOutcome outcome, int checks, boolean exhausted) {
    }

    private final DifferentialRunner runner;
    private final int maxChecks;
    private final long deadline;
    private int checks;

    /**
     * Creates a minimizer.
     *
     * @param runner the runner that compares candidates
     * @param maxChecks the maximum number of candidates to compare
     * @param maxMillis the maximum time in milliseconds
     */
    public Minimizer(DifferentialRunner runner, int maxChecks, long maxMillis) {
        this.runner = runner;
        this.maxChecks = maxChecks;
        this.deadline = System.currentTimeMillis() + maxMillis;
    }

    /**
     * Minimizes a unit.
     *
     * @throws IllegalArgumentException if the unit does not show the difference
     */
    public Result minimize(UnitCase unit, Target target) {
        UnitOutcome outcome = runner.run(unit);
        if (!shows(outcome, target)) {
            throw new IllegalArgumentException("The unit " + unit.key() + " does not show " + target);
        }
        State state = new State(unit, outcome);
        boolean progress = true;
        while (progress && !exhausted()) {
            progress = reduce(state, target, configCandidates(state.unit))
                    | reduce(state, target, planCandidates(state.unit))
                    | reduce(state, target, expressionCandidates(state.unit))
                    | reduceRows(state, target);
        }
        return new Result(state.unit, state.outcome, checks, exhausted());
    }

    private static final class State {
        UnitCase unit;
        UnitOutcome outcome;

        State(UnitCase unit, UnitOutcome outcome) {
            this.unit = unit;
            this.outcome = outcome;
        }
    }

    private boolean exhausted() {
        return checks >= maxChecks || System.currentTimeMillis() > deadline;
    }

    private static boolean shows(UnitOutcome outcome, Target target) {
        return outcome.differences().stream().anyMatch(d -> d.pair() == target.pair() && d.kind() == target.kind());
    }

    /** Tries candidates in order and keeps the first that shows the difference; repeats until none does. */
    private boolean reduce(State state, Target target, List<Function<UnitCase, UnitCase>> candidates) {
        boolean any = false;
        boolean progress = true;
        while (progress && !exhausted()) {
            progress = false;
            for (Function<UnitCase, UnitCase> candidate : candidates) {
                if (exhausted()) {
                    break;
                }
                if (accept(state, target, candidate)) {
                    any = true;
                    progress = true;
                    break;
                }
            }
            if (progress) {
                candidates = sameKind(candidates, state.unit);
            }
        }
        return any;
    }

    /** Recomputes the candidates of the same phase for the reduced unit. */
    private List<Function<UnitCase, UnitCase>> sameKind(List<Function<UnitCase, UnitCase>> old, UnitCase unit) {
        if (old.isEmpty()) {
            return old;
        }
        Object phase = old.get(0);
        if (phase instanceof ConfigCandidate) {
            return configCandidates(unit);
        }
        if (phase instanceof PlanCandidate) {
            return planCandidates(unit);
        }
        return expressionCandidates(unit);
    }

    private boolean accept(State state, Target target, Function<UnitCase, UnitCase> candidate) {
        UnitCase c;
        try {
            c = candidate.apply(state.unit);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return false;
        }
        if (c == null) {
            return false;
        }
        checks++;
        UnitOutcome o;
        try {
            o = runner.run(c);
        } catch (RuntimeException e) {
            return false;
        }
        if (!shows(o, target)) {
            return false;
        }
        state.unit = c;
        state.outcome = o;
        return true;
    }

    /** A candidate that changes the configuration of the generator. */
    private interface ConfigCandidate extends Function<UnitCase, UnitCase> {
    }

    /** A candidate that changes the operators of the plan. */
    private interface PlanCandidate extends Function<UnitCase, UnitCase> {
    }

    /** A candidate that changes an expression of the plan. */
    private interface ExpressionCandidate extends Function<UnitCase, UnitCase> {
    }

    private List<Function<UnitCase, UnitCase>> configCandidates(UnitCase unit) {
        // Each candidate moves one setting to the default, so that the search cannot go back and forth.
        CodegenConfig c = unit.config();
        List<Function<UnitCase, UnitCase>> result = new ArrayList<>();
        List<CodegenConfig> simpler = List.of(
                c.withComments(false), c.withTryNesting(0), c.withInlineStrings(false),
                c.withReuseSubexpressions(false), c.withConstantsAsReferences(false),
                c.withSplitThreshold(CodegenConfig.DEFAULT_SPLIT_THRESHOLD), c.withAnsi(false));
        for (CodegenConfig s : simpler) {
            if (!s.equals(c)) {
                result.add((ConfigCandidate) u -> u.with(u.plan(), u.tables(), s));
            }
        }
        return result;
    }

    private List<Function<UnitCase, UnitCase>> planCandidates(UnitCase unit) {
        PlanNode plan = unit.plan();
        List<Function<UnitCase, UnitCase>> result = new ArrayList<>();
        List<PlanNode> nodes = Plans.nodes(plan);
        // A subtree as the whole plan; the smallest first.
        for (int i = nodes.size() - 1; i > 0; i--) {
            PlanNode sub = nodes.get(i);
            result.add((PlanCandidate) u -> u.with(sub, u.tables(), u.config()));
        }
        for (PlanNode node : nodes) {
            switch (node) {
                case PlanNode.Filter f -> result.add(replace(node, f.input()));
                case PlanNode.Limit l -> result.add(replace(node, l.input()));
                case PlanNode.Sort s -> {
                    result.add(replace(node, s.input()));
                    for (int k = 0; k < s.keys().size() && s.keys().size() > 1; k++) {
                        List<PlanNode.SortKey> keys = new ArrayList<>(s.keys());
                        keys.remove(k);
                        result.add(replace(node, new PlanNode.Sort(s.input(), keys)));
                    }
                }
                case PlanNode.Project p -> {
                    result.add(replace(node, p.input()));
                    for (int k = 0; k < p.columns().size() && p.columns().size() > 1; k++) {
                        List<PlanNode.NamedExpression> columns = new ArrayList<>(p.columns());
                        columns.remove(k);
                        result.add(replace(node, () -> new PlanNode.Project(p.input(), columns)));
                    }
                }
                case PlanNode.HashAggregate a -> {
                    for (int k = 0; k < a.aggregates().size() && a.aggregates().size() > 1; k++) {
                        List<PlanNode.AggregateCall> calls = new ArrayList<>(a.aggregates());
                        calls.remove(k);
                        result.add(replace(node, () -> new PlanNode.HashAggregate(a.input(), a.groupColumns(),
                                calls)));
                    }
                    for (int k = 0; k < a.groupColumns().size(); k++) {
                        List<Integer> groups = new ArrayList<>(a.groupColumns());
                        groups.remove(k);
                        result.add(replace(node, () -> new PlanNode.HashAggregate(a.input(), groups,
                                a.aggregates())));
                    }
                }
                case PlanNode.HashJoin j -> {
                    result.add(replace(node, j.probe()));
                    if (j.type() == PlanNode.JoinType.LEFT_OUTER) {
                        result.add(replace(node, () -> new PlanNode.HashJoin(j.probe(), j.build(), j.probeKeys(),
                                j.buildKeys(), PlanNode.JoinType.INNER)));
                    }
                }
                case PlanNode.Union un -> {
                    for (int k = 0; k < un.inputs().size() && un.inputs().size() > 1; k++) {
                        List<PlanNode> inputs = new ArrayList<>(un.inputs());
                        inputs.remove(k);
                        result.add(replace(node, () -> new PlanNode.Union(inputs)));
                    }
                }
                default -> {
                }
            }
        }
        return result;
    }

    private interface NodeSupplier {
        PlanNode get();
    }

    private static Function<UnitCase, UnitCase> replace(PlanNode target, PlanNode replacement) {
        return replace(target, () -> replacement);
    }

    private static Function<UnitCase, UnitCase> replace(PlanNode target, NodeSupplier replacement) {
        return (PlanCandidate) u -> u.with(Plans.replace(u.plan(), target, replacement.get()), u.tables(),
                u.config());
    }

    private List<Function<UnitCase, UnitCase>> expressionCandidates(UnitCase unit) {
        List<Function<UnitCase, UnitCase>> result = new ArrayList<>();
        for (PlanNode node : Plans.nodes(unit.plan())) {
            List<Expression> expressions = Plans.expressions(node);
            for (int slot = 0; slot < expressions.size(); slot++) {
                List<Expression> subs = new ArrayList<>();
                collect(expressions.get(slot), subs);
                for (Expression sub : subs) {
                    for (Expression replacement : replacements(sub)) {
                        int s = slot;
                        result.add((ExpressionCandidate) u -> u.with(Plans.replace(u.plan(), node,
                                Plans.mapExpressions(node, slotMapper(s, sub, replacement))), u.tables(),
                                u.config()));
                    }
                }
            }
        }
        return result;
    }

    /** Maps the expression in one slot of a node: replaces one subexpression, by identity. */
    private static UnaryOperator<Expression> slotMapper(int slot, Expression target,
            Expression replacement) {
        int[] k = {0};
        return e -> k[0]++ == slot ? replaceExpression(e, target, replacement) : e;
    }

    private static Expression replaceExpression(Expression e, Expression target, Expression replacement) {
        if (e == target) {
            return replacement;
        }
        List<Expression> children = new ArrayList<>(e.children());
        for (int i = 0; i < children.size(); i++) {
            Expression c = replaceExpression(children.get(i), target, replacement);
            if (c != children.get(i)) {
                children.set(i, c);
                return Plans.withChildren(e, children);
            }
        }
        return e;
    }

    /** The subexpressions of an expression, in pre-order: the larger ones first. */
    private static void collect(Expression e, List<Expression> result) {
        result.add(e);
        for (Expression c : e.children()) {
            collect(c, result);
        }
    }

    /** The simpler expressions of the same type: the operands of that type, then literals. */
    private static List<Expression> replacements(Expression e) {
        List<Expression> result = new ArrayList<>();
        if (e instanceof Expression.Literal) {
            return result;
        }
        for (Expression c : e.children()) {
            if (c.type() == e.type()) {
                result.add(c);
            }
        }
        result.add(new Expression.Literal(e.type(), null));
        result.add(new Expression.Literal(e.type(), simpleValue(e.type())));
        return result;
    }

    private static Object simpleValue(DataType type) {
        return switch (type) {
            case BOOLEAN -> Boolean.TRUE;
            case BYTE -> (byte) 1;
            case SHORT -> (short) 1;
            case INT, DATE -> 1;
            case LONG -> 1L;
            case FLOAT -> 1.0f;
            case DOUBLE -> 1.0;
            case STRING -> "a";
            case DECIMAL -> BigDecimal.ONE;
        };
    }

    /** Removes rows of the tables, in chunks that get smaller, as long as the difference remains. */
    private boolean reduceRows(State state, Target target) {
        boolean any = false;
        for (String name : List.copyOf(state.unit.tables().keySet())) {
            int chunk = Math.max(1, state.unit.tables().get(name).size() / 2);
            while (chunk >= 1 && !exhausted()) {
                boolean removed = false;
                for (int start = 0; start < state.unit.tables().get(name).size() && !exhausted(); ) {
                    int from = start;
                    int size = chunk;
                    boolean accepted = accept(state, target, u -> {
                        Table t = u.tables().get(name);
                        if (from >= t.size()) {
                            return null;
                        }
                        List<Object[]> rows = new ArrayList<>(t.rows());
                        rows.subList(from, Math.min(t.size(), from + size)).clear();
                        Map<String, Table> tables = new LinkedHashMap<>(u.tables());
                        tables.put(name, new Table(t.schema(), rows));
                        return u.with(u.plan(), tables, u.config());
                    });
                    if (accepted) {
                        removed = true;
                        any = true;
                    } else {
                        start += chunk;
                    }
                }
                if (!removed) {
                    chunk /= 2;
                }
            }
        }
        return any;
    }
}
