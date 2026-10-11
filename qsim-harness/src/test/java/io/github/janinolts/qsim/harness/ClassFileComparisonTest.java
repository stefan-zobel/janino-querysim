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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.janinolts.qsim.harness.ClassFileComparison.Level;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClassFileComparisonTest {

    /** A class {@code p.C} with a method {@code int f()} that returns the constant, with the given class flags. */
    private static byte[] classFile(int version, int access, int constant, boolean innerClass) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(version, access, "p/C", null, "java/lang/Object", null);
        if (innerClass) {
            cw.visitInnerClass("p/C$1", null, null, 0);
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()I", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.BIPUSH, constant);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final byte[] BASE = classFile(Opcodes.V1_8, Opcodes.ACC_PUBLIC, 7, false);

    @Test
    void identical() {
        assertEquals(Level.L0, ClassFileComparison.compare(Map.of("p.C", BASE), Map.of("p.C", BASE.clone())).level());
    }

    @Test
    void onlyTheVersion() {
        ClassFileComparison c = ClassFileComparison.compare(Map.of("p.C", classFile(Opcodes.V1_6, Opcodes.ACC_PUBLIC,
                7, false)), Map.of("p.C", BASE));
        assertEquals(Level.L1, c.level());
        assertEquals("p.C: class file version 50 / 52", c.details().get(0));
    }

    @Test
    void flagsAndAttributes() {
        ClassFileComparison c = ClassFileComparison.compare(Map.of("p.C", BASE),
                Map.of("p.C", classFile(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, 7, true)));
        assertEquals(Level.L2, c.level());
        assertTrue(c.details().get(0).contains("access flags 0x1 / 0x21"), c.details()::toString);
        assertTrue(c.details().get(0).contains("InnerClasses"), c.details()::toString);
    }

    @Test
    void code() {
        ClassFileComparison c = ClassFileComparison.compare(Map.of("p.C", BASE),
                Map.of("p.C", classFile(Opcodes.V1_8, Opcodes.ACC_PUBLIC, 8, false)));
        assertEquals(Level.L3, c.level());
        assertEquals("p.C.f()I: code 3 / 3 bytes", c.details().get(0));
    }

    @Test
    void missingClass() {
        ClassFileComparison c = ClassFileComparison.compare(Map.of("p.C", BASE), Map.of());
        assertEquals(Level.L3, c.level());
    }

    @Test
    void sizes() {
        ClassFileInfo info = ClassFileInfo.of(BASE);
        assertEquals(52, info.majorVersion());
        assertEquals(3, info.largestMethod());
        assertEquals(BASE.length, info.size());
        assertEquals("f", info.methods().get(0).name());
    }
}
