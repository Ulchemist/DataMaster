package com.datamaster.app.domain;

public record ModelOption(
        String id,
        String label,
        String description,
        boolean recommended
) {
}
