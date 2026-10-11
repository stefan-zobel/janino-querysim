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

import io.github.janinolts.qsim.plan.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a generator keeps track of while it generates one unit: fresh names,
 * fields, the statements that initialize them, and the feature tags of the
 * code. Names are numbered per unit, so the same plan and configuration always
 * give the same code.
 */
final class CodegenContext {

    private final CodegenConfig config;
    private int counter;
    private final List<String> fields = new ArrayList<>();
    private final List<String> initStatements = new ArrayList<>();
    private final Set<String> featureTags = new LinkedHashSet<>();
    private final List<String> methods = new ArrayList<>();
    private final List<String> nestedClasses = new ArrayList<>();
    private final List<Object> references = new ArrayList<>();
    private String referencePattern;
    private final Map<CodeWriter, Map<Computed, Reusable>> computed = new IdentityHashMap<>();

    /** An expression as computed over an input. */
    private record Computed(InputAccess access, Expression expression) {
    }

    /** The null-pair of a computed expression, and the block of the writer in which it is declared. */
    private record Reusable(ExprCode code, int block) {
    }

    CodegenContext(CodegenConfig config) {
        this.config = config;
    }

    CodegenConfig config() {
        return config;
    }

    boolean ansi() {
        return config.ansi();
    }

    /** Returns a new name with the given prefix, e.g. {@code value_7}. */
    String fresh(String prefix) {
        return prefix + '_' + counter++;
    }

    /** Declares a field, e.g. {@code private int scan_index;}. */
    void addField(String declaration) {
        fields.add(declaration);
    }

    /** Adds a statement to the initialization of the fields. */
    void addInit(String statement) {
        initStatements.add(statement);
    }

    List<String> fields() {
        return fields;
    }

    List<String> initStatements() {
        return initStatements;
    }

    /** Adds a method, e.g. a helper of a split projection; its text ends with a newline. */
    void addMethod(String text) {
        methods.add(text);
    }

    List<String> methods() {
        return methods;
    }

    /** Adds a nested class, e.g. an aggregation buffer or a comparator; its text ends with a newline. */
    void addNestedClass(String text) {
        nestedClasses.add(text);
    }

    List<String> nestedClasses() {
        return nestedClasses;
    }

    /** Declares a field if it is not declared yet. */
    void addFieldOnce(String declaration) {
        if (!fields.contains(declaration)) {
            fields.add(declaration);
        }
    }

    /**
     * Enables references: generated code reads the reference with index
     * {@code i} with the expression {@code String.format(pattern, i)}, e.g.
     * {@code references[%d]}.
     */
    void enableReferences(String pattern) {
        this.referencePattern = pattern;
    }

    /**
     * Adds a run-time object to the references, if the configuration asks for
     * constants as references and the unit has references, and returns the
     * expression that reads it, of type {@code Object}; otherwise returns
     * {@code null}.
     */
    String constantReference(Object value) {
        if (!config.constantsAsReferences() || referencePattern == null) {
            return null;
        }
        tag("ref-cast");
        references.add(value);
        return String.format(referencePattern, references.size() - 1);
    }

    /** Returns the number of references so far. */
    int referenceCount() {
        return references.size();
    }

    /** Removes the references from the given index on, e.g. those of code that is generated again. */
    void truncateReferences(int count) {
        references.subList(count, references.size()).clear();
    }

    /** Returns the references of the unit, in the order of their indexes. */
    Object[] references() {
        return references.toArray();
    }

    /**
     * Returns the null-pair of an expression computed over the same input
     * whose variables are still in scope at the current position of the
     * writer, if the configuration asks for the reuse of subexpressions;
     * otherwise {@code null}.
     */
    ExprCode reusable(CodeWriter w, InputAccess access, Expression e) {
        if (!config.reuseSubexpressions()) {
            return null;
        }
        Reusable r = computed.getOrDefault(w, Map.of()).get(new Computed(access, e));
        return r != null && w.isOpen(r.block()) ? r.code() : null;
    }

    /** Records the null-pair of an expression, declared in the current block of the writer. */
    void computed(CodeWriter w, InputAccess access, Expression e, ExprCode code) {
        if (config.reuseSubexpressions()) {
            computed.computeIfAbsent(w, x -> new HashMap<>()).put(new Computed(access, e),
                    new Reusable(code, w.block()));
        }
    }

    /** Records that the code contains a pattern, e.g. {@code null-pair}. */
    void tag(String featureTag) {
        featureTags.add(featureTag);
    }

    List<String> featureTags() {
        return List.copyOf(featureTags);
    }
}
