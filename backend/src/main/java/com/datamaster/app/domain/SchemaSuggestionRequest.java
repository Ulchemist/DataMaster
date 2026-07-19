package com.datamaster.app.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Privacy-minimised schema profile sent to the mapping assistant.
 *
 * <p>This contract deliberately has no file name, sheet name, raw-row or sample-value field.  The
 * model sees only structural metadata and aggregate numeric characteristics.</p>
 */
public record SchemaSuggestionRequest(
        String providerId,
        List<ColumnProfile> columns
) {
    public SchemaSuggestionRequest {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public record ColumnProfile(
            String columnId,
            String header,
            InferredType inferredType,
            BigDecimal coverage,
            NumericProfile numericProfile,
            List<SemanticField> candidateSemantics
    ) {
        public ColumnProfile {
            candidateSemantics = candidateSemantics == null ? List.of() : List.copyOf(candidateSemantics);
        }
    }

    public record NumericProfile(
            BigDecimal minimum,
            BigDecimal maximum,
            BigDecimal mean,
            BigDecimal median,
            BigDecimal zeroRatio,
            BigDecimal negativeRatio
    ) {
    }

    public enum InferredType {
        TEXT, INTEGER, DECIMAL, DATE, DATETIME, BOOLEAN, MIXED, UNKNOWN
    }

    public enum SemanticField {
        DATE,
        REVENUE,
        COST,
        EXPENSE,
        PROFIT,
        QUANTITY,
        UNIT_PRICE,
        PRODUCT,
        CUSTOMER,
        ORDER_ID,
        REGION,
        CHANNEL,
        SALESPERSON,
        CATEGORY,
        DISCOUNT,
        TAX,
        CURRENCY,
        UNKNOWN
    }
}
