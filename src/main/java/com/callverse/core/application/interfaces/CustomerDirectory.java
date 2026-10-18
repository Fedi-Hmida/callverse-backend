package com.callverse.core.application.interfaces;

import java.util.List;
import java.util.UUID;

/** Minimal real-customer directory for the existing staff contact-registration workflow. */
public interface CustomerDirectory {
    Page search(String query, int page);
    record Item(UUID id, String firstName, String lastName, String region) {}
    record Page(List<Item> content, int page, int size, boolean hasNext) {}
}
