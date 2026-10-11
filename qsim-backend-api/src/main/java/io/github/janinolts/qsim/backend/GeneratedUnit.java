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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One piece of generated code, compiled with one compiler instance.
 * <p>
 * The unit is the model from which the backends render what their API takes:
 * a class body plus setter calls, or a complete compilation unit (see
 * {@link UnitRenderer}). Type names are in source form (see
 * {@link TypeNames}).
 *
 * @param kind what the body contains
 * @param packageName the package of the class, or {@code ""}
 * @param className the simple name of the class
 * @param imports the imports, each as in an import declaration without
 *        {@code import} and {@code ;}, e.g. {@code java.util.List} or
 *        {@code java.util.*}
 * @param extendedClass the superclass, or {@code null}
 * @param implementedInterfaces the interfaces
 * @param body the class body, the expression or the statements
 * @param parameters the parameters of an expression or a script; empty for a
 *        class
 * @param returnType the type of an expression or the return type of a script
 *        ({@code void} for none); {@code null} for a class
 * @param instantiation how the main class is instantiated
 * @param references the run-time objects that the generated code refers to by
 *        index; not copied
 * @param metadata where the unit comes from
 */
public record GeneratedUnit(
        UnitKind kind,
        String packageName,
        String className,
        List<String> imports,
        String extendedClass,
        List<String> implementedInterfaces,
        String body,
        List<Parameter> parameters,
        String returnType,
        Instantiation instantiation,
        Object[] references,
        UnitMetadata metadata) {

    public GeneratedUnit {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(className, "className");
        imports = List.copyOf(imports);
        implementedInterfaces = List.copyOf(implementedInterfaces);
        Objects.requireNonNull(body, "body");
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(instantiation, "instantiation");
        Objects.requireNonNull(references, "references");
        Objects.requireNonNull(metadata, "metadata");
        if (kind == UnitKind.CLASS) {
            if (!parameters.isEmpty() || returnType != null) {
                throw new IllegalArgumentException("A class unit has no parameters and no return type");
            }
        } else {
            Objects.requireNonNull(returnType, "returnType");
            if (extendedClass != null || !implementedInterfaces.isEmpty()) {
                throw new IllegalArgumentException("An expression or a script has no superclass and no interfaces");
            }
            if (instantiation != Instantiation.NO_ARG_CONSTRUCTOR) {
                throw new IllegalArgumentException("An expression or a script is instantiated without arguments");
            }
        }
    }

    /** Returns the fully qualified name of the class. */
    public String qualifiedName() {
        return packageName.isEmpty() ? className : packageName + '.' + className;
    }

    /** Returns the name of the source file, e.g. for locations in compile errors. */
    public String sourceFileName() {
        return className + ".java";
    }

    @Override
    public String toString() {
        return "GeneratedUnit[" + kind + ' ' + qualifiedName() + ", " + metadata + ']';
    }

    /** Starts a unit whose body is a class body. */
    public static Builder ofClass(String packageName, String className, String classBody) {
        return new Builder(UnitKind.CLASS, packageName, className, classBody, null);
    }

    /** Starts a unit whose body is an expression of the given type. */
    public static Builder ofExpression(String packageName, String className, String expression, String type) {
        return new Builder(UnitKind.EXPRESSION, packageName, className, expression, type);
    }

    /** Starts a unit whose body is a sequence of statements with the given return type. */
    public static Builder ofScript(String packageName, String className, String statements, String returnType) {
        return new Builder(UnitKind.SCRIPT, packageName, className, statements, returnType);
    }

    /**
     * Collects the parts of a unit.
     */
    public static final class Builder {

        private final UnitKind kind;
        private final String packageName;
        private final String className;
        private final String body;
        private final String returnType;
        private final List<String> imports = new ArrayList<>();
        private String extendedClass;
        private final List<String> implementedInterfaces = new ArrayList<>();
        private final List<Parameter> parameters = new ArrayList<>();
        private Instantiation instantiation = Instantiation.NO_ARG_CONSTRUCTOR;
        private Object[] references = new Object[0];
        private UnitMetadata metadata = UnitMetadata.HAND_WRITTEN;

        private Builder(UnitKind kind, String packageName, String className, String body, String returnType) {
            this.kind = kind;
            this.packageName = packageName;
            this.className = className;
            this.body = body;
            this.returnType = returnType;
        }

        public Builder imports(String... imports) {
            this.imports.addAll(Arrays.asList(imports));
            return this;
        }

        public Builder extendedClass(String extendedClass) {
            this.extendedClass = extendedClass;
            return this;
        }

        public Builder implementedInterfaces(String... interfaces) {
            implementedInterfaces.addAll(Arrays.asList(interfaces));
            return this;
        }

        public Builder parameter(String type, String name) {
            parameters.add(new Parameter(type, name));
            return this;
        }

        public Builder instantiation(Instantiation instantiation) {
            this.instantiation = instantiation;
            return this;
        }

        public Builder references(Object... references) {
            this.references = references;
            return this;
        }

        public Builder metadata(UnitMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public GeneratedUnit build() {
            return new GeneratedUnit(kind, packageName, className, imports, extendedClass, implementedInterfaces,
                    body, parameters, returnType, instantiation, references, metadata);
        }
    }
}
