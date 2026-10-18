package com.callverse.core.application.interfaces;

import java.util.List;

/** Durable alert history, reconstructed from the original immutable incident records. */
public interface SupervisionAlertHistory {
    AlertPage list(int page, int size);
    record AlertPage(List<SupervisionAlert> content, int number, int size, long totalElements, int totalPages) {}
}
