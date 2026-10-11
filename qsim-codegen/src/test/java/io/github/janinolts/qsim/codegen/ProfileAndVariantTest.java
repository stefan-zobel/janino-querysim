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

package io.github.janinolts.qsim.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.Expression;
import io.github.janinolts.qsim.plan.Expressions;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.plan.PlanNode;
import io.github.janinolts.qsim.plan.RandomPlans;
import io.github.janinolts.qsim.plan.Schema;

import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The language features of the profiles, the metamorphic settings of the
 * generator and the negative programs change the generated code as intended.
 */
class ProfileAndVariantTest {

    private static final Schema SCHEMA = Schema.of(DataType.INT, DataType.BYTE, DataType.BOOLEAN, DataType.STRING);

    /** A projection of the operand of a feature, the feature and a second column. */
    private static PlanNode featurePlan(LanguageFeature f) {
        DataType type = f.operandType() == null ? DataType.INT : f.operandType();
        int column = switch (type) {
            case BYTE -> 1;
            case BOOLEAN -> 2;
            case STRING -> 3;
            default -> 0;
        };
        Expression operand = Expressions.col(column, type);
        return new PlanNode.Project(new PlanNode.Scan("t", SCHEMA, PlanNode.Layout.ROWS), List.of(
                new PlanNode.NamedExpression("f", new Expression.Feature(f, operand)),
                new PlanNode.NamedExpression("x", Expressions.add(Expressions.col(0, DataType.INT),
                        Expressions.lit(1)))));
    }

    private static String code(PlanNode plan, CodegenConfig config) {
        return String.join("\n", Generator.generate(plan, config).units().stream()
                .map(UnitRenderer::compilationUnit).toList());
    }

    @ParameterizedTest
    @EnumSource(LanguageFeature.class)
    void everyFeatureHasItsConstruct(LanguageFeature f) {
        Profile profile = Profile.valueOf(f.profile());
        for (Style style : Style.values()) {
            CodegenConfig config = CodegenConfig.of(style, false, 1).withProfile(profile);
            String code = code(featurePlan(f), config);
            String construct = switch (f) {
                case FINAL_LOCAL_ASSIGNMENT -> "final int lenient";
                case BINARY_NAME -> "java.util.Map$Entry";
                case PARAMETERIZED_ASSIGNMENT -> "new java.util.ArrayList<Integer>()";
                case GENERIC_ARRAY -> "new java.util.List<Object>[1]";
                case MULTI_CATCH -> "catch (IllegalStateException | UnsupportedOperationException";
                case INTERFACE_SUPER -> "Identity.super.identity(value)";
                case EFFECTIVELY_FINAL -> "return captured";
                case LOCAL_CLASS_MODIFIER -> "final class Box";
                case NULL_BOOLEAN_OR -> "|| true;";
                case VARARGS_BEFORE_BOXING, VARARGS_ARRAY -> "String f(int... a)";
                case CONDITIONAL_BOXED_TYPE -> " : 5;";
                case CONSTANT_CONDITIONAL -> "(true ? \"b\" : ";
                case CONSTANT_SHIFT -> " = 1 << 2;";
                case VARARGS_ARRAY_ELEMENT -> "new int[] {1, 2}";
            };
            assertTrue(code.contains(construct), () -> style + ": " + construct + "\n" + code);
            assertTrue(Generator.generate(featurePlan(f), config).unit().metadata().featureTags().contains(f.id()));
        }
    }

    @Test
    void aFeatureNeedsItsProfile() {
        PlanNode plan = featurePlan(LanguageFeature.MULTI_CATCH);
        assertThrows(IllegalArgumentException.class,
                () -> Generator.generate(plan, CodegenConfig.of(Style.WHOLE_STAGE, false, 1)));
        assertThrows(IllegalArgumentException.class, () -> Generator.generate(plan,
                CodegenConfig.of(Style.WHOLE_STAGE, false, 1).withProfile(Profile.LENIENT)));
    }

    @Test
    void theMetamorphicSettingsChangeTheCode() {
        // The same subexpression in two columns, and a decimal constant.
        Expression sum = Expressions.add(Expressions.col(0, DataType.INT), Expressions.lit(1));
        PlanNode plan = new PlanNode.Project(new PlanNode.Scan("t", SCHEMA, PlanNode.Layout.ROWS), List.of(
                new PlanNode.NamedExpression("a", Expressions.mul(sum, Expressions.lit(2))),
                new PlanNode.NamedExpression("b", Expressions.mul(sum, Expressions.lit(3))),
                new PlanNode.NamedExpression("c", Expressions.lit(new java.math.BigDecimal("1.5")))));
        CodegenConfig base = CodegenConfig.of(Style.WHOLE_STAGE, false, 1);
        String plain = code(plan, base);
        String reuse = code(plan, base.withReuseSubexpressions(true));
        assertTrue(count(reuse, " + ") < count(plain, " + "), reuse);
        String references = code(plan, base.withConstantsAsReferences(true));
        assertTrue(references.contains("(BigDecimal) references["), references);
        assertFalse(plain.contains("references[0]"));
        // The literal 1 twice (once per column), 2, 3 and 1.5.
        assertEquals(5, Generator.generate(plan, base.withConstantsAsReferences(true)).unit().references().length);
        assertNotEquals(base.hash(), base.withReuseSubexpressions(true).hash());
        // Settings added later leave the hashes of older configurations alone.
        assertEquals(base.hash(), CodegenConfig.of(Style.WHOLE_STAGE, false, 7).hash());

        String enumerable = code(plan, CodegenConfig.of(Style.ENUMERABLE, false, 1).withConstantsAsReferences(true));
        assertTrue(enumerable.contains("root.reference("), enumerable);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    @ParameterizedTest
    @EnumSource(NegativePrograms.Mutation.class)
    void aMutationInsertsStatementsIntoAMethod(NegativePrograms.Mutation m) {
        PlanNode plan = RandomPlans.withDefaults().generate(3).plan();
        for (Style style : List.of(Style.WHOLE_STAGE, Style.OPERATOR_CLASSES, Style.ENUMERABLE)) {
            GeneratedQuery q = Generator.generate(plan, CodegenConfig.of(style, false, 3));
            assertTrue(NegativePrograms.applies(q));
            GeneratedQuery mutated = NegativePrograms.apply(q, m, new SplittableRandom(1));
            String before = String.join("", q.units().stream().map(u -> u.body()).toList());
            String after = String.join("", mutated.units().stream().map(u -> u.body()).toList());
            assertTrue(after.toLowerCase().contains("negative") && !before.toLowerCase().contains("negative"), after);
            assertTrue(after.length() > before.length());
        }
    }
}
