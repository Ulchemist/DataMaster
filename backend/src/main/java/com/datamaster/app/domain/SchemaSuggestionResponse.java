package com.datamaster.app.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SchemaSuggestionResponse(
        List<FieldCandidates> fieldCandidates,
        List<Conflict> conflicts,
        List<ConfirmationCode> requiredConfirmations,
        List<LimitationCode> limitations,
        String providerId,
        String model,
        Instant generatedAt
) {
    public SchemaSuggestionResponse {
        fieldCandidates = fieldCandidates == null ? List.of() : List.copyOf(fieldCandidates);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        requiredConfirmations = requiredConfirmations == null ? List.of() : List.copyOf(requiredConfirmations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public record FieldCandidates(String columnId, List<Candidate> candidates) {
        public FieldCandidates {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record Candidate(
            SchemaSuggestionRequest.SemanticField semanticField,
            BigDecimal confidence,
            ReasonCode reasonCode
    ) {
    }

    public record Conflict(
            ConflictCode code,
            List<String> columnIds,
            SchemaSuggestionRequest.SemanticField semanticField,
            ConfirmationCode requiredConfirmation
    ) {
        public Conflict {
            columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
        }
    }

    public enum ReasonCode {
        HEADER_MATCH,
        TYPE_COMPATIBLE,
        NUMERIC_PROFILE_MATCH,
        MULTIPLE_PLAUSIBLE_FIELDS,
        WEAK_SIGNAL
    }

    public enum ConfirmationCode {
        CONFIRM_CURRENCY_UNIT,
        CONFIRM_SIGN_CONVENTION,
        CONFIRM_DATE_GRAIN,
        CONFIRM_REVENUE_FIELD,
        CONFIRM_COST_SCOPE,
        CONFIRM_EXPENSE_SCOPE,
        CONFIRM_PROFIT_DEFINITION,
        CONFIRM_DUPLICATE_GRAIN,
        CONFIRM_PRODUCT_IDENTIFIER,
        CONFIRM_CUSTOMER_IDENTIFIER,
        CONFIRM_LOW_CONFIDENCE_MAPPING
    }

    public enum ConflictCode {
        MULTIPLE_COLUMNS_SAME_ROLE,
        AMBIGUOUS_AMOUNT_SCOPE,
        CONFLICTING_DATE_GRAIN,
        OVERLAPPING_PROFIT_DEFINITION,
        LOW_MARGIN_BETWEEN_CANDIDATES
    }

    public enum LimitationCode {
        PROFILE_ONLY_NO_RAW_VALUES,
        NO_DATE_FIELD,
        NO_REVENUE_FIELD,
        NO_COST_FIELD,
        NO_EXPENSE_FIELD,
        AMBIGUOUS_AMOUNT_FIELDS,
        LOW_CONFIDENCE_MAPPING,
        PROFIT_NOT_DERIVABLE,
        UNSUPPORTED_COMPLEX_HEADER
    }
}
