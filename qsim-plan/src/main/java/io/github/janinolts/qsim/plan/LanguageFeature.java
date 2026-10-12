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
import java.util.Objects;

/**
 * A language construct whose treatment differs between Janino and javac, as
 * the register of deviations of janino-lts or its change log describes it.
 * A unit of a language profile other than {@code STRICT} contains exactly one
 * of them, as an {@link Expression.Feature} around an operand: the generated
 * code computes the value of the feature with the construct.
 * <p>
 * The features of the profiles {@code LENIENT} and {@code MODERN} pass the
 * value of their operand through unchanged; only the acceptance differs. The
 * features of the profile {@code PROBE} compute a value that differs between
 * javac and Janino, or that only javac compiles; the interpreter computes
 * either, see {@link Semantics}.
 */
public enum LanguageFeature {

    /** {@code final T x = v; x = v;}: javac rejects the assignment to a {@code final} local variable. */
    FINAL_LOCAL_ASSIGNMENT("L-04", "LENIENT", null, false, "0", false, null),

    /** {@code java.util.Map$Entry e = new java.util.AbstractMap$SimpleEntry(...)}: binary names in source. */
    BINARY_NAME("L-42", "LENIENT", null, false, "0", false, null),

    /** {@code java.util.List<String> l = new java.util.ArrayList<Integer>();}: incompatible type arguments. */
    PARAMETERIZED_ASSIGNMENT("G-07", "LENIENT", null, false, "0", false, null),

    /** {@code new java.util.List<Object>[1]}: generic array creation. */
    GENERIC_ARRAY("G-09", "LENIENT", null, false, "0", false, null),

    /** {@code catch (IllegalStateException | UnsupportedOperationException e)}: multi-catch. */
    MULTI_CATCH("#21", "MODERN", null, true, "3.1.16", false, null),

    /** {@code Identity.super.identity(v)} in a local class: a qualified superinterface method invocation. */
    INTERFACE_SUPER("#22", "MODERN", null, true, "3.1.16", false, null),

    /** A local variable without {@code final}, read in an anonymous class: effectively final. */
    EFFECTIVELY_FINAL("#24", "MODERN", null, true, "3.1.17", false, null),

    /** {@code final class Box {}} in a method: a local class with a modifier. */
    LOCAL_CLASS_MODIFIER("#60", "MODERN", null, true, "3.1.17", false, null),

    /**
     * {@code Boolean z = v; boolean x = z || true;} for a {@code BOOLEAN}
     * operand: javac throws a {@code NullPointerException} if the operand is
     * {@code NULL}, Janino gives {@code true}.
     */
    NULL_BOOLEAN_OR("S-01", "PROBE", DataType.BOOLEAN, true, "0", true, "3.2.0"),

    /**
     * {@code f(v)} with {@code f(Object)} and {@code f(int...)} for an
     * {@code INT} operand: javac invokes {@code f(Object)}, Janino
     * {@code f(int...)}; the result is {@code "Object"} or {@code "int..."}.
     */
    VARARGS_BEFORE_BOXING("S-02", "PROBE", DataType.INT, true, "0", true, "3.2.0"),

    /**
     * {@code Object x = (v & 1) == 0 ? b : 5;} with {@code Byte b = v} for a
     * {@code BYTE} operand: the class of {@code x} is {@code Byte} with javac
     * and {@code Integer} with Janino; the result is its name, a colon and
     * the value.
     */
    CONDITIONAL_BOXED_TYPE("S-04", "PROBE", DataType.BYTE, true, "0", true, "3.2.0"),

    /**
     * {@code ("a" + (true ? "b" : s)) == "ab"} for a {@code STRING} operand
     * {@code s}: {@code false} with javac, {@code true} with Janino, which
     * folds the concatenation into an interned constant.
     */
    CONSTANT_CONDITIONAL("S-12", "PROBE", DataType.STRING, true, "0", true, "3.2.0"),

    /**
     * {@code f(new int[] {v})} with {@code f(Object)} and {@code f(int...)}
     * for an {@code INT} operand: javac invokes {@code f(int...)}, Janino
     * {@code f(Object)}; the result is {@code "int..."} or {@code "Object"}.
     */
    VARARGS_ARRAY("S-13", "PROBE", DataType.INT, true, "0", true, "3.2.0"),

    /**
     * {@code byte b = 1 << 2;} plus an {@code INT} operand: javac folds the
     * shift into a constant, Janino rejects the assignment because it does
     * not; the result is {@code v + 4}.
     */
    CONSTANT_SHIFT("D-01", "PROBE", DataType.INT, true, null, false, null),

    /**
     * {@code f(v, new int[] {1, 2})} with {@code f(Integer i, Object... a)}
     * for an {@code INT} operand: javac passes the array as the only element
     * of {@code a}; Janino rejects the invocation before 3.1.20, and passes
     * the array like javac since. The result is {@code i + a.length}, i.e.
     * {@code v + 1}.
     */
    VARARGS_ARRAY_ELEMENT("D-08", "PROBE", DataType.INT, true, "3.1.20", false, null);

    /** The language profiles that consist of features, in the order of the reports. */
    public static final List<String> PROFILES = List.of("LENIENT", "MODERN", "PROBE");

    private final String id;
    private final String profile;
    private final DataType operandType;
    private final boolean javacAccepts;
    private final String janinoSince;
    private final boolean janinoDiffers;
    private final String compliantSince;

    /**
     * @param id the ID in the register of deviations, or the issue that added
     *        the construct
     * @param profile the language profile, e.g. {@code LENIENT}
     * @param operandType the type of the operand, or {@code null} for any
     * @param javacAccepts whether javac compiles the construct
     * @param janinoSince the first version of Janino that accepts it,
     *        {@code "0"} for every version, {@code null} for none
     * @param janinoDiffers whether a Janino that accepts it computes the
     *        result of {@link Semantics#JANINO} (the compatibility mode of
     *        janino-lts keeps the behavior of 3.1.12); otherwise that of javac
     * @param compliantSince the first version of janino-lts whose compliance
     *        mode treats the construct like javac, as the register of
     *        deviations states it; {@code null} while that is open, and the
     *        compliance mode behaves like the compatibility mode
     */
    LanguageFeature(String id, String profile, DataType operandType, boolean javacAccepts, String janinoSince,
            boolean janinoDiffers, String compliantSince) {
        this.id = id;
        this.profile = profile;
        this.operandType = operandType;
        this.javacAccepts = javacAccepts;
        this.janinoSince = janinoSince;
        this.janinoDiffers = janinoDiffers;
        this.compliantSince = compliantSince;
    }

    /** How a compiler treats a construct. */
    public enum Behavior {
        /** Compiles it, and the result is that of {@link Semantics#JAVAC}. */
        JAVAC,
        /** Compiles it, and the result is that of {@link Semantics#JANINO}. */
        JANINO,
        /** Rejects it with a compile error. */
        REJECT
    }

    /** Which compiler's result the interpreter computes for a feature. */
    public enum Semantics {
        /** As javac compiles the construct: the reference. */
        JAVAC,
        /**
         * As Janino 3.1.12 compiles it, and the compatibility mode of
         * janino-lts; the compliance mode too, until it corrects the feature.
         */
        JANINO
    }

    /** Returns the ID in the register of deviations or the issue, e.g. {@code S-01} or {@code #21}. */
    public String id() {
        return id;
    }

    /** Returns the language profile, e.g. {@code PROBE}. */
    public String profile() {
        return profile;
    }

    /** Returns the type of the operand, or {@code null} if any type is allowed. */
    public DataType operandType() {
        return operandType;
    }

    /**
     * Returns the first version of Janino that accepts the construct:
     * {@code "0"} for every version, {@code null} for none.
     */
    public String janinoSince() {
        return janinoSince;
    }

    /** Returns the type of the result for an operand of the given type. */
    public DataType resultType(DataType operand) {
        return switch (this) {
            case NULL_BOOLEAN_OR, CONSTANT_CONDITIONAL -> DataType.BOOLEAN;
            case VARARGS_BEFORE_BOXING, CONDITIONAL_BOXED_TYPE, VARARGS_ARRAY -> DataType.STRING;
            case CONSTANT_SHIFT, VARARGS_ARRAY_ELEMENT -> DataType.INT;
            default -> operand;
        };
    }

    /**
     * Tells whether the result is {@code NULL} for a {@code NULL} operand;
     * otherwise it is never {@code NULL}.
     */
    public boolean propagatesNull() {
        return this != NULL_BOOLEAN_OR && this != CONSTANT_CONDITIONAL;
    }

    /** Returns how javac treats the construct. */
    public Behavior javacBehavior() {
        return javacAccepts ? Behavior.JAVAC : Behavior.REJECT;
    }

    /**
     * Returns how a version of Janino treats the construct in its default
     * mode.
     *
     * @param version the version, e.g. {@code 3.1.12} or {@code 3.2.0-SNAPSHOT}
     */
    public Behavior janinoBehavior(String version) {
        if (janinoSince == null || compareVersions(version, janinoSince) < 0) {
            return Behavior.REJECT;
        }
        return janinoDiffers ? Behavior.JANINO : Behavior.JAVAC;
    }

    /**
     * Returns the first version of janino-lts whose compliance mode treats
     * the construct like javac, or {@code null} if that is open.
     */
    public String compliantSince() {
        return compliantSince;
    }

    /**
     * Returns how a version of janino-lts treats the construct in its
     * compliance mode: like javac since {@link #compliantSince()}, otherwise
     * like in its default mode.
     *
     * @param version the version, e.g. {@code 3.2.0-SNAPSHOT}
     */
    public Behavior complianceBehavior(String version) {
        if (compliantSince != null && compareVersions(version, compliantSince) >= 0) {
            return javacBehavior();
        }
        return janinoBehavior(version);
    }

    /** Returns the features of a profile, in declaration order. */
    public static List<LanguageFeature> ofProfile(String profile) {
        List<LanguageFeature> result = new ArrayList<>();
        for (LanguageFeature f : values()) {
            if (f.profile.equals(profile)) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Computes the value of the feature.
     *
     * @param operand the value of the operand, {@code null} for {@code NULL}
     * @throws NullPointerException for {@link #NULL_BOOLEAN_OR} with javac and
     *         a {@code NULL} operand, as the generated code does
     */
    public Object evaluate(Object operand, Semantics semantics) {
        boolean javac = semantics == Semantics.JAVAC;
        if (operand == null && propagatesNull()) {
            return null;
        }
        return switch (this) {
            case NULL_BOOLEAN_OR -> {
                if (operand == null && javac) {
                    throw new NullPointerException("Unboxing of a null Boolean");
                }
                yield Boolean.TRUE;
            }
            case VARARGS_BEFORE_BOXING -> javac ? "Object" : "int...";
            case CONDITIONAL_BOXED_TYPE -> {
                byte b = (Byte) operand;
                int value = (b & 1) == 0 ? b : 5;
                yield (javac ? "java.lang.Byte:" : "java.lang.Integer:") + value;
            }
            case CONSTANT_CONDITIONAL -> !javac;
            case VARARGS_ARRAY -> javac ? "int..." : "Object";
            case CONSTANT_SHIFT -> (Integer) operand + 4;
            case VARARGS_ARRAY_ELEMENT -> (Integer) operand + 1;
            default -> operand;
        };
    }

    /**
     * Compares two versions numerically, part by part; a qualifier such as
     * {@code -SNAPSHOT} is ignored, because a snapshot already contains the
     * changes of its version.
     */
    public static int compareVersions(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int[] numbers(String version) {
        Objects.requireNonNull(version, "version");
        int dash = version.indexOf('-');
        String[] parts = (dash < 0 ? version : version.substring(0, dash)).split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a version: " + version, e);
            }
        }
        return result;
    }
}
