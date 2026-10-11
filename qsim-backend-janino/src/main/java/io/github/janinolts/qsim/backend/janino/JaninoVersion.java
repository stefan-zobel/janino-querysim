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

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * Reads the version of Janino from its JAR file.
 */
final class JaninoVersion {

    /** The version if the class does not come from a JAR file with a version. */
    static final String UNKNOWN = "unknown";

    /** The Maven metadata of the artifact {@code janino}, whatever its group ID. */
    private static final Pattern POM_PROPERTIES = Pattern.compile("META-INF/maven/[^/]+/janino/pom\\.properties");

    private JaninoVersion() {
    }

    /**
     * Returns the version of the JAR file that contains the given class: the
     * Maven version from {@code META-INF/maven/<groupId>/janino/pom.properties},
     * e.g. {@code 3.2.0-SNAPSHOT}; else the attribute {@code Bundle-Version} of
     * the manifest, whose qualifier is in OSGi form ({@code 3.2.0.SNAPSHOT});
     * {@link #UNKNOWN} if there is neither.
     */
    static String of(Class<?> janinoClass) {
        CodeSource source = janinoClass.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return UNKNOWN;
        }
        try {
            Path path = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(path)) {
                return UNKNOWN;
            }
            try (JarFile jar = new JarFile(path.toFile())) {
                String version = mavenVersion(jar);
                if (version == null) {
                    Manifest manifest = jar.getManifest();
                    version = manifest == null ? null : manifest.getMainAttributes().getValue("Bundle-Version");
                }
                return version == null ? UNKNOWN : version;
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    private static String mavenVersion(JarFile jar) throws IOException {
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
            JarEntry entry = entries.nextElement();
            if (POM_PROPERTIES.matcher(entry.getName()).matches()) {
                Properties properties = new Properties();
                try (InputStream in = jar.getInputStream(entry)) {
                    properties.load(in);
                }
                return properties.getProperty("version");
            }
        }
        return null;
    }
}
