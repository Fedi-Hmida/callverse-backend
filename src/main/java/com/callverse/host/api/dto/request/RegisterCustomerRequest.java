package com.callverse.host.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterCustomerRequest(
        @NotBlank @Email @Size(max=180) String email,
        @NotBlank @Size(min=12,max=72) String password,
        @NotBlank @Size(max=80) String firstName,
        @NotBlank @Size(max=80) String lastName,
        @NotBlank @Size(max=40) String region,
        @Size(max=30) String phone) {
    @Override public String toString() { return "RegisterCustomerRequest[credentials=redacted]"; }
}
