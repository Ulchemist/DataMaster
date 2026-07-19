package com.datamaster.app.domain;

import java.math.BigDecimal;

/** Non-sensitive aggregate statistics for one source column, suitable for AI schema reasoning. */
public record ColumnProfile(
        String columnId,
        String header,
        String source,
        int columnIndex,
        String inferredType,
        long nonBlankCount,
        long validNumericCount,
        long rowCount,
        BigDecimal coverage,
        BigDecimal minimum,
        BigDecimal maximum,
        BigDecimal mean,
        BigDecimal negativeRatio,
        int distinctSampleCount,
        boolean distinctSampleTruncated
) {
}
