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

package io.github.janinolts.qsim.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Rewrites plans and expressions: copies of nodes with other inputs or
 * operands, replacements by identity, traversals. A rewrite creates new nodes
 * through their constructors, which check the types again; a rewrite that
 * does not fit throws an {@link IllegalArgumentException}.
 */
public final class Plans {

    private Plans() {
    }

    /** Returns the nodes of a plan in pre-order. */
    public static List<PlanNode> nodes(PlanNode plan) {
        List<PlanNode> result = new ArrayList<>();
        collect(plan, result);
        return result;
    }

    private static void collect(PlanNode node, List<PlanNode> result) {
        result.add(node);
        for (PlanNode child : node.children()) {
            collect(child, result);
        }
    }

    /** Returns a copy of a node with other inputs, in the order of {@link PlanNode#children()}. */
    public static PlanNode withChildren(PlanNode node, List<PlanNode> children) {
        return switch (node) {
            case PlanNode.Scan s -> s;
            case PlanNode.Filter f -> new PlanNode.Filter(children.get(0), f.condition());
            case PlanNode.Project p -> new PlanNode.Project(children.get(0), p.columns());
            case PlanNode.HashAggregate a -> new PlanNode.HashAggregate(children.get(0), a.groupColumns(),
                    a.aggregates());
            case PlanNode.HashJoin j -> new PlanNode.HashJoin(children.get(0), children.get(1), j.probeKeys(),
                    j.buildKeys(), j.type());
            case PlanNode.Sort s -> new PlanNode.Sort(children.get(0), s.keys());
            case PlanNode.Limit l -> new PlanNode.Limit(children.get(0), l.count());
            case PlanNode.Union u -> new PlanNode.Union(children);
        };
    }

    /**
     * Returns the plan with one node, found by identity, replaced; the nodes
     * above it are copied.
     *
     * @throws IllegalArgumentException if the node is not in the plan, or the
     *         replacement does not fit its parent
     */
    public static PlanNode replace(PlanNode plan, PlanNode target, PlanNode replacement) {
        PlanNode result = replaceOrNull(plan, target, replacement);
        if (result == null) {
            throw new IllegalArgumentException("Not a node of the plan: " + target);
        }
        return result;
    }

    private static PlanNode replaceOrNull(PlanNode node, PlanNode target, PlanNode replacement) {
        if (node == target) {
            return replacement;
        }
        List<PlanNode> children = new ArrayList<>(node.children());
        for (int i = 0; i < children.size(); i++) {
            PlanNode c = replaceOrNull(children.get(i), target, replacement);
            if (c != null) {
                children.set(i, c);
                return withChildren(node, children);
            }
        }
        return null;
    }

    /** Returns a copy of an expression with other operands, in the order of {@link Expression#children()}. */
    public static Expression withChildren(Expression e, List<Expression> c) {
        return switch (e) {
            case Expression.Literal l -> l;
            case Expression.Column col -> col;
            case Expression.Arithmetic a -> new Expression.Arithmetic(a.op(), c.get(0), c.get(1));
            case Expression.Negate n -> new Expression.Negate(c.get(0));
            case Expression.Bitwise b -> new Expression.Bitwise(b.op(), c.get(0), c.get(1));
            case Expression.BitNot b -> new Expression.BitNot(c.get(0));
            case Expression.Shift s -> new Expression.Shift(s.op(), c.get(0), c.get(1));
            case Expression.Comparison cmp -> new Expression.Comparison(cmp.op(), c.get(0), c.get(1));
            case Expression.And a -> new Expression.And(c.get(0), c.get(1));
            case Expression.Or o -> new Expression.Or(c.get(0), c.get(1));
            case Expression.Not n -> new Expression.Not(c.get(0));
            case Expression.IsNull n -> new Expression.IsNull(c.get(0), n.negated());
            case Expression.Between b -> new Expression.Between(c.get(0), c.get(1), c.get(2));
            case Expression.In in -> new Expression.In(c.get(0), c.subList(1, c.size()));
            case Expression.Case cs -> {
                List<Expression.When> whens = new ArrayList<>();
                for (int i = 0; i < cs.whens().size(); i++) {
                    whens.add(new Expression.When(c.get(2 * i), c.get(2 * i + 1)));
                }
                yield new Expression.Case(whens, c.get(c.size() - 1));
            }
            case Expression.If i -> new Expression.If(c.get(0), c.get(1), c.get(2));
            case Expression.Coalesce co -> new Expression.Coalesce(c);
            case Expression.Cast cast -> new Expression.Cast(c.get(0), cast.target());
            case Expression.StringCall s -> new Expression.StringCall(s.function(), c);
            case Expression.Like l -> new Expression.Like(c.get(0), c.get(1));
            case Expression.MathCall m -> new Expression.MathCall(m.function(), c);
            case Expression.Feature f -> new Expression.Feature(f.feature(), c.get(0));
        };
    }

    /** Returns a copy of a node whose expressions are mapped; other nodes are returned unchanged. */
    public static PlanNode mapExpressions(PlanNode node, UnaryOperator<Expression> mapping) {
        return switch (node) {
            case PlanNode.Filter f -> new PlanNode.Filter(f.input(), mapping.apply(f.condition()));
            case PlanNode.Project p -> {
                List<PlanNode.NamedExpression> columns = new ArrayList<>();
                for (PlanNode.NamedExpression c : p.columns()) {
                    columns.add(new PlanNode.NamedExpression(c.name(), mapping.apply(c.expression())));
                }
                yield new PlanNode.Project(p.input(), columns);
            }
            default -> node;
        };
    }

    /** Returns the expressions of a node: the condition of a filter, the columns of a projection. */
    public static List<Expression> expressions(PlanNode node) {
        return switch (node) {
            case PlanNode.Filter f -> List.of(f.condition());
            case PlanNode.Project p -> p.columns().stream().map(PlanNode.NamedExpression::expression).toList();
            default -> List.of();
        };
    }

    /** Returns the language features that a plan contains, in pre-order. */
    public static List<LanguageFeature> features(PlanNode plan) {
        List<LanguageFeature> result = new ArrayList<>();
        for (PlanNode node : nodes(plan)) {
            for (Expression e : expressions(node)) {
                collectFeatures(e, result);
            }
        }
        return result;
    }

    private static void collectFeatures(Expression e, List<LanguageFeature> result) {
        if (e instanceof Expression.Feature f) {
            result.add(f.feature());
        }
        for (Expression child : e.children()) {
            collectFeatures(child, result);
        }
    }

    /** Returns the number of nodes of an expression. */
    public static int size(Expression e) {
        int n = 1;
        for (Expression child : e.children()) {
            n += size(child);
        }
        return n;
    }
}
