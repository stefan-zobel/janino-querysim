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

import io.github.janinolts.qsim.harness.ClassFileComparison;
import io.github.janinolts.qsim.harness.Difference;
import io.github.janinolts.qsim.harness.RandomUnit;
import io.github.janinolts.qsim.harness.UnitKey;
import io.github.janinolts.qsim.harness.UnitOutcome;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The files through which a batch of a campaign, run in a child JVM, hands
 * its outcomes to the command {@code fuzz}: in the directory of the batch,
 * {@value #UNITS} with the raw data of the report, {@value #SUMMARY} with one
 * line per unit (the random unit, its key, feature tags, class file level and
 * differences), {@value #INCONSISTENCIES} with the inconsistencies of the
 * metamorphic variants, and {@value #DONE} when the batch is complete.
 */
final class CampaignFiles {

    static final String UNITS = "units.jsonl";
    static final String SUMMARY = "summary.jsonl";
    static final String INCONSISTENCIES = "inconsistencies.txt";
    static final String DONE = "done";
    static final String LOG = "log.txt";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    /**
     * The summary of a unit.
     *
     * @param unit the random unit
     * @param outcome the outcome, without the runs of the backends
     */
    record Summary(RandomUnit unit, UnitOutcome outcome) {
    }

    private CampaignFiles() {
    }

    /** Returns the line of the summary of a unit. */
    static String summaryLine(RandomUnit unit, UnitOutcome o) {
        JsonObject line = new JsonObject();
        line.addProperty("unit", unit.toString());
        line.addProperty("key", o.key().toString());
        JsonArray tags = new JsonArray();
        o.featureTags().forEach(tags::add);
        line.add("tags", tags);
        line.addProperty("level", o.classFiles() == null ? null : o.classFiles().level().name());
        line.add("differences", differences(o.differences()));
        line.add("expected", differences(o.expected()));
        return GSON.toJson(line);
    }

    private static JsonArray differences(List<Difference> differences) {
        JsonArray array = new JsonArray();
        for (Difference d : differences) {
            JsonObject o = new JsonObject();
            o.addProperty("pair", d.pair().label());
            o.addProperty("kind", d.kind().name());
            o.addProperty("assessment", d.assessment().name());
            o.addProperty("detail", d.detail());
            array.add(o);
        }
        return array;
    }

    /** Parses a line of the summary. */
    static Summary parseSummary(String text) {
        JsonObject line = GSON.fromJson(text, JsonObject.class);
        UnitKey key = UnitKey.parse(line.get("key").getAsString());
        List<String> tags = new ArrayList<>();
        line.getAsJsonArray("tags").forEach(t -> tags.add(t.getAsString()));
        JsonElement level = line.get("level");
        ClassFileComparison classFiles = level.isJsonNull() ? null
                : new ClassFileComparison(ClassFileComparison.Level.valueOf(level.getAsString()), List.of());
        UnitOutcome outcome = new UnitOutcome(key, tags, List.of(), classFiles,
                parseDifferences(key, line.getAsJsonArray("differences")),
                parseDifferences(key, line.getAsJsonArray("expected")));
        return new Summary(RandomUnit.parse(line.get("unit").getAsString()), outcome);
    }

    private static List<Difference> parseDifferences(UnitKey key, JsonArray array) {
        List<Difference> result = new ArrayList<>();
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            result.add(new Difference(key, Difference.Pair.ofLabel(o.get("pair").getAsString()),
                    Difference.Kind.valueOf(o.get("kind").getAsString()),
                    Difference.Assessment.valueOf(o.get("assessment").getAsString()), o.get("detail").getAsString()));
        }
        return result;
    }
}
