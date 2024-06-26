/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.core.expression.predicate.regex;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.gen.processor.Processor;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

public class RegexProcessor implements Processor {

    public static class RegexOperation {

        public static Boolean match(Object value, Pattern pattern) {
            if (pattern == null) {
                return Boolean.TRUE;
            }

            if (value == null) {
                return null;
            }

            return pattern.matcher(value.toString()).matches();
        }

        public static Boolean match(Object value, String pattern) {
            return match(value, pattern, Boolean.FALSE);
        }

        public static Boolean match(Object value, String pattern, Boolean caseInsensitive) {
            if (pattern == null) {
                return Boolean.TRUE;
            }

            if (value == null) {
                return null;
            }

            int flags = 0;
            if (Boolean.TRUE.equals(caseInsensitive)) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            return Pattern.compile(pattern, flags).matcher(value.toString()).matches();
        }
    }

    public static final String NAME = "rgx";

    private Pattern pattern;

    public RegexProcessor(String pattern) {
        this.pattern = pattern != null ? Pattern.compile(pattern) : null;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public RegexProcessor(StreamInput in) throws IOException {
        this(in.readOptionalString());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalString(pattern != null ? pattern.toString() : null);
    }

    @Override
    public Object process(Object input) {
        return RegexOperation.match(input, pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        RegexProcessor other = (RegexProcessor) obj;
        return Objects.equals(pattern, other.pattern);
    }
}
