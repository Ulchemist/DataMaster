package com.datamaster.app.domain;

/**
 * Identifies who produced the explanatory recommendations attached to an analysis.
 * Financial figures and breakdowns are always calculated deterministically.
 */
public enum InsightSource {
    LOCAL_RULES,
    AI
}
