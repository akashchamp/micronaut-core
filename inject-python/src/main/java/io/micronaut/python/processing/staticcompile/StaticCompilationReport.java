/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.python.processing.staticcompile;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.python.processing.staticcompile.StaticCompilationDecision.Outcome;
import io.micronaut.python.processing.staticcompile.StaticCompilationDecision.Reason;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes the decisions of a compilation as {@code decisions.jsonl} (one JSON object per line, the
 * first one describing the plan) and {@code summary.txt} (the decisions grouped by outcome with
 * totals per outcome and per reason).
 *
 * @author Graeme Rocher
 * @since 5.3.0
 */
@Experimental
public final class StaticCompilationReport {

    /**
     * The name of the machine-readable file.
     */
    public static final String DECISIONS_FILE = "decisions.jsonl";

    /**
     * The name of the human-readable file.
     */
    public static final String SUMMARY_FILE = "summary.txt";

    private StaticCompilationReport() {
    }

    /**
     * Writes the report. An incremental build plans the affected sources only: the decisions of the
     * previous report for every other source are kept, so both files stay complete, and the plan
     * record says the build was incremental. A planned source without decisions (its functions
     * were removed) loses its previous ones.
     *
     * @param directory      The directory, created when missing
     * @param mode           The mode of the compilation
     * @param decisions      The decisions of this build
     * @param plannedSources The paths of the sources this build planned, or {@code null} when it planned all of them
     * @return Every decision the report now holds: the retained ones, then this build's
     */
    public static List<StaticCompilationDecision> write(Path directory, StaticCompilationMode mode, List<StaticCompilationDecision> decisions, @Nullable Set<String> plannedSources) {
        try {
            Files.createDirectories(directory);
            Path decisionsFile = directory.resolve(DECISIONS_FILE);
            List<StaticCompilationDecision> all = new ArrayList<>();
            if (plannedSources != null && Files.exists(decisionsFile)) {
                for (String line : Files.readAllLines(decisionsFile, StandardCharsets.UTF_8)) {
                    StaticCompilationDecision retained = retained(line);
                    if (retained != null && (retained.sourcePath() == null || !plannedSources.contains(retained.sourcePath()))) {
                        all.add(retained);
                    }
                }
            }
            all.addAll(decisions);
            List<String> lines = new ArrayList<>(all.size() + 1);
            lines.add("{\"record\":\"plan\",\"mode\":" + Json.string(mode.optionValue()) + ",\"coverage\":" + Json.string(plannedSources == null ? "full" : "incremental") + "}");
            for (StaticCompilationDecision decision : all) {
                lines.add(decision.toJson());
            }
            Files.write(decisionsFile, lines, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(SUMMARY_FILE), summary(all), StandardCharsets.UTF_8);
            return List.copyOf(all);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the static compilation report to " + directory, e);
        }
    }

    /**
     * The decision a line of a previous report holds, or {@code null} for the plan record or a line
     * a previous version wrote differently.
     */
    private static @Nullable StaticCompilationDecision retained(String line) {
        try {
            return StaticCompilationDecision.fromJson(line);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The human-readable summary: the decisions grouped by outcome, each with its reasons, then the
     * totals per outcome and per reason rule.
     *
     * @param decisions The decisions
     * @return The summary
     */
    public static String summary(List<StaticCompilationDecision> decisions) {
        StringBuilder out = new StringBuilder();
        Map<Outcome, List<StaticCompilationDecision>> byOutcome = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            byOutcome.put(outcome, new ArrayList<>());
        }
        for (StaticCompilationDecision decision : decisions) {
            byOutcome.computeIfAbsent(decision.outcome(), outcome -> new ArrayList<>()).add(decision);
        }
        for (Map.Entry<Outcome, List<StaticCompilationDecision>> entry : byOutcome.entrySet()) {
            for (StaticCompilationDecision decision : entry.getValue()) {
                out.append(String.format("%-13s %s", entry.getKey().name(), decision.qualifiedName()));
                if (decision.span() != null) {
                    out.append("  ").append(decision.span().location());
                }
                out.append('\n');
                if (decision.outcome() == Outcome.COMPILED || decision.outcome() == Outcome.CANDIDATE) {
                    out.append(String.format("              statements %d · java calls %d · bridge calls %d · helpers %d%n",
                        decision.stats().statements(), decision.stats().javaCalls(), decision.stats().bridgeCalls(), decision.stats().helperCalls()));
                }
                for (Reason reason : decision.reasons()) {
                    out.append("              [").append(reason.rule()).append("] ");
                    if (reason.span() != null) {
                        out.append(reason.span().location()).append("  ");
                    }
                    out.append(reason.message()).append('\n');
                }
            }
        }
        out.append('\n');
        for (Map.Entry<Outcome, List<StaticCompilationDecision>> entry : byOutcome.entrySet()) {
            out.append(String.format("%-13s %d%n", entry.getKey().name(), entry.getValue().size()));
        }
        Map<String, Integer> byRule = new TreeMap<>();
        for (StaticCompilationDecision decision : decisions) {
            for (Reason reason : decision.reasons()) {
                byRule.merge(reason.rule(), 1, Integer::sum);
            }
        }
        if (!byRule.isEmpty()) {
            out.append('\n');
            byRule.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> out.append(String.format("%-32s %d%n", e.getKey(), e.getValue())));
        }
        return out.toString();
    }
}
