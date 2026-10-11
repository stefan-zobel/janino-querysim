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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The sizes in a class file that ASM does not report: the length of the
 * bytecode of each method and the number of constant pool entries. Read
 * directly from the class file format (JVMS 4).
 *
 * @param majorVersion the class file version, e.g. 52 for Java 8
 * @param constantPoolCount the {@code constant_pool_count}
 * @param size the size of the class file in bytes
 * @param methods the methods with their code lengths, in the order of the
 *        class file
 */
public record ClassFileInfo(int majorVersion, int constantPoolCount, int size, List<MethodSize> methods) {

    /**
     * A method and the length of its bytecode.
     *
     * @param name the name
     * @param descriptor the descriptor
     * @param codeLength the {@code code_length} of its {@code Code}
     *        attribute, 0 for an abstract or native method
     */
    public record MethodSize(String name, String descriptor, int codeLength) {
    }

    public ClassFileInfo {
        methods = List.copyOf(methods);
    }

    /** Returns the length of the largest method, or 0. */
    public int largestMethod() {
        return methods.stream().mapToInt(MethodSize::codeLength).max().orElse(0);
    }

    /** Parses a class file. */
    public static ClassFileInfo of(byte[] classFile) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IllegalArgumentException("Not a class file");
            }
            in.readUnsignedShort();
            int major = in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IllegalArgumentException("Constant pool tag " + tag + " at " + i);
                }
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int interfaces = in.readUnsignedShort();
            for (int i = 0; i < interfaces; i++) {
                in.readUnsignedShort();
            }
            int fields = in.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                in.readUnsignedShort();
                in.readUnsignedShort();
                in.readUnsignedShort();
                skipAttributes(in);
            }
            int methodCount = in.readUnsignedShort();
            List<MethodSize> methods = new ArrayList<>();
            for (int i = 0; i < methodCount; i++) {
                in.readUnsignedShort();
                String name = utf8[in.readUnsignedShort()];
                String descriptor = utf8[in.readUnsignedShort()];
                int codeLength = 0;
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    String attribute = utf8[in.readUnsignedShort()];
                    int length = in.readInt();
                    if ("Code".equals(attribute)) {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        codeLength = in.readInt();
                        in.skipNBytes(length - 8);
                    } else {
                        in.skipNBytes(length);
                    }
                }
                methods.add(new MethodSize(name, descriptor, codeLength));
            }
            return new ClassFileInfo(major, count, classFile.length, methods);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            in.readUnsignedShort();
            in.skipNBytes(in.readInt());
        }
    }
}
