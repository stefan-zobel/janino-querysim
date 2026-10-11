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

import io.github.janinolts.qsim.plan.Expression.ArithmeticOp;
import io.github.janinolts.qsim.plan.Expression.BitwiseOp;
import io.github.janinolts.qsim.plan.Expression.ComparisonOp;
import io.github.janinolts.qsim.plan.Expression.MathFunction;
import io.github.janinolts.qsim.plan.Expression.ShiftOp;
import io.github.janinolts.qsim.plan.Expression.StringFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates random, well-typed expressions over a schema, deterministically
 * from a random source. Every kind of expression can occur; leaves are columns
 * of the requested type, edge values, small values and {@code NULL}.
 */
public final class ExpressionGenerator {

    /**
     * The weights of the kinds of expressions and of the types.
     *
     * @param leafOneIn below the maximum depth, an expression is a leaf with
     *        the probability {@code 1 / leafOneIn}
     * @param conditional the weight of {@code CASE}, {@code IF} and
     *        {@code COALESCE}
     * @param cast the weight of {@code CAST}
     * @param operator the weight of the operators and functions of the type
     * @param types the weight of each type, in the order of
     *        {@link DataType#values()}, where any type can be chosen
     */
    public record Weights(int leafOneIn, int conditional, int cast, int operator, List<Integer> types) {

        /** The default: a leaf in one of four, conditionals 2, casts 1, operators 7, all types alike. */
        public static final Weights DEFAULT = new Weights(4, 2, 1, 7,
                Collections.nCopies(DataType.values().length, 1));

        public Weights {
            types = List.copyOf(types);
            if (leafOneIn < 1 || conditional < 0 || cast < 0 || operator < 0 || conditional + cast + operator == 0
                    || types.size() != DataType.values().length || types.stream().mapToInt(i -> i).sum() <= 0
                    || types.stream().anyMatch(i -> i < 0)) {
                throw new IllegalArgumentException("Invalid weights: " + this);
            }
        }

        /** Picks a type by its weight. */
        public DataType pickType(SplittableRandom random) {
            int r = random.nextInt(types.stream().mapToInt(i -> i).sum());
            for (int i = 0; i < types.size(); i++) {
                r -= types.get(i);
                if (r < 0) {
                    return DataType.values()[i];
                }
            }
            throw new IllegalStateException();
        }
    }

    private static final List<DataType> NUMERIC = List.of(DataType.BYTE, DataType.SHORT, DataType.INT,
            DataType.LONG, DataType.DECIMAL, DataType.FLOAT, DataType.DOUBLE);

    private final SplittableRandom random;
    private final Schema schema;
    private final Weights weights;

    public ExpressionGenerator(SplittableRandom random, Schema schema) {
        this(random, schema, Weights.DEFAULT);
    }

    public ExpressionGenerator(SplittableRandom random, Schema schema, Weights weights) {
        this.random = random;
        this.schema = schema;
        this.weights = weights;
    }

    /** Generates an expression of the given type with at most the given depth. */
    public Expression generate(DataType type, int depth) {
        if (depth <= 0 || random.nextInt(weights.leafOneIn()) == 0) {
            return leaf(type);
        }
        int d = depth - 1;
        int choice = random.nextInt(weights.conditional() + weights.cast() + weights.operator());
        if (choice < weights.conditional()) {
            return conditional(type, d);
        }
        if (choice < weights.conditional() + weights.cast()) {
            return new Expression.Cast(generate(anyType(), d), type);
        }
        return switch (type) {
            case BOOLEAN -> bool(d);
            case STRING -> string(d);
            case DATE -> leaf(type);
            default -> numeric(type, d);
        };
    }

    private DataType anyType() {
        return weights.pickType(random);
    }

    private Expression leaf(DataType type) {
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            if (schema.type(i) == type) {
                columns.add(i);
            }
        }
        int choice = random.nextInt(10);
        if (!columns.isEmpty() && choice < 5) {
            return new Expression.Column(columns.get(random.nextInt(columns.size())), type);
        }
        if (choice == 5) {
            return new Expression.Literal(type, null);
        }
        List<Object> edges = EdgeValues.of(type);
        return new Expression.Literal(type, edges.get(random.nextInt(edges.size())));
    }

    private Expression conditional(DataType type, int d) {
        return switch (random.nextInt(3)) {
            case 0 -> {
                List<Expression.When> whens = new ArrayList<>();
                int n = 1 + random.nextInt(3);
                for (int i = 0; i < n; i++) {
                    whens.add(new Expression.When(generate(DataType.BOOLEAN, d), operandOf(type, d)));
                }
                // One branch has exactly the type, so that it is the common type of all.
                yield new Expression.Case(whens, generate(type, d));
            }
            case 1 -> new Expression.If(generate(DataType.BOOLEAN, d), operandOf(type, d), generate(type, d));
            default -> new Expression.Coalesce(List.of(operandOf(type, d), generate(type, d)));
        };
    }

    /** An operand whose type has the given type as the common type, e.g. a narrower numeric type. */
    private Expression operandOf(DataType type, int d) {
        if (type.isNumeric() && random.nextInt(3) == 0) {
            List<DataType> narrower = new ArrayList<>();
            for (DataType t : NUMERIC) {
                if (TypeRules.numeric(t, type) == type) {
                    narrower.add(t);
                }
            }
            return generate(narrower.get(random.nextInt(narrower.size())), d);
        }
        return generate(type, d);
    }

    private Expression numeric(DataType type, int d) {
        int choice = random.nextInt(type.isIntegral() ? 7 : 4);
        return switch (choice) {
            case 0, 1 -> {
                ArithmeticOp op = ArithmeticOp.values()[random.nextInt(ArithmeticOp.values().length)];
                boolean leftFirst = random.nextBoolean();
                Expression a = generate(type, d);
                Expression b = operandOf(type, d);
                yield new Expression.Arithmetic(op, leftFirst ? a : b, leftFirst ? b : a);
            }
            case 2 -> new Expression.Negate(generate(type, d));
            case 3 -> mathOrLength(type, d);
            case 4 -> new Expression.Bitwise(BitwiseOp.values()[random.nextInt(3)], generate(type, d),
                    operandOf(type, d));
            case 5 -> new Expression.BitNot(generate(type, d));
            default -> new Expression.Shift(ShiftOp.values()[random.nextInt(3)], generate(type, d),
                    generate(DataType.INT, d));
        };
    }

    private Expression mathOrLength(DataType type, int d) {
        if (type == DataType.DOUBLE) {
            MathFunction f = MathFunction.values()[random.nextInt(MathFunction.values().length)];
            List<Expression> args = new ArrayList<>();
            args.add(generate(DataType.DOUBLE, d));
            if (f == MathFunction.POWER) {
                args.add(generate(DataType.DOUBLE, d));
            }
            return new Expression.MathCall(f, args);
        }
        if (type == DataType.INT && random.nextBoolean()) {
            return new Expression.StringCall(StringFunction.LENGTH, List.of(generate(DataType.STRING, d)));
        }
        return new Expression.MathCall(MathFunction.ABS, List.of(generate(type, d)));
    }

    private Expression bool(int d) {
        return switch (random.nextInt(9)) {
            case 0, 1 -> {
                DataType t = anyType();
                Expression a = generate(t, d);
                Expression b = t.isNumeric() ? operandOf(randomWider(t), d) : generate(t, d);
                yield new Expression.Comparison(ComparisonOp.values()[random.nextInt(6)], a, b);
            }
            case 2 -> new Expression.And(generate(DataType.BOOLEAN, d), generate(DataType.BOOLEAN, d));
            case 3 -> new Expression.Or(generate(DataType.BOOLEAN, d), generate(DataType.BOOLEAN, d));
            case 4 -> new Expression.Not(generate(DataType.BOOLEAN, d));
            case 5 -> new Expression.IsNull(generate(anyType(), d), random.nextBoolean());
            case 6 -> {
                DataType t = anyType();
                yield new Expression.Between(generate(t, d), sameFamily(t, d), sameFamily(t, d));
            }
            case 7 -> {
                DataType t = anyType();
                List<Expression> list = new ArrayList<>();
                int n = 1 + random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    list.add(sameFamily(t, d));
                }
                yield new Expression.In(generate(t, d), list);
            }
            default -> new Expression.Like(generate(DataType.STRING, d), generate(DataType.STRING, d));
        };
    }

    private DataType randomWider(DataType t) {
        return NUMERIC.get(random.nextInt(NUMERIC.size())) == DataType.DOUBLE ? DataType.DOUBLE : t;
    }

    /** An expression whose type is compatible with {@code t}: the same type, or any numeric type. */
    private Expression sameFamily(DataType t, int d) {
        return generate(t.isNumeric() ? NUMERIC.get(random.nextInt(NUMERIC.size())) : t, d);
    }

    private Expression string(int d) {
        return switch (random.nextInt(3)) {
            case 0 -> {
                List<Expression> args = new ArrayList<>();
                int n = 1 + random.nextInt(3);
                for (int i = 0; i < n; i++) {
                    args.add(generate(DataType.STRING, d));
                }
                yield new Expression.StringCall(StringFunction.CONCAT, args);
            }
            case 1 -> new Expression.StringCall(StringFunction.SUBSTRING,
                    List.of(generate(DataType.STRING, d), generate(DataType.INT, d), generate(DataType.INT, d)));
            default -> new Expression.StringCall(StringFunction.UPPER, List.of(generate(DataType.STRING, d)));
        };
    }
}
