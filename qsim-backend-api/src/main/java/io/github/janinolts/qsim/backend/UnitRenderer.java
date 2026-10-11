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

package io.github.janinolts.qsim.backend;

import io.github.janinolts.qsim.runtime.Evaluator;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link GeneratedUnit} into the source text that an API takes: a
 * class body for the class body evaluators, a complete compilation unit for
 * {@code SimpleCompiler} and javac. Both renderings come from the same unit,
 * but their line numbers differ.
 * <p>
 * An expression or a script that is compiled through a class path is rendered
 * as a class that implements {@link Evaluator}: {@code evaluate(Object[])}
 * unboxes the arguments and invokes a static method {@code eval} whose body is
 * the expression or the script, as the evaluators of Janino do.
 */
public final class UnitRenderer {

    private static final String INDENT = "    ";

    private UnitRenderer() {
    }

    /** Returns the class body: the body of a class unit, or the members of the evaluator class. */
    public static String classBody(GeneratedUnit unit) {
        return switch (unit.kind()) {
            case CLASS -> unit.body();
            case EXPRESSION, SCRIPT -> evaluatorMembers(unit);
        };
    }

    /** Returns the interface names in binary form, including {@link Evaluator} for an expression or a script. */
    public static List<String> implementedInterfaces(GeneratedUnit unit) {
        if (unit.kind() == UnitKind.CLASS) {
            return unit.implementedInterfaces();
        }
        List<String> interfaces = new ArrayList<>(unit.implementedInterfaces());
        interfaces.add(Evaluator.class.getName());
        return List.copyOf(interfaces);
    }

    /** Returns a complete compilation unit with package, imports and a public class. */
    public static String compilationUnit(GeneratedUnit unit) {
        StringBuilder sb = new StringBuilder();
        if (!unit.packageName().isEmpty()) {
            sb.append("package ").append(unit.packageName()).append(";\n\n");
        }
        for (String imp : unit.imports()) {
            sb.append("import ").append(imp).append(";\n");
        }
        if (!unit.imports().isEmpty()) {
            sb.append('\n');
        }
        sb.append("public class ").append(unit.className());
        if (unit.extendedClass() != null) {
            sb.append(" extends ").append(TypeNames.sourceForm(unit.extendedClass()));
        }
        List<String> interfaces = implementedInterfaces(unit);
        for (int i = 0; i < interfaces.size(); i++) {
            sb.append(i == 0 ? " implements " : ", ").append(TypeNames.sourceForm(interfaces.get(i)));
        }
        sb.append(" {\n");
        appendLines(sb, classBody(unit));
        sb.append("}\n");
        return sb.toString();
    }

    private static String evaluatorMembers(GeneratedUnit unit) {
        boolean isVoid = "void".equals(unit.returnType().strip());
        List<Parameter> parameters = unit.parameters();

        StringBuilder invocation = new StringBuilder("eval(");
        StringBuilder declaration = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter p = parameters.get(i);
            String type = TypeNames.sourceForm(p.type());
            invocation.append(i == 0 ? "" : ", ")
                    .append('(').append(TypeNames.sourceForm(TypeNames.boxed(p.type()))).append(") arguments[")
                    .append(i).append(']');
            declaration.append(i == 0 ? "" : ", ").append(type).append(' ').append(p.name());
        }
        invocation.append(')');

        StringBuilder sb = new StringBuilder();
        sb.append(INDENT).append("public Object evaluate(Object[] arguments) throws Exception {\n");
        if (isVoid) {
            sb.append(INDENT).append(INDENT).append(invocation).append(";\n");
            sb.append(INDENT).append(INDENT).append("return null;\n");
        } else {
            sb.append(INDENT).append(INDENT).append("return ").append(invocation).append(";\n");
        }
        sb.append(INDENT).append("}\n\n");
        sb.append(INDENT).append("private static ").append(TypeNames.sourceForm(unit.returnType().strip()))
                .append(" eval(").append(declaration).append(") throws Exception {\n");
        if (unit.kind() == UnitKind.EXPRESSION) {
            sb.append(INDENT).append(INDENT).append("return ").append(unit.body().strip()).append(";\n");
        } else {
            appendLines(sb, unit.body());
        }
        sb.append(INDENT).append("}\n");
        return sb.toString();
    }

    private static void appendLines(StringBuilder sb, String text) {
        sb.append(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            sb.append('\n');
        }
    }
}
