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
import io.micronaut.python.processing.model.SourceSpan;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The decision taken for one candidate function: whether its body is compiled to Java, how the
 * decision to attempt it was reached, and every reason it was not compiled.
 *
 * @param qualifiedName The function, as {@code Class.method} or the module-level function's name
 * @param span          Where the function is declared, when known
 * @param outcome       The outcome
 * @param scope         The declaration that decided whether compilation was attempted
 * @param reasons       Why the body is not compiled; empty when it is
 * @param stats         What the body contains
 * @author Graeme Rocher
 * @since 5.3.0
 */
@Experimental
public record StaticCompilationDecision(String qualifiedName,
                                        @Nullable SourceSpan span,
                                        Outcome outcome,
                                        Scope scope,
                                        List<Reason> reasons,
                                        Stats stats) {

    public StaticCompilationDecision {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(scope, "scope");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        stats = stats == null ? Stats.NONE : stats;
    }

    /**
     * @return The path of the source the function is declared in, or {@code null} when unknown
     */
    public @Nullable String sourcePath() {
        return span == null ? null : span.path();
    }

    /**
     * @return The decision as one line of JSON, with stable field names
     */
    public String toJson() {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"record\":\"decision\",\"name\":").append(Json.string(qualifiedName));
        json.append(",\"source\":").append(Json.string(sourcePath()));
        json.append(",\"line\":").append(span == null ? "null" : String.valueOf(span.line()));
        json.append(",\"column\":").append(span == null ? "null" : String.valueOf(span.column()));
        json.append(",\"outcome\":").append(Json.string(outcome.name()));
        json.append(",\"scope\":").append(Json.string(scope.name()));
        json.append(",\"reasons\":[");
        for (int i = 0; i < reasons.size(); i++) {
            Reason reason = reasons.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"rule\":").append(Json.string(reason.rule()));
            json.append(",\"message\":").append(Json.string(reason.message()));
            json.append(",\"location\":").append(Json.string(reason.span() == null ? null : reason.span().location()));
            json.append('}');
        }
        json.append("],\"stats\":{\"statements\":").append(stats.statements());
        json.append(",\"javaCalls\":").append(stats.javaCalls());
        json.append(",\"bridgeCalls\":").append(stats.bridgeCalls());
        json.append(",\"helperCalls\":").append(stats.helperCalls());
        json.append("}}");
        return json.toString();
    }

    /**
     * Reads a decision back from the line {@link #toJson()} wrote.
     *
     * @param json The line
     * @return The decision, or {@code null} when the line is not a decision record
     * @throws IllegalArgumentException When the line is not JSON or lacks a field
     */
    public static @Nullable StaticCompilationDecision fromJson(String json) {
        if (!(Json.parse(json) instanceof Map<?, ?> map) || !"decision".equals(map.get("record"))) {
            return null;
        }
        String name = (String) Objects.requireNonNull(map.get("name"), "name");
        String source = (String) map.get("source");
        SourceSpan span = source == null ? null : new SourceSpan(source, intOf(map.get("line")), intOf(map.get("column")), intOf(map.get("line")), intOf(map.get("column")) + 1);
        List<Reason> reasons = new ArrayList<>();
        if (map.get("reasons") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> reason) {
                    reasons.add(new Reason((String) Objects.requireNonNull(reason.get("rule"), "rule"), (String) Objects.requireNonNull(reason.get("message"), "message"), spanOf((String) reason.get("location"))));
                }
            }
        }
        Stats stats = Stats.NONE;
        if (map.get("stats") instanceof Map<?, ?> values) {
            stats = new Stats(intOf(values.get("statements")), intOf(values.get("javaCalls")), intOf(values.get("bridgeCalls")), intOf(values.get("helperCalls")));
        }
        return new StaticCompilationDecision(name, span, Outcome.valueOf((String) Objects.requireNonNull(map.get("outcome"), "outcome")), Scope.valueOf((String) Objects.requireNonNull(map.get("scope"), "scope")), reasons, stats);
    }

    private static int intOf(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * The span a {@link SourceSpan#location()} denotes: the path, the line and the column.
     */
    private static @Nullable SourceSpan spanOf(@Nullable String location) {
        if (location == null) {
            return null;
        }
        int columnSeparator = location.lastIndexOf(':');
        int lineSeparator = columnSeparator < 0 ? -1 : location.lastIndexOf(':', columnSeparator - 1);
        if (lineSeparator <= 0) {
            return null;
        }
        try {
            int line = Integer.parseInt(location.substring(lineSeparator + 1, columnSeparator));
            int column = Integer.parseInt(location.substring(columnSeparator + 1));
            return new SourceSpan(location.substring(0, lineSeparator), line, column, line, column + 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * What became of a candidate function.
     */
    public enum Outcome {
        /**
         * The body is compiled to Java.
         */
        COMPILED,
        /**
         * The body passes every check the planner makes; a code generator would compile it.
         */
        CANDIDATE,
        /**
         * Compilation was attempted and refused for the listed reasons, which the author can address.
         */
        SKIPPED,
        /**
         * A decorator excluded the function, its class or its module.
         */
        EXCLUDED,
        /**
         * The function can never be compiled: its kind, its class or its signature rule it out.
         */
        NOT_CANDIDATE
    }

    /**
     * The declaration that decided whether compilation was attempted: the nearest {@code CompileStatic}
     * switch, or the compilation's mode when there is none.
     */
    public enum Scope {
        /**
         * The mode of the compilation.
         */
        MODE,
        /**
         * A switch on the module.
         */
        MODULE,
        /**
         * A switch on the class.
         */
        CLASS,
        /**
         * A switch on the function itself.
         */
        FUNCTION
    }

    /**
     * Why a body is not compiled.
     *
     * @param rule    The stable identifier of the reason
     * @param message What was found
     * @param span    Where, when known
     */
    public record Reason(String rule, String message, @Nullable SourceSpan span) {
        public Reason {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * What a body contains, for the report.
     *
     * @param statements  The statements of the body
     * @param javaCalls   The calls to Java methods and constructors
     * @param bridgeCalls The calls that cross into Python
     * @param helperCalls The calls to the runtime's Python-semantics helpers
     */
    public record Stats(int statements, int javaCalls, int bridgeCalls, int helperCalls) {
        /**
         * No statistics.
         */
        public static final Stats NONE = new Stats(0, 0, 0, 0);
    }
}
