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

package io.github.janinolts.qsim.backend.janino;

import io.github.janinolts.qsim.backend.CompileOptions;
import io.github.janinolts.qsim.backend.CompileResult;
import io.github.janinolts.qsim.backend.CompilerBackend;
import io.github.janinolts.qsim.backend.CompilerPhases;
import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.Parameter;
import io.github.janinolts.qsim.backend.TypeNames;
import io.github.janinolts.qsim.backend.UnitKind;
import io.github.janinolts.qsim.backend.UnitRenderer;
import io.github.janinolts.qsim.runtime.Evaluator;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IClassBodyEvaluator;
import org.codehaus.commons.compiler.ICompilerFactory;
import org.codehaus.commons.compiler.Location;
import org.codehaus.janino.ClassBodyEvaluator;
import org.codehaus.janino.ClassLoaderIClassLoader;
import org.codehaus.janino.ExpressionEvaluator;
import org.codehaus.janino.Java;
import org.codehaus.janino.JaninoOption;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.ScriptEvaluator;
import org.codehaus.janino.SimpleCompiler;
import org.codehaus.janino.UnitCompiler;
import org.codehaus.janino.util.ClassFile;

/**
 * The backend for one Janino version.
 * <p>
 * This class is compiled against the API of Janino 3.1.12 and calls it
 * statically typed. It is loaded by a class loader of its own, together with
 * the JARs of one Janino version; janino-lts keeps the public API of 3.1.12,
 * so the same class runs with every version. Only the compliance mode, which
 * 3.1.12 does not have, is looked up by name.
 * <p>
 * The harness creates instances by reflection through the constructor
 * {@link #JaninoBackend(String)} and uses them through
 * {@link CompilerBackend} and {@link CompilerPhases}.
 */
public final class JaninoBackend implements CompilerBackend, CompilerPhases {

    private static final String COMPLIANCE_OPTION = "JAVAC_COMPLIANCE";

    private final String id;
    private final String version;

    /**
     * Creates the backend.
     *
     * @param id the identifier, e.g. {@code J3112} or {@code JLTS}
     */
    public JaninoBackend(String id) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = JaninoVersion.of(SimpleCompiler.class);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public CompileResult compile(GeneratedUnit unit, CompileOptions options) {
        if (!options.apiPath().accepts(unit.kind())) {
            throw new IllegalArgumentException(options.apiPath() + " does not accept a unit of kind " + unit.kind());
        }
        EnumSet<JaninoOption> compliance = null;
        if (options.compliance()) {
            compliance = complianceOption();
            if (compliance == null) {
                return CompileResult.unsupported("Janino " + version + " has no compliance mode");
            }
        }
        Classes classes = new Classes(unit, options.parent());
        try {
            return switch (options.apiPath()) {
                case CLASS_BODY -> compileClassBody(unit, options, classes, compliance);
                case SIMPLE_COMPILER -> compileSimple(unit, options, compliance);
                case COMMONS_COMPILER -> compileCommons(unit, options, classes, compliance);
                case EXPRESSION -> compileExpression(unit, options, classes, compliance);
                case SCRIPT -> compileScript(unit, options, classes, compliance);
            };
        } catch (CompileException e) {
            Location location = e.getLocation();
            return CompileResult.rejected(e.getMessage(),
                    location == null ? 0 : location.getLineNumber(),
                    location == null ? 0 : location.getColumnNumber(), e);
        } catch (LinkageError e) {
            return CompileResult.invalidClass(Map.of(), e);
        } catch (RuntimeException | StackOverflowError | IOException | ClassNotFoundException e) {
            return CompileResult.internalError(e);
        }
    }

    /** Parses the compilation unit as {@code SimpleCompiler.cook} does. */
    @Override
    public Object parse(GeneratedUnit unit, CompileOptions options) throws CompileException, IOException {
        Scanner scanner = new Scanner(unit.sourceFileName(), new StringReader(UnitRenderer.compilationUnit(unit)));
        return new Parser(scanner).parseAbstractCompilationUnit();
    }

    /**
     * Compiles a syntax tree as {@code SimpleCompiler.cook} does; without a
     * target version in the options, the compiler keeps its default.
     */
    @Override
    public Map<String, byte[]> compile(Object syntaxTree, CompileOptions options) throws CompileException {
        UnitCompiler unitCompiler = new UnitCompiler((Java.AbstractCompilationUnit) syntaxTree,
                new ClassLoaderIClassLoader(options.parent()));
        if (options.compliance()) {
            EnumSet<JaninoOption> compliance = complianceOption();
            if (compliance == null) {
                throw new UnsupportedOperationException("Janino " + version + " has no compliance mode");
            }
            unitCompiler.options(compliance);
        }
        if (options.targetVersion() != CompileOptions.DEFAULT_TARGET_VERSION) {
            unitCompiler.setTargetVersion(options.targetVersion());
        }
        List<ClassFile> classFiles = new ArrayList<>();
        unitCompiler.compileUnit(options.debugSource(), options.debugLines(), options.debugVars(), classFiles::add);
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (ClassFile cf : classFiles) {
            result.put(cf.getThisClassName(), cf.toByteArray());
        }
        return result;
    }

    /** The way Spark compiles: setters, then {@code cook(fileName, body)}. */
    private static CompileResult compileClassBody(GeneratedUnit unit, CompileOptions options, Classes classes,
            EnumSet<JaninoOption> compliance) throws CompileException, ClassNotFoundException {
        ClassBodyEvaluator cbe = new ClassBodyEvaluator();
        cbe.setParentClassLoader(options.parent());
        cbe.setClassName(unit.qualifiedName());
        cbe.setDefaultImports(classes.imports);
        if (classes.extendedClass != null) {
            cbe.setExtendedClass(classes.extendedClass);
        }
        cbe.setImplementedInterfaces(classes.interfaces);
        cbe.setDebuggingInformation(options.debugSource(), options.debugLines(), options.debugVars());
        if (options.targetVersion() != CompileOptions.DEFAULT_TARGET_VERSION) {
            cbe.setTargetVersion(options.targetVersion());
        }
        if (compliance != null) {
            cbe.options(withOptions(cbe.options(), compliance));
        }
        cbe.cook(unit.sourceFileName(), UnitRenderer.classBody(unit));
        return load(unit, cbe.getBytecodes(), cbe::getClazz);
    }

    /** The way Flink compiles: a complete compilation unit, then {@code loadClass}. */
    private static CompileResult compileSimple(GeneratedUnit unit, CompileOptions options,
            EnumSet<JaninoOption> compliance) throws CompileException, ClassNotFoundException {
        SimpleCompiler sc = new SimpleCompiler();
        sc.setParentClassLoader(options.parent());
        sc.setDebuggingInformation(options.debugSource(), options.debugLines(), options.debugVars());
        if (options.targetVersion() != CompileOptions.DEFAULT_TARGET_VERSION) {
            sc.setTargetVersion(options.targetVersion());
        }
        if (compliance != null) {
            sc.options(withOptions(sc.options(), compliance));
        }
        sc.cook(unit.sourceFileName(), UnitRenderer.compilationUnit(unit));
        return load(unit, sc.getBytecodes(), () -> sc.getClassLoader().loadClass(unit.qualifiedName()));
    }

    /**
     * The way Calcite compiles: the {@code commons-compiler} interfaces. Calcite
     * also uses {@code createInstance(Reader)}, which is {@code cook} followed
     * by {@code getClazz().newInstance()}; the instantiation is left to the
     * harness, so that its failures are not taken for compile errors.
     */
    private static CompileResult compileCommons(GeneratedUnit unit, CompileOptions options, Classes classes,
            EnumSet<JaninoOption> compliance) throws CompileException, IOException, ClassNotFoundException {
        ICompilerFactory factory;
        try {
            factory = CompilerFactoryFactory.getDefaultCompilerFactory(JaninoBackend.class.getClassLoader());
        } catch (Exception e) {
            throw new IllegalStateException("No compiler factory in the class loader of " + JaninoBackend.class, e);
        }
        IClassBodyEvaluator cbe = factory.newClassBodyEvaluator();
        cbe.setClassName(unit.qualifiedName());
        if (classes.extendedClass != null) {
            cbe.setExtendedClass(classes.extendedClass);
        }
        cbe.setImplementedInterfaces(classes.interfaces);
        cbe.setParentClassLoader(options.parent());
        cbe.setDefaultImports(classes.imports);
        cbe.setDebuggingInformation(options.debugSource(), options.debugLines(), options.debugVars());
        if (options.targetVersion() != CompileOptions.DEFAULT_TARGET_VERSION) {
            cbe.setTargetVersion(options.targetVersion());
        }
        if (compliance != null) {
            if (!(cbe instanceof ClassBodyEvaluator janinoCbe)) {
                throw new IllegalStateException("Not a Janino class body evaluator: " + cbe.getClass());
            }
            janinoCbe.options(withOptions(janinoCbe.options(), compliance));
        }
        cbe.cook(unit.sourceFileName(), new StringReader(UnitRenderer.classBody(unit)));
        return load(unit, cbe.getBytecodes(), cbe::getClazz);
    }

    private static CompileResult compileExpression(GeneratedUnit unit, CompileOptions options, Classes classes,
            EnumSet<JaninoOption> compliance) throws CompileException {
        ExpressionEvaluator ee = new ExpressionEvaluator();
        ee.setParentClassLoader(options.parent());
        ee.setClassName(unit.qualifiedName());
        ee.setDefaultImports(classes.imports);
        ee.setParameters(classes.parameterNames, classes.parameterTypes);
        ee.setExpressionType(classes.returnType);
        ee.setDebuggingInformation(options.debugSource(), options.debugLines(), options.debugVars());
        if (options.targetVersion() != CompileOptions.DEFAULT_TARGET_VERSION) {
            ee.setTargetVersion(options.targetVersion());
        }
        if (compliance != null) {
            ee.options(withOptions(ee.options(), compliance));
        }
        ee.cook(unit.sourceFileName(), unit.body());
        Map<String, byte[]> classFiles = ee.getBytecodes();
        Class<?> mainClass;
        try {
            mainClass = ee.getClazz();
        } catch (LinkageError e) {
            return CompileResult.invalidClass(classFiles, e);
        }
        Evaluator evaluator = arguments -> {
            try {
                return ee.evaluate(arguments);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            }
        };
        return CompileResult.ok(mainClass, evaluator, classFiles);
    }

    private static CompileResult compileScript(GeneratedUnit unit, CompileOptions options, Classes classes,
            EnumSet<JaninoOption> compliance) throws CompileException {
        ScriptEvaluator se = new ScriptEvaluator();
        se.setParentClassLoader(options.parent());
        se.setClassName(unit.qualifiedName());
        se.setDefaultImports(classes.imports);
        se.setParameters(classes.parameterNames, classes.parameterTypes);
        se.setReturnType(classes.returnType);
        se.setDebuggingInformation(options.debugSource(), options.debugLines(), options.debugVars());
        if (options.targetVersion() != CompileOptions.DEFAULT_TARGET_VERSION) {
            se.setTargetVersion(options.targetVersion());
        }
        if (compliance != null) {
            se.options(withOptions(se.options(), compliance));
        }
        se.cook(unit.sourceFileName(), unit.body());
        Map<String, byte[]> classFiles = se.getBytecodes();
        Class<?> mainClass;
        try {
            mainClass = se.getClazz();
        } catch (LinkageError e) {
            return CompileResult.invalidClass(classFiles, e);
        }
        Evaluator evaluator = arguments -> {
            try {
                return se.evaluate(arguments);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            }
        };
        return CompileResult.ok(mainClass, evaluator, classFiles);
    }

    /** Loads the main class; a class that the JVM rejects keeps its class files in the result. */
    @FunctionalInterface
    private interface MainClassLoader {
        Class<?> load() throws ClassNotFoundException;
    }

    private static CompileResult load(GeneratedUnit unit, Map<String, byte[]> classFiles, MainClassLoader loader)
            throws ClassNotFoundException {
        try {
            Class<?> mainClass = loader.load();
            return CompileResult.ok(mainClass, evaluatorOf(unit, mainClass), classFiles);
        } catch (LinkageError e) {
            return CompileResult.invalidClass(classFiles, e);
        }
    }

    /**
     * Returns the evaluator of an expression or a script compiled through a
     * class path, whose main class implements {@link Evaluator}; {@code null}
     * for a class. Instantiating such a class runs no generated code.
     */
    private static Evaluator evaluatorOf(GeneratedUnit unit, Class<?> mainClass) {
        if (unit.kind() == UnitKind.CLASS) {
            return null;
        }
        try {
            return mainClass.asSubclass(Evaluator.class).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate the evaluator class " + mainClass.getName(), e);
        }
    }

    /**
     * Returns the compliance option as a set, or {@code null} if this Janino
     * version does not have it.
     */
    private static EnumSet<JaninoOption> complianceOption() {
        try {
            return EnumSet.of(JaninoOption.valueOf(COMPLIANCE_OPTION));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EnumSet<JaninoOption> withOptions(EnumSet<JaninoOption> current, EnumSet<JaninoOption> added) {
        EnumSet<JaninoOption> result = EnumSet.noneOf(JaninoOption.class);
        if (current != null) {
            result.addAll(current);
        }
        result.addAll(added);
        return result;
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Error error) {
            throw error;
        }
        return cause instanceof Exception exception ? exception : e;
    }

    /** The types of a unit, resolved through the parent class loader. */
    private static final class Classes {

        final String[] imports;
        final Class<?> extendedClass;
        final Class<?>[] interfaces;
        final String[] parameterNames;
        final Class<?>[] parameterTypes;
        final Class<?> returnType;

        Classes(GeneratedUnit unit, ClassLoader loader) {
            imports = unit.imports().toArray(new String[0]);
            extendedClass = unit.extendedClass() == null ? null : TypeNames.resolve(unit.extendedClass(), loader);
            interfaces = resolveAll(UnitRenderer.implementedInterfaces(unit), loader);
            List<Parameter> parameters = unit.parameters();
            parameterNames = parameters.stream().map(Parameter::name).toArray(String[]::new);
            parameterTypes = resolveAll(parameters.stream().map(Parameter::type).toList(), loader);
            returnType = unit.returnType() == null ? null : TypeNames.resolve(unit.returnType(), loader);
        }

        private static Class<?>[] resolveAll(List<String> typeNames, ClassLoader loader) {
            return typeNames.stream().map(name -> TypeNames.resolve(name, loader)).toArray(Class<?>[]::new);
        }
    }
}
