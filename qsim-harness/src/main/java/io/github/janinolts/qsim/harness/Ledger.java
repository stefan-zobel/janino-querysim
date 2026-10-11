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

import io.github.janinolts.qsim.plan.LanguageFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The known differences of one development line of the candidate, from a file
 * {@code known-differences.tsv}: one entry per line, tab-separated, with the
 * columns unit key, pair, kind, ID and note, optionally feature tags and
 * optionally the version that fixed the difference; empty lines and lines
 * starting with {@code #} are ignored. The unit key may contain {@code *},
 * which matches any text, so that one entry can cover the variants of a unit.
 * An entry with feature tags applies only to units that have all of them: one
 * entry covers the random units of a code pattern, and a run without such a
 * unit does not orphan it. An entry with a fixed version applies only to
 * candidates before that version, see {@link #applicableTo(String)}.
 * <p>
 * A check fails in both directions: on a difference that no entry explains,
 * and on an entry that explains no difference although its unit ran. The
 * pair {@code INTERP:JAVAC} cannot be entered: such a difference is a defect
 * of qsim.
 *
 * @param line the development line, e.g. {@code 3.1.x}
 * @param entries the entries
 */
public record Ledger(String line, List<Ledger.Entry> entries) {

    /** The development line of the maintenance releases. */
    public static final String LINE_3_1_X = "3.1.x";

    /** The development line on {@code master}. */
    public static final String LINE_MASTER = "master";

    /** The repository whose issues the IDs {@code #N} of the ledger refer to. */
    public static final String ISSUE_REPOSITORY = "janino-lts/janino";

    private static final Pattern ISSUE_ID = Pattern.compile("#\\d+");

    /**
     * An entry.
     *
     * @param keyPattern the unit key, possibly with {@code *}
     * @param pair the pair
     * @param kind the kind
     * @param id an issue number or an ID of the register of deviations, e.g.
     *        {@code #2} or {@code S-04}
     * @param note a short explanation
     * @param tags the feature tags that a unit must have, or none
     * @param fixed the version of the candidate that fixed the difference, or
     *        {@code null}; a snapshot of that version counts as fixed
     * @param lineNumber the line in the file, for messages
     */
    public record Entry(String keyPattern, Difference.Pair pair, Difference.Kind kind, String id, String note,
            List<String> tags, String fixed, int lineNumber) {

        public Entry {
            Objects.requireNonNull(keyPattern, "keyPattern");
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(note, "note");
            tags = List.copyOf(tags);
            if (pair == Difference.Pair.INTERP_JAVAC) {
                throw new IllegalArgumentException("Line " + lineNumber
                        + ": INTERP:JAVAC cannot be a known difference; it is a defect of qsim");
            }
            if (id.isBlank()) {
                throw new IllegalArgumentException("Line " + lineNumber + ": an entry needs an ID");
            }
            if (fixed != null) {
                try {
                    LanguageFeature.compareVersions(fixed, fixed);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Line " + lineNumber + ": " + e.getMessage(), e);
                }
            }
        }

        /** Tells whether this entry applies to a candidate version: always, or if the version is before the fix. */
        public boolean appliesTo(String candidateVersion) {
            return fixed == null || LanguageFeature.compareVersions(candidateVersion, fixed) < 0;
        }

        /** Tells whether a unit key matches the pattern of this entry; the tags are not checked. */
        public boolean matches(UnitKey key) {
            return PATTERNS.computeIfAbsent(keyPattern, Entry::toRegex).matcher(key.toString()).matches();
        }

        /** Tells whether a unit with the given feature tags matches the key pattern and the tags of this entry. */
        public boolean matches(UnitKey key, Collection<String> unitTags) {
            return unitTags.containsAll(tags) && matches(key);
        }

        /** Tells whether this entry explains a difference of a unit with the given feature tags. */
        public boolean explains(Difference d, Collection<String> unitTags) {
            return pair == d.pair() && kind == d.kind() && matches(d.key(), unitTags);
        }

        /**
         * Returns the ID as the reports print it: an issue number qualified
         * with its repository, e.g. {@code janino-lts/janino#54}, because a
         * bare {@code #54} would refer to the issues of the repository that
         * runs qsim; any other ID unchanged.
         */
        public String reference() {
            return ISSUE_ID.matcher(id).matches() ? ISSUE_REPOSITORY + id : id;
        }

        /** Returns the {@linkplain #reference() reference} in Markdown, an issue as a link to it. */
        public String markdownReference() {
            if (!ISSUE_ID.matcher(id).matches()) {
                return "`" + id + "`";
            }
            return "[" + reference() + "](https://github.com/" + ISSUE_REPOSITORY + "/issues/" + id.substring(1) + ")";
        }

        /** Describes the entry in Markdown, for the reports. */
        public String markdown() {
            return "`" + keyPattern + "` " + pair.label() + " " + kind + " " + markdownReference() + ": " + note
                    + (tags.isEmpty() ? "" : " (tags " + String.join(", ", tags) + ")")
                    + (fixed == null ? "" : " (fixed in " + fixed + ")") + "; line " + lineNumber;
        }

        @Override
        public String toString() {
            return "line " + lineNumber + ": " + keyPattern + " " + pair.label() + " " + kind + " " + reference() + " "
                    + note + (tags.isEmpty() ? "" : " " + tags) + (fixed == null ? "" : " fixed in " + fixed);
        }

        private static Pattern toRegex(String glob) {
            StringBuilder regex = new StringBuilder();
            for (String part : glob.split("\\*", -1)) {
                if (!regex.isEmpty()) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            return Pattern.compile(regex.toString());
        }
    }

    /** The compiled key patterns, shared by all entries. */
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    public Ledger {
        Objects.requireNonNull(line, "line");
        entries = List.copyOf(entries);
    }

    /** Returns the development line of a candidate version: {@code 3.1.x} for 3.1 versions, else {@code master}. */
    public static String lineOf(String candidateVersion) {
        return candidateVersion.startsWith("3.1.") ? LINE_3_1_X : LINE_MASTER;
    }

    /**
     * Loads the ledger of a candidate version from the resources of qsim: the
     * ledger of its development line, with the entries that apply to it.
     */
    public static Ledger forVersion(String candidateVersion) {
        return forLine(lineOf(candidateVersion)).applicableTo(candidateVersion);
    }

    /**
     * Returns the ledger without the entries whose difference the candidate
     * version has fixed: such an entry would be orphaned by every run of a
     * later candidate, while an earlier candidate still needs it.
     */
    public Ledger applicableTo(String candidateVersion) {
        return new Ledger(line, entries.stream().filter(e -> e.appliesTo(candidateVersion)).toList());
    }

    /** Loads the ledger of a development line from the resources of qsim. */
    public static Ledger forLine(String line) {
        String resource = "/ledger/" + line + "/known-differences.tsv";
        try (InputStream in = Ledger.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No ledger " + resource);
            }
            return parse(line, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads a ledger from a file. */
    public static Ledger load(String line, Path file) {
        try {
            return parse(line, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses the text of a ledger file. */
    public static Ledger parse(String line, String text) {
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String s;
            int number = 0;
            while ((s = reader.readLine()) != null) {
                number++;
                if (s.isBlank() || s.startsWith("#")) {
                    continue;
                }
                String[] columns = s.split("\t", -1);
                if (columns.length < 5 || columns.length > 7) {
                    throw new IllegalArgumentException("Line " + number + ": expected 5 to 7 tab-separated columns, "
                            + "found " + columns.length);
                }
                List<String> tags = new ArrayList<>();
                if (columns.length >= 6) {
                    for (String tag : columns[5].split(",")) {
                        if (!tag.isBlank()) {
                            tags.add(tag.strip());
                        }
                    }
                }
                String fixed = columns.length == 7 && !columns[6].isBlank() ? columns[6].strip() : null;
                entries.add(new Entry(columns[0].strip(), Difference.Pair.ofLabel(columns[1].strip()),
                        Difference.Kind.valueOf(columns[2].strip()), columns[3].strip(), columns[4].strip(), tags,
                        fixed, number));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Ledger(line, entries);
    }

    /**
     * Reconciles the differences of a run with the ledger, for units without
     * feature tags.
     *
     * @param executed the keys of the units that ran
     * @param differences the differences found
     */
    public Reconciliation reconcile(Collection<UnitKey> executed, List<Difference> differences) {
        Map<UnitKey, List<String>> units = new LinkedHashMap<>();
        for (UnitKey key : executed) {
            units.put(key, List.of());
        }
        return reconcile(units, differences);
    }

    /**
     * Reconciles the differences of a run with the ledger.
     *
     * @param executed the units that ran, with their feature tags
     * @param differences the differences found
     */
    public Reconciliation reconcile(Map<UnitKey, ? extends Collection<String>> executed,
            List<Difference> differences) {
        List<Difference> explained = new ArrayList<>();
        List<Difference> unexplained = new ArrayList<>();
        Map<Difference, Entry> explanations = new LinkedHashMap<>();
        List<Entry> used = new ArrayList<>();
        for (Difference d : differences) {
            Collection<String> tags = executed.containsKey(d.key()) ? executed.get(d.key()) : List.of();
            Entry match = entries.stream().filter(e -> e.explains(d, tags)).findFirst().orElse(null);
            if (match == null) {
                unexplained.add(d);
            } else {
                explained.add(d);
                explanations.put(d, match);
                if (!used.contains(match)) {
                    used.add(match);
                }
            }
        }
        List<Entry> orphans = new ArrayList<>();
        for (Entry e : entries) {
            if (e.kind() == Difference.Kind.ALLOC || used.contains(e)) {
                continue;
            }
            if (executed.entrySet().stream().anyMatch(u -> e.matches(u.getKey(), u.getValue()))) {
                orphans.add(e);
            }
        }
        return new Reconciliation(explained, unexplained, orphans, explanations);
    }

    /** Tells whether an entry explains a difference of a unit without feature tags. */
    public boolean explains(Difference d) {
        return explains(d, List.of());
    }

    /** Tells whether an entry explains a difference of a unit with the given feature tags. */
    public boolean explains(Difference d, Collection<String> unitTags) {
        return entries.stream().anyMatch(e -> e.explains(d, unitTags));
    }

    /**
     * Reconciles the allocation differences of a measurement run with the
     * entries of the kind {@code ALLOC}: every violation must be explained, and
     * an entry whose measurement point ran in {@code xint} without a violation
     * is orphaned.
     *
     * @param measured the keys of the units measured in {@code xint}
     * @param violations the allocation differences, of the kind {@code ALLOC}
     */
    public Reconciliation reconcileAllocation(Collection<UnitKey> measured, List<Difference> violations) {
        List<Difference> explained = new ArrayList<>();
        List<Difference> unexplained = new ArrayList<>();
        Map<Difference, Entry> explanations = new LinkedHashMap<>();
        for (Difference d : violations) {
            Entry match = entries.stream().filter(e -> e.explains(d, List.of())).findFirst().orElse(null);
            if (match == null) {
                unexplained.add(d);
            } else {
                explained.add(d);
                explanations.put(d, match);
            }
        }
        List<Entry> orphans = new ArrayList<>();
        for (Entry e : entries) {
            if (e.kind() == Difference.Kind.ALLOC && measured.stream().anyMatch(e::matches)
                    && violations.stream().noneMatch(d -> e.explains(d, List.of()))) {
                orphans.add(e);
            }
        }
        return new Reconciliation(explained, unexplained, orphans, explanations);
    }

    /**
     * The result of {@link #reconcile}.
     *
     * @param explained the differences that an entry explains
     * @param unexplained the differences that no entry explains: findings
     * @param orphans the entries whose unit ran without the difference
     * @param explanations the entry that explains each explained difference
     */
    public record Reconciliation(List<Difference> explained, List<Difference> unexplained, List<Entry> orphans,
            Map<Difference, Entry> explanations) {

        public Reconciliation {
            explained = List.copyOf(explained);
            unexplained = List.copyOf(unexplained);
            orphans = List.copyOf(orphans);
            explanations = Collections.unmodifiableMap(new LinkedHashMap<>(explanations));
        }

        /** Returns the entries that explain differences, in the order of the ledger, with their counts. */
        public Map<Entry, Integer> usedEntries() {
            Map<Entry, Integer> counts = new TreeMap<>(Comparator.comparingInt(Entry::lineNumber));
            explanations.values().forEach(e -> counts.merge(e, 1, Integer::sum));
            return counts;
        }

        /** Tells whether every difference is explained and no entry is orphaned. */
        public boolean passed() {
            return unexplained.isEmpty() && orphans.isEmpty();
        }
    }
}
