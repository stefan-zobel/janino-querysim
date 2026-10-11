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

package io.github.janinolts.qsim.harness;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Compares the class files that two compilers generated for the same unit,
 * and reports the lowest of four levels that applies:
 * <ul>
 * <li>{@link Level#L0}: byte-identical;</li>
 * <li>{@link Level#L1}: identical except for the class file version
 * (bytes 4 to 7);</li>
 * <li>{@link Level#L2}: all method bodies equal (instructions, exception
 * tables, {@code max_stack}, {@code max_locals}); differences only in flags,
 * attributes such as {@code InnerClasses} and {@code EnclosingMethod}, or the
 * order of the constant pool;</li>
 * <li>{@link Level#L3}: at least one method body differs, or the set of
 * classes or methods.</li>
 * </ul>
 * Method bodies are compared as the text that ASM prints for them, without
 * debugging information and stack map frames, so that the order of the
 * constant pool does not matter.
 *
 * @param level the level of the unit: the highest level of its classes
 * @param details what differs, per class
 */
public record ClassFileComparison(Level level, List<String> details) {

    /** The levels, from identical to different code. */
    public enum Level { L0, L1, L2, L3 }

    public ClassFileComparison {
        Objects.requireNonNull(level, "level");
        details = List.copyOf(details);
    }

    /** Compares the class files of two compilations, by binary class name. */
    public static ClassFileComparison compare(Map<String, byte[]> a, Map<String, byte[]> b) {
        List<String> details = new ArrayList<>();
        Level level = Level.L0;
        TreeSet<String> names = new TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        for (String name : names) {
            byte[] x = a.get(name);
            byte[] y = b.get(name);
            Level classLevel;
            if (x == null || y == null) {
                classLevel = Level.L3;
                details.add(name + ": only in " + (x == null ? "B" : "A"));
            } else {
                classLevel = compareClass(name, x, y, details);
            }
            if (classLevel.compareTo(level) > 0) {
                level = classLevel;
            }
        }
        return new ClassFileComparison(level, details);
    }

    private static Level compareClass(String name, byte[] a, byte[] b, List<String> details) {
        if (Arrays.equals(a, b)) {
            return Level.L0;
        }
        if (a.length == b.length && Arrays.equals(withoutVersion(a), withoutVersion(b))) {
            details.add(name + ": class file version " + major(a) + " / " + major(b));
            return Level.L1;
        }
        ClassNode x = read(a);
        ClassNode y = read(b);
        Map<String, String> bodiesA = methodBodies(x);
        Map<String, String> bodiesB = methodBodies(y);
        if (!bodiesA.equals(bodiesB)) {
            Map<String, Integer> sizesA = codeLengths(a);
            Map<String, Integer> sizesB = codeLengths(b);
            TreeSet<String> methods = new TreeSet<>(bodiesA.keySet());
            methods.addAll(bodiesB.keySet());
            for (String m : methods) {
                if (!Objects.equals(bodiesA.get(m), bodiesB.get(m))) {
                    details.add(name + "." + m + ": code " + sizesA.getOrDefault(m, -1) + " / "
                            + sizesB.getOrDefault(m, -1) + " bytes");
                }
            }
            return Level.L3;
        }
        List<String> differences = new ArrayList<>();
        if (x.version != y.version) {
            differences.add("version " + (x.version & 0xffff) + " / " + (y.version & 0xffff));
        }
        if (x.access != y.access) {
            differences.add("access flags 0x" + Integer.toHexString(x.access) + " / 0x" + Integer.toHexString(y.access));
        }
        if (!innerClasses(x).equals(innerClasses(y))) {
            differences.add("InnerClasses " + innerClasses(x) + " / " + innerClasses(y));
        }
        if (!Objects.equals(x.outerClass, y.outerClass) || !Objects.equals(x.outerMethod, y.outerMethod)) {
            differences.add("EnclosingMethod " + x.outerClass + "." + x.outerMethod + " / " + y.outerClass + "."
                    + y.outerMethod);
        }
        if (!fields(x).equals(fields(y))) {
            differences.add("fields " + fields(x) + " / " + fields(y));
        }
        if (!methodFlags(x).equals(methodFlags(y))) {
            differences.add("method flags " + methodFlags(x) + " / " + methodFlags(y));
        }
        if (differences.isEmpty()) {
            differences.add("constant pool, debugging information or attribute order");
        }
        details.add(name + ": " + String.join("; ", differences));
        return Level.L2;
    }

    private static byte[] withoutVersion(byte[] classFile) {
        byte[] copy = classFile.clone();
        Arrays.fill(copy, 4, 8, (byte) 0);
        return copy;
    }

    private static int major(byte[] classFile) {
        return ((classFile[6] & 0xff) << 8) | (classFile[7] & 0xff);
    }

    private static ClassNode read(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    /** The text of each method body, by name and descriptor. */
    private static Map<String, String> methodBodies(ClassNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        for (MethodNode m : node.methods) {
            Textifier textifier = new Textifier();
            m.accept(new TraceMethodVisitor(textifier));
            StringWriter sw = new StringWriter();
            try (PrintWriter pw = new PrintWriter(sw)) {
                textifier.print(pw);
            }
            result.put(m.name + m.desc, sw.toString());
        }
        return result;
    }

    private static Map<String, Integer> codeLengths(byte[] classFile) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ClassFileInfo.MethodSize m : ClassFileInfo.of(classFile).methods()) {
            result.put(m.name() + m.descriptor(), m.codeLength());
        }
        return result;
    }

    private static List<String> innerClasses(ClassNode node) {
        List<String> result = new ArrayList<>();
        for (InnerClassNode ic : node.innerClasses) {
            result.add(ic.name + "/" + ic.outerName + "/" + ic.innerName + "/0x" + Integer.toHexString(ic.access));
        }
        return result;
    }

    private static List<String> fields(ClassNode node) {
        List<String> result = new ArrayList<>();
        for (FieldNode f : node.fields) {
            result.add(f.name + ":" + f.desc + "/0x" + Integer.toHexString(f.access));
        }
        return result;
    }

    private static List<String> methodFlags(ClassNode node) {
        List<String> result = new ArrayList<>();
        for (MethodNode m : node.methods) {
            result.add(m.name + m.desc + "/0x" + Integer.toHexString(m.access));
        }
        return result;
    }
}
