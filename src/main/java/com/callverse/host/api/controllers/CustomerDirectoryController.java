package com.callverse.host.api.controllers;

import com.callverse.core.application.interfaces.CustomerDirectory;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
class CustomerDirectoryController {
    private final CustomerDirectory directory;

    @GetMapping("/directory")
    @PreAuthorize(Roles.STAFF)
    @Operation(operationId = "listCustomersForContact", summary = "Browse real customers for contact registration, staff only")
    public CustomerDirectory.Page list(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page) {
        return directory.search(q, page);
    }
}
