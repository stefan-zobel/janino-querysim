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
import io.github.janinolts.qsim.plan.Expression.ComparisonOp;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Short factory methods for expressions, for hand-written plans and tests.
 */
public final class Expressions {

    private Expressions() {
    }

    public static Expression col(int index, DataType type) {
        return new Expression.Column(index, type);
    }

    public static Expression lit(DataType type, Object value) {
        return new Expression.Literal(type, value);
    }

    public static Expression nullOf(DataType type) {
        return new Expression.Literal(type, null);
    }

    public static Expression lit(boolean value) {
        return lit(DataType.BOOLEAN, value);
    }

    public static Expression lit(byte value) {
        return lit(DataType.BYTE, value);
    }

    public static Expression lit(short value) {
        return lit(DataType.SHORT, value);
    }

    public static Expression lit(int value) {
        return lit(DataType.INT, value);
    }

    public static Expression lit(long value) {
        return lit(DataType.LONG, value);
    }

    public static Expression lit(float value) {
        return lit(DataType.FLOAT, value);
    }

    public static Expression lit(double value) {
        return lit(DataType.DOUBLE, value);
    }

    public static Expression lit(String value) {
        return lit(DataType.STRING, value);
    }

    public static Expression lit(BigDecimal value) {
        return lit(DataType.DECIMAL, value);
    }

    public static Expression date(int days) {
        return lit(DataType.DATE, days);
    }

    public static Expression add(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.ADD, l, r);
    }

    public static Expression sub(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.SUBTRACT, l, r);
    }

    public static Expression mul(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.MULTIPLY, l, r);
    }

    public static Expression div(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.DIVIDE, l, r);
    }

    public static Expression rem(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.REMAINDER, l, r);
    }

    public static Expression neg(Expression e) {
        return new Expression.Negate(e);
    }

    public static Expression cmp(ComparisonOp op, Expression l, Expression r) {
        return new Expression.Comparison(op, l, r);
    }

    public static Expression eq(Expression l, Expression r) {
        return cmp(ComparisonOp.EQ, l, r);
    }

    public static Expression lt(Expression l, Expression r) {
        return cmp(ComparisonOp.LT, l, r);
    }

    public static Expression gt(Expression l, Expression r) {
        return cmp(ComparisonOp.GT, l, r);
    }

    public static Expression and(Expression l, Expression r) {
        return new Expression.And(l, r);
    }

    public static Expression or(Expression l, Expression r) {
        return new Expression.Or(l, r);
    }

    public static Expression not(Expression e) {
        return new Expression.Not(e);
    }

    public static Expression isNull(Expression e) {
        return new Expression.IsNull(e, false);
    }

    public static Expression isNotNull(Expression e) {
        return new Expression.IsNull(e, true);
    }

    public static Expression between(Expression v, Expression lo, Expression hi) {
        return new Expression.Between(v, lo, hi);
    }

    public static Expression in(Expression v, Expression... list) {
        return new Expression.In(v, Arrays.asList(list));
    }

    public static Expression.When when(Expression condition, Expression result) {
        return new Expression.When(condition, result);
    }

    public static Expression caseWhen(List<Expression.When> whens, Expression otherwise) {
        return new Expression.Case(whens, otherwise);
    }

    public static Expression iff(Expression condition, Expression whenTrue, Expression whenFalse) {
        return new Expression.If(condition, whenTrue, whenFalse);
    }

    public static Expression coalesce(Expression... operands) {
        return new Expression.Coalesce(Arrays.asList(operands));
    }

    public static Expression cast(Expression e, DataType target) {
        return new Expression.Cast(e, target);
    }

    public static Expression call(Expression.StringFunction f, Expression... arguments) {
        return new Expression.StringCall(f, Arrays.asList(arguments));
    }

    public static Expression call(Expression.MathFunction f, Expression... arguments) {
        return new Expression.MathCall(f, Arrays.asList(arguments));
    }

    public static Expression like(Expression s, Expression pattern) {
        return new Expression.Like(s, pattern);
    }

    public static Expression shift(Expression.ShiftOp op, Expression value, Expression distance) {
        return new Expression.Shift(op, value, distance);
    }

    public static Expression bitwise(Expression.BitwiseOp op, Expression l, Expression r) {
        return new Expression.Bitwise(op, l, r);
    }
}
