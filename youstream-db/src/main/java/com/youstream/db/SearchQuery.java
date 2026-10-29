package com.youstream.db;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns user input into a safe PostgreSQL {@code to_tsquery} string: every word becomes a prefix term and all
 * must match ({@code "sail carib"} -> {@code "sail:* & carib:*"}). Only letters and digits survive, so the
 * result can never contain tsquery operators supplied by the user.
 */
public final class SearchQuery {

    private static final int MAX_TERMS = 8;
    private static final int MAX_TERM_LENGTH = 40;

    private SearchQuery() {
    }

    /** Empty when the input has no searchable words. */
    public static Optional<String> of(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String query = Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
            .filter(t -> !t.isEmpty())
            .map(t -> t.length() > MAX_TERM_LENGTH ? t.substring(0, MAX_TERM_LENGTH) : t)
            .distinct()
            .limit(MAX_TERMS)
            .map(t -> t + ":*")
            .collect(Collectors.joining(" & "));
        return query.isEmpty() ? Optional.empty() : Optional.of(query);
    }
}
