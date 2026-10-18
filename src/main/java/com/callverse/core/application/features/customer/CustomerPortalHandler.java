package com.callverse.core.application.features.customer;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommand;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommandHandler;
import com.callverse.core.application.interfaces.*;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.UserRole;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Resolves ownership from the authenticated account for every operation. */
public class CustomerPortalHandler {
    private final CurrentPrincipalProvider principals;
    private final CustomerSelfService customers;
    private final CustomerRecords records;
    private final OpenConversationCommandHandler conversations;
    public CustomerPortalHandler(CurrentPrincipalProvider principals, CustomerSelfService customers,
                                 CustomerRecords records, OpenConversationCommandHandler conversations) {
        this.principals = principals; this.customers = customers; this.records = records; this.conversations = conversations;
    }
    private UUID customerId() {
        var caller = principals.current().orElseThrow(AuthenticationRequiredException::new);
        if (caller.role() != UserRole.CUSTOMER) throw new ActionNotPermittedException("Customer access required.");
        return customers.customerId(caller.userId());
    }
    public CustomerProfile profile() {
        UUID id = customerId();
        return records.findProfile(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }
    public List<TransactionSummary> recent(int limit) {
        if (limit < 1 || limit > 50) throw new InvalidRequestException("count must be between 1 and 50");
        return records.findRecentTransactions(customerId(), limit);
    }
    public List<TransactionSummary> statement(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9999
                || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new InvalidRequestException("Statement dates must be between years 0001 and 9999 and span at most 366 days, in chronological order.");
        }
        return customers.statement(customerId(), from, to);
    }
    public ConversationLifecycle.ConversationRecord open(String skill, Intent intent) {
        return conversations.handle(new OpenConversationCommand(customerId(), skill, intent));
    }
}
