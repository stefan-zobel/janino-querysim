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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes text files in UTF-8 that may contain data of the tables, e.g. in
 * exception messages: a {@code SUBSTRING} can cut a character outside of the
 * Basic Multilingual Plane in half, and such a lone surrogate has no UTF-8
 * form. Unlike {@link Files#writeString}, which throws, this writes {@code ?}
 * for it.
 */
public final class TextFiles {

    private TextFiles() {
    }

    /** Writes a text in UTF-8, with {@code ?} for each lone surrogate. */
    public static void write(Path file, CharSequence text) throws IOException {
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
