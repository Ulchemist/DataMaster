package com.datamaster.app.domain;

import java.util.List;

public record AnalysisSession(AnalysisResult result, List<DataRow> rows) {
    public AnalysisSession {
        rows = List.copyOf(rows);
    }

    public AnalysisSession withResult(AnalysisResult value) {
        return new AnalysisSession(value, rows);
    }
}
