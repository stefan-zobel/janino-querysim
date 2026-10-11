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

import io.github.janinolts.qsim.plan.DataType;
import io.github.janinolts.qsim.plan.LanguageFeature;
import io.github.janinolts.qsim.runtime.Identity;
import io.github.janinolts.qsim.runtime.linq.Function0;

/**
 * Writes the construct of a {@link LanguageFeature} around the null-pair of
 * its operand, as the register of deviations shows it. The features of the
 * profiles {@code LENIENT} and {@code MODERN} pass the value through an
 * object: boxed, through the construct, and unboxed again. Local variables
 * have prefixes that no field of a style uses, e.g. not {@code list}, so that
 * they hide none.
 */
final class FeatureCodegen {

    private final CodegenContext ctx;
    private final CodeWriter w;

    FeatureCodegen(CodegenContext ctx, CodeWriter w) {
        this.ctx = ctx;
        this.w = w;
    }

    /** Writes the construct and returns the null-pair of its result. */
    ExprCode gen(LanguageFeature feature, ExprCode operand) {
        ctx.tag(feature.id());
        String n = ctx.fresh("");
        return switch (feature) {
            case FINAL_LOCAL_ASSIGNMENT -> {
                DataType t = operand.type();
                String value = "lenient" + n;
                w.line("final " + JavaTypes.javaType(t) + " " + value + " = " + JavaTypes.defaultValue(t) + ";");
                w.line(value + " = " + operand.value() + ";");
                yield new ExprCode(operand.isNull(), value, t);
            }
            case BINARY_NAME -> {
                String entry = "entry" + n;
                w.line("java.util.Map$Entry " + entry + " = new java.util.AbstractMap$SimpleEntry((Object) "
                        + boxed(operand) + ", (Object) null);");
                yield unboxed(entry + ".getKey()", operand.type(), n);
            }
            case PARAMETERIZED_ASSIGNMENT -> {
                String list = "typedList" + n;
                w.line("java.util.List<String> " + list + " = new java.util.ArrayList<Integer>();");
                w.line(list + ".add((Object) " + boxed(operand) + ");");
                yield unboxed(list + ".get(0)", operand.type(), n);
            }
            case GENERIC_ARRAY -> {
                String lists = "typedLists" + n;
                if (ctx.config().style() == Style.EXPRESSION && w.atInitialLevel()) {
                    // A statement of the script itself, not one in a block of it.
                    ctx.tag("script-generic-array");
                }
                w.line("java.util.List<Object>[] " + lists + " = new java.util.List<Object>[1];");
                w.line(lists + "[0] = new java.util.ArrayList();");
                w.line(lists + "[0].add((Object) " + boxed(operand) + ");");
                yield unboxed(lists + "[0].get(0)", operand.type(), n);
            }
            case MULTI_CATCH -> {
                DataType t = operand.type();
                String value = "caught" + n;
                String e = "e" + n;
                w.line(JavaTypes.javaType(t) + " " + value + " = " + JavaTypes.defaultValue(t) + ";");
                w.open("try");
                w.line(value + " = " + operand.value() + ";");
                w.reopen("catch (IllegalStateException | UnsupportedOperationException " + e + ")");
                w.line("throw " + e + ";");
                w.close();
                yield new ExprCode(operand.isNull(), value, t);
            }
            case INTERFACE_SUPER -> {
                ctx.tag("inner");
                ctx.tag("iface8");
                String local = "Same" + n;
                String identity = Identity.class.getName();
                ctx.tag("local-class");
                w.open("class " + local + " implements " + identity);
                w.open("public Object identity(Object value)");
                w.line("return " + identity + ".super.identity(value);");
                w.close();
                w.close();
                String same = "same" + n;
                w.line("Object " + same + " = new " + local + "().identity(" + boxed(operand) + ");");
                yield unboxed(same, operand.type(), n);
            }
            case EFFECTIVELY_FINAL -> {
                ctx.tag("inner");
                String captured = "captured" + n;
                String function = "function" + n;
                String read = "read" + n;
                w.line("Object " + captured + " = " + boxed(operand) + ";");
                w.open(Function0.class.getName() + " " + function + " = new " + Function0.class.getName() + "()");
                w.open("public Object apply()");
                w.line("return " + captured + ";");
                w.close();
                w.close(";");
                w.line("Object " + read + " = " + function + ".apply();");
                yield unboxed(read, operand.type(), n);
            }
            case LOCAL_CLASS_MODIFIER -> {
                ctx.tag("inner");
                String local = "Box" + n;
                String box = "box" + n;
                ctx.tag("local-class");
                w.open("final class " + local);
                w.line("Object content;");
                w.close();
                w.line(local + " " + box + " = new " + local + "();");
                w.line(box + ".content = " + boxed(operand) + ";");
                yield unboxed(box + ".content", operand.type(), n);
            }
            case NULL_BOOLEAN_OR -> {
                String z = "z" + n;
                String value = "value" + n;
                w.line("Boolean " + z + " = " + boxed(operand) + ";");
                w.line("boolean " + value + " = " + z + " || true;");
                yield new ExprCode("false", value, DataType.BOOLEAN);
            }
            case VARARGS_BEFORE_BOXING, VARARGS_ARRAY -> {
                ctx.tag("inner");
                String local = "Overloads" + n;
                ctx.tag("local-class");
                w.open("class " + local);
                w.open("String f(Object o)");
                w.line("return \"Object\";");
                w.close();
                w.open("String f(int... a)");
                w.line("return \"int...\";");
                w.close();
                w.close();
                String argument = feature == LanguageFeature.VARARGS_ARRAY
                        ? "new int[] {" + operand.value() + "}" : operand.value();
                yield guarded(operand, DataType.STRING, n, "new " + local + "().f(" + argument + ")");
            }
            case CONDITIONAL_BOXED_TYPE -> {
                String isNull = "isNull" + n;
                String value = "value" + n;
                String b = "b" + n;
                String x = "x" + n;
                w.line("boolean " + isNull + " = " + operand.isNull() + ";");
                w.line("String " + value + " = null;");
                w.open("if (!" + isNull + ")");
                w.line("Byte " + b + " = Byte.valueOf(" + operand.value() + ");");
                w.line("Object " + x + " = (" + operand.value() + " & 1) == 0 ? " + b + " : 5;");
                w.line(value + " = " + x + ".getClass().getName() + \":\" + " + x + ";");
                w.close();
                yield new ExprCode(isNull, value, DataType.STRING);
            }
            case CONSTANT_CONDITIONAL -> {
                String s = "s" + n;
                String value = "value" + n;
                w.line("String " + s + " = " + operand.value() + ";");
                w.line("boolean " + value + " = (\"a\" + (true ? \"b\" : " + s + ")) == \"ab\";");
                yield new ExprCode("false", value, DataType.BOOLEAN);
            }
            case CONSTANT_SHIFT -> {
                String shifted = "shifted" + n;
                w.line("byte " + shifted + " = 1 << 2;");
                yield guarded(operand, DataType.INT, n, operand.value() + " + " + shifted);
            }
            case VARARGS_ARRAY_ELEMENT -> {
                ctx.tag("inner");
                String local = "Varargs" + n;
                ctx.tag("local-class");
                w.open("class " + local);
                w.open("int f(Integer i, Object... a)");
                w.line("return i.intValue() + a.length;");
                w.close();
                w.close();
                yield guarded(operand, DataType.INT, n,
                        "new " + local + "().f(" + operand.value() + ", new int[] {1, 2})");
            }
        };
    }

    /** Returns an expression of type {@code Object} for a null-pair: the boxed value, or {@code null}. */
    private static String boxed(ExprCode c) {
        String boxed = JavaTypes.box(c.type(), c.value());
        return c.isNull().equals("false") ? boxed : "(" + c.isNull() + " ? null : " + boxed + ")";
    }

    /** Declares the null-pair of an object that holds a boxed value of the type, or {@code null}. */
    private ExprCode unboxed(String object, DataType type, String n) {
        String o = "object" + n;
        String isNull = "isNull" + n;
        String value = "value" + n;
        w.line("Object " + o + " = " + object + ";");
        w.line("boolean " + isNull + " = " + o + " == null;");
        w.line(JavaTypes.javaType(type) + " " + value + " = " + isNull + " ? " + JavaTypes.defaultValue(type) + " : "
                + JavaTypes.unbox(type, o) + ";");
        return new ExprCode(isNull, value, type);
    }

    /** Declares a null-pair that is {@code NULL} if the operand is, and the value of the expression otherwise. */
    private ExprCode guarded(ExprCode operand, DataType type, String n, String expression) {
        String isNull = "isNull" + n;
        String value = "value" + n;
        w.line("boolean " + isNull + " = " + operand.isNull() + ";");
        w.line(JavaTypes.javaType(type) + " " + value + " = " + JavaTypes.defaultValue(type) + ";");
        w.open("if (!" + isNull + ")");
        w.line(value + " = " + expression + ";");
        w.close();
        return new ExprCode(isNull, value, type);
    }
}
