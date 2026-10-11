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

package io.github.janinolts.qsim.bench;

import java.util.Map;

/**
 * Defines classes from class files in memory. A class loader other than the
 * boot loader has its classes verified by the JVM when it links them.
 */
final class ClassFileLoader extends ClassLoader {

    private final Map<String, byte[]> classFiles;

    /**
     * Creates the loader.
     *
     * @param classFiles the class files, by binary class name
     */
    ClassFileLoader(ClassLoader parent, Map<String, byte[]> classFiles) {
        super(parent);
        this.classFiles = classFiles;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = classFiles.get(name);
        if (b == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, b, 0, b.length);
    }

    /**
     * Loads, links (which verifies) and initializes every class of the class
     * files in a new loader.
     *
     * @return the classes, in the order of the class files
     */
    static Class<?>[] loadAll(ClassLoader parent, Map<String, byte[]> classFiles) {
        ClassFileLoader loader = new ClassFileLoader(parent, classFiles);
        Class<?>[] classes = new Class<?>[classFiles.size()];
        int i = 0;
        for (String name : classFiles.keySet()) {
            try {
                classes[i++] = Class.forName(name, true, loader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return classes;
    }
}
