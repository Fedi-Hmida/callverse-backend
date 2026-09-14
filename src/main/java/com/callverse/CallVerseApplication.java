package com.callverse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the CallVerse backend.
 *
 * <p>CallVerse is a digital twin of a telecom customer relation center. This service owns the
 * business domain, the queue and routing engine, the SLA rules and the simulation orchestration,
 * and it remains the authority on business rules even when the autonomous agents in the Python
 * service are the ones asking.
 *
 * <p>This class sits in the root package on purpose: component scanning starts here and therefore
 * covers {@code host} and {@code infrastructure}. It does not cover {@code core} in any meaningful
 * sense, because nothing in {@code core} carries a Spring annotation.
 */
@SpringBootApplication
public class CallVerseApplication {

    public static void main(String[] args) {
        SpringApplication.run(CallVerseApplication.class, args);
    }
}
