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

import java.util.Objects;

/**
 * How a backend compiles a unit.
 *
 * @param apiPath the API of Janino to use
 * @param targetVersion the class file target version, e.g. 8; or
 *        {@link #DEFAULT_TARGET_VERSION} for the default of the compiler
 *        (javac always compiles with release 8)
 * @param debugSource whether to write the source file name
 * @param debugLines whether to write line numbers
 * @param debugVars whether to write local variable names
 * @param compliance whether to compile in the compliance mode of janino-lts;
 *        a backend without it reports {@link Outcome#UNSUPPORTED}
 * @param parent the class loader through which the compiler resolves, and the
 *        generated classes load, the classes that the code references; it
 *        must see {@code qsim-runtime}
 */
public record CompileOptions(
        ApiPath apiPath,
        int targetVersion,
        boolean debugSource,
        boolean debugLines,
        boolean debugVars,
        boolean compliance,
        ClassLoader parent) {

    /** The value of {@link #targetVersion()} that keeps the default of the compiler. */
    public static final int DEFAULT_TARGET_VERSION = 0;

    public CompileOptions {
        Objects.requireNonNull(apiPath, "apiPath");
        Objects.requireNonNull(parent, "parent");
        if (targetVersion < 0) {
            throw new IllegalArgumentException("targetVersion: " + targetVersion);
        }
    }

    /**
     * Returns options with the default target version, no debugging
     * information and without the compliance mode.
     */
    public static CompileOptions of(ApiPath apiPath, ClassLoader parent) {
        return new CompileOptions(apiPath, DEFAULT_TARGET_VERSION, false, false, false, false, parent);
    }

    public CompileOptions withApiPath(ApiPath apiPath) {
        return new CompileOptions(apiPath, targetVersion, debugSource, debugLines, debugVars, compliance, parent);
    }

    public CompileOptions withTargetVersion(int targetVersion) {
        return new CompileOptions(apiPath, targetVersion, debugSource, debugLines, debugVars, compliance, parent);
    }

    public CompileOptions withDebugging(boolean source, boolean lines, boolean vars) {
        return new CompileOptions(apiPath, targetVersion, source, lines, vars, compliance, parent);
    }

    public CompileOptions withCompliance(boolean compliance) {
        return new CompileOptions(apiPath, targetVersion, debugSource, debugLines, debugVars, compliance, parent);
    }
}
