package com.callverse.core.application.features.customer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommandHandler;
import com.callverse.core.application.interfaces.*;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerPortalHandlerTest {
    @ParameterizedTest
    @ValueSource(strings = {"+999999999-12-31", "+300000000-01-01", "0000-01-01", "-999999999-01-01"})
    void unsupportedStatementDateIsAnInputErrorBeforeAnyDatabaseCall(String value) {
        var customers = mock(CustomerSelfService.class);
        var portal = new CustomerPortalHandler(mock(CurrentPrincipalProvider.class), customers,
                mock(CustomerRecords.class), mock(OpenConversationCommandHandler.class));
        LocalDate date = LocalDate.parse(value);
        assertThatThrownBy(() -> portal.statement(date, date)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(customers);
    }
}
