package com.callverse.infrastructure.config;

import com.callverse.core.application.features.admin.ReferenceDataAdminHandler;
import com.callverse.core.application.interfaces.AdminReferenceData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReferenceDataAdminConfiguration {
    @Bean
    ReferenceDataAdminHandler referenceDataAdminHandler(AdminReferenceData data) {
        return new ReferenceDataAdminHandler(data);
    }
}
