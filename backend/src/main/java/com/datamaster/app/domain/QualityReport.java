package com.datamaster.app.domain;

import java.util.List;

public record QualityReport(
        int totalRows,
        int validRows,
        int missingDate,
        int missingProduct,
        int missingCustomer,
        int invalidNumericCells,
        List<String> warnings,
        int profiledNumericErrors,
        int externalWorkbookLinks
) {
    public QualityReport {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public QualityReport(int totalRows, int validRows, int missingDate, int missingProduct,
                         int missingCustomer, int invalidNumericCells, List<String> warnings) {
        this(totalRows, validRows, missingDate, missingProduct, missingCustomer,
                invalidNumericCells, warnings, invalidNumericCells, 0);
    }
}
