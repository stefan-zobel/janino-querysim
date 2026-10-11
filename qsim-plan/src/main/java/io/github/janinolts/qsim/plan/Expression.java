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
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An expression of the simulated engine. Every node checks the types of its
 * operands when it is created and knows its result {@link #type()}. The
 * semantics are defined by the {@link Interpreter}.
 * <p>
 * Implicit conversions exist only where noted: the operands of arithmetic,
 * comparisons, {@code BETWEEN} and {@code IN}, and the branches of
 * {@code CASE}, {@code IF} and {@code COALESCE}, are converted to their common
 * type ({@link TypeRules}). Everything else must have the exact type; a
 * {@link Cast} converts explicitly.
 */
public sealed interface Expression {

    /** Returns the type of the result. */
    DataType type();

    /** Returns the operands, in the order of evaluation. */
    List<Expression> children();

    /** The arithmetic operators. */
    enum ArithmeticOp { ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER }

    /** The bitwise operators on integral types. */
    enum BitwiseOp { AND, OR, XOR }

    /** The shift operators, as in Java. */
    enum ShiftOp { LEFT, RIGHT, UNSIGNED_RIGHT }

    /** The comparison operators. */
    enum ComparisonOp { EQ, NE, LT, LE, GT, GE }

    /** The string functions. */
    enum StringFunction { CONCAT, SUBSTRING, UPPER, LENGTH }

    /** The mathematical functions. */
    enum MathFunction { ABS, SQRT, POWER, FLOOR, CEIL }

    /** A constant, possibly {@code NULL}. */
    record Literal(DataType type, Object value) implements Expression {
        public Literal {
            Objects.requireNonNull(type, "type");
            if (!type.accepts(value)) {
                throw new IllegalArgumentException("Not a " + type + ": " + value.getClass().getName());
            }
        }

        @Override
        public List<Expression> children() {
            return List.of();
        }
    }

    /** The value of a column of the input row, by index. */
    record Column(int index, DataType type) implements Expression {
        public Column {
            Objects.requireNonNull(type, "type");
            if (index < 0) {
                throw new IllegalArgumentException("index: " + index);
            }
        }

        @Override
        public List<Expression> children() {
            return List.of();
        }
    }

    /** {@code + - * / %} on numeric operands, in their common type. */
    record Arithmetic(ArithmeticOp op, Expression left, Expression right) implements Expression {
        public Arithmetic {
            Objects.requireNonNull(op, "op");
            TypeRules.numeric(left.type(), right.type());
        }

        @Override
        public DataType type() {
            return TypeRules.numeric(left.type(), right.type());
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** Unary minus. */
    record Negate(Expression operand) implements Expression {
        public Negate {
            TypeRules.requireNumeric(operand.type());
        }

        @Override
        public DataType type() {
            return operand.type();
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /** {@code & | ^} on integral operands, in their common type. */
    record Bitwise(BitwiseOp op, Expression left, Expression right) implements Expression {
        public Bitwise {
            Objects.requireNonNull(op, "op");
            TypeRules.integral(left.type(), right.type());
        }

        @Override
        public DataType type() {
            return TypeRules.integral(left.type(), right.type());
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** {@code ~} on an integral operand. */
    record BitNot(Expression operand) implements Expression {
        public BitNot {
            if (!operand.type().isIntegral()) {
                throw new IllegalArgumentException("Not integral: " + operand.type());
            }
        }

        @Override
        public DataType type() {
            return operand.type();
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /** {@code << >> >>>}: an integral value shifted by an {@code INT} distance; the type of the value. */
    record Shift(ShiftOp op, Expression value, Expression distance) implements Expression {
        public Shift {
            Objects.requireNonNull(op, "op");
            if (!value.type().isIntegral()) {
                throw new IllegalArgumentException("Not integral: " + value.type());
            }
            TypeRules.require(DataType.INT, distance.type(), "The shift distance");
        }

        @Override
        public DataType type() {
            return value.type();
        }

        @Override
        public List<Expression> children() {
            return List.of(value, distance);
        }
    }

    /** A comparison in the common type of the operands. */
    record Comparison(ComparisonOp op, Expression left, Expression right) implements Expression {
        public Comparison {
            Objects.requireNonNull(op, "op");
            TypeRules.common(left.type(), right.type());
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** {@code AND} with three-valued logic. */
    record And(Expression left, Expression right) implements Expression {
        public And {
            TypeRules.require(DataType.BOOLEAN, left.type(), "An operand of AND");
            TypeRules.require(DataType.BOOLEAN, right.type(), "An operand of AND");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** {@code OR} with three-valued logic. */
    record Or(Expression left, Expression right) implements Expression {
        public Or {
            TypeRules.require(DataType.BOOLEAN, left.type(), "An operand of OR");
            TypeRules.require(DataType.BOOLEAN, right.type(), "An operand of OR");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** {@code NOT} with three-valued logic. */
    record Not(Expression operand) implements Expression {
        public Not {
            TypeRules.require(DataType.BOOLEAN, operand.type(), "The operand of NOT");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /** {@code IS NULL}, or {@code IS NOT NULL} if negated; never {@code NULL}. */
    record IsNull(Expression operand, boolean negated) implements Expression {
        public IsNull {
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /** {@code value BETWEEN low AND high}, i.e. {@code value >= low AND value <= high}. */
    record Between(Expression value, Expression low, Expression high) implements Expression {
        public Between {
            TypeRules.common(List.of(value.type(), low.type(), high.type()));
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(value, low, high);
        }
    }

    /** {@code value IN (list)}, each element compared like {@code =}. */
    record In(Expression value, List<Expression> list) implements Expression {
        public In {
            list = List.copyOf(list);
            if (list.isEmpty()) {
                throw new IllegalArgumentException("Empty IN list");
            }
            for (Expression e : list) {
                TypeRules.common(value.type(), e.type());
            }
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            List<Expression> result = new ArrayList<>();
            result.add(value);
            result.addAll(list);
            return result;
        }
    }

    /** One branch of a {@link Case}. */
    record When(Expression condition, Expression result) {
        public When {
            TypeRules.require(DataType.BOOLEAN, condition.type(), "A condition of CASE");
            Objects.requireNonNull(result, "result");
        }
    }

    /** {@code CASE WHEN c1 THEN r1 ... ELSE otherwise END}; a {@code NULL} condition counts as false. */
    record Case(List<When> whens, Expression otherwise) implements Expression {
        public Case {
            whens = List.copyOf(whens);
            if (whens.isEmpty()) {
                throw new IllegalArgumentException("CASE without WHEN");
            }
            Objects.requireNonNull(otherwise, "otherwise");
            resultTypes(whens, otherwise);
        }

        @Override
        public DataType type() {
            return resultTypes(whens, otherwise);
        }

        private static DataType resultTypes(List<When> whens, Expression otherwise) {
            List<DataType> types = new ArrayList<>();
            for (When w : whens) {
                types.add(w.result().type());
            }
            types.add(otherwise.type());
            return TypeRules.common(types);
        }

        @Override
        public List<Expression> children() {
            List<Expression> result = new ArrayList<>();
            for (When w : whens) {
                result.add(w.condition());
                result.add(w.result());
            }
            result.add(otherwise);
            return result;
        }
    }

    /** {@code IF(condition, whenTrue, whenFalse)}; a {@code NULL} condition selects {@code whenFalse}. */
    record If(Expression condition, Expression whenTrue, Expression whenFalse) implements Expression {
        public If {
            TypeRules.require(DataType.BOOLEAN, condition.type(), "The condition of IF");
            TypeRules.common(whenTrue.type(), whenFalse.type());
        }

        @Override
        public DataType type() {
            return TypeRules.common(whenTrue.type(), whenFalse.type());
        }

        @Override
        public List<Expression> children() {
            return List.of(condition, whenTrue, whenFalse);
        }
    }

    /** The first operand that is not {@code NULL}. */
    record Coalesce(List<Expression> operands) implements Expression {
        public Coalesce {
            operands = List.copyOf(operands);
            if (operands.isEmpty()) {
                throw new IllegalArgumentException("COALESCE without operands");
            }
            TypeRules.common(operands.stream().map(Expression::type).toList());
        }

        @Override
        public DataType type() {
            return TypeRules.common(operands.stream().map(Expression::type).toList());
        }

        @Override
        public List<Expression> children() {
            return operands;
        }
    }

    /** {@code CAST(operand AS target)}; every pair of types is allowed. */
    record Cast(Expression operand, DataType target) implements Expression {
        public Cast {
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(target, "target");
        }

        @Override
        public DataType type() {
            return target;
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /**
     * A string function: {@code CONCAT(STRING...)}, {@code SUBSTRING(STRING,
     * INT start, INT length)}, {@code UPPER(STRING)}, {@code LENGTH(STRING)}.
     */
    record StringCall(StringFunction function, List<Expression> arguments) implements Expression {
        public StringCall {
            Objects.requireNonNull(function, "function");
            arguments = List.copyOf(arguments);
            List<DataType> expected = switch (function) {
                case CONCAT -> arguments.stream().map(a -> DataType.STRING).toList();
                case SUBSTRING -> List.of(DataType.STRING, DataType.INT, DataType.INT);
                case UPPER, LENGTH -> List.of(DataType.STRING);
            };
            checkArguments(function, expected, arguments);
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException(function + " without arguments");
            }
        }

        @Override
        public DataType type() {
            return function == StringFunction.LENGTH ? DataType.INT : DataType.STRING;
        }

        @Override
        public List<Expression> children() {
            return arguments;
        }
    }

    /** {@code string LIKE pattern}, see {@code StringFunctions.like}. */
    record Like(Expression string, Expression pattern) implements Expression {
        public Like {
            TypeRules.require(DataType.STRING, string.type(), "The operand of LIKE");
            TypeRules.require(DataType.STRING, pattern.type(), "The pattern of LIKE");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(string, pattern);
        }
    }

    /**
     * A mathematical function: {@code ABS} of any numeric type, with the type
     * of its argument; {@code SQRT}, {@code FLOOR}, {@code CEIL} of a
     * {@code DOUBLE} and {@code POWER} of two, as in {@link Math}.
     */
    record MathCall(MathFunction function, List<Expression> arguments) implements Expression {
        public MathCall {
            Objects.requireNonNull(function, "function");
            arguments = List.copyOf(arguments);
            if (function == MathFunction.ABS) {
                if (arguments.size() != 1) {
                    throw new IllegalArgumentException("ABS takes one argument");
                }
                TypeRules.requireNumeric(arguments.get(0).type());
            } else {
                int n = function == MathFunction.POWER ? 2 : 1;
                checkArguments(function, Collections.nCopies(n, DataType.DOUBLE), arguments);
            }
        }

        @Override
        public DataType type() {
            return function == MathFunction.ABS ? arguments.get(0).type() : DataType.DOUBLE;
        }

        @Override
        public List<Expression> children() {
            return arguments;
        }
    }

    /**
     * The value of an operand passed through a language construct whose
     * treatment differs between compilers, see {@link LanguageFeature}; only
     * the units of the language profile of the feature contain it.
     */
    record Feature(LanguageFeature feature, Expression operand) implements Expression {
        public Feature {
            Objects.requireNonNull(feature, "feature");
            Objects.requireNonNull(operand, "operand");
            if (feature.operandType() != null) {
                TypeRules.require(feature.operandType(), operand.type(), "The operand of " + feature);
            }
        }

        @Override
        public DataType type() {
            return feature.resultType(operand.type());
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    private static void checkArguments(Enum<?> function, List<DataType> expected, List<Expression> arguments) {
        if (expected.size() != arguments.size()) {
            throw new IllegalArgumentException(function + " takes " + expected.size() + " arguments");
        }
        for (int i = 0; i < expected.size(); i++) {
            TypeRules.require(expected.get(i), arguments.get(i).type(), "Argument " + (i + 1) + " of " + function);
        }
    }
}
