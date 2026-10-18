package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.SupervisionAlertHistory;

public record GetSupervisionAlertsQueryHandler(SupervisionAlertHistory history) {
    public SupervisionAlertHistory.AlertPage handle(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new InvalidRequestException("page must be nonnegative and size 1 to 100.");
        return history.list(page, size);
    }
}
