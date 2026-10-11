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

package io.github.janinolts.qsim.backend.javac;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * Keeps the class files that javac writes in memory; reads everything else
 * through the standard file manager.
 */
final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();

    MemoryFileManager(StandardJavaFileManager standard) {
        super(standard);
    }

    /** Returns a source file in memory for the class with the given qualified name. */
    static JavaFileObject source(String qualifiedName, String text) {
        URI uri = URI.create("string:///" + qualifiedName.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension);
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
            FileObject sibling) {
        URI uri = URI.create("memory:///" + className.replace('.', '/') + kind.extension);
        return new SimpleJavaFileObject(uri, kind) {
            @Override
            public OutputStream openOutputStream() {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                outputs.put(className, out);
                return out;
            }
        };
    }

    /** Returns the class files by binary class name, in the order in which javac wrote them. */
    Map<String, byte[]> classFiles() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        outputs.forEach((name, out) -> result.put(name, out.toByteArray()));
        return result;
    }
}
