package com.callverse.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.AccessibleConversationDirectory;
import com.callverse.core.domain.enums.UserRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AccessibleConversationDirectoryTest {
    @Test void appliesOwnershipInSqlBeforePaginationForEachRole() {
        for (var role : UserRole.values()) {
            var jdbc = mock(NamedParameterJdbcTemplate.class);
            when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<AccessibleConversationDirectory.Item>>any())).thenReturn(List.of());
            var caller = new AuthenticatedPrincipal(UUID.randomUUID(), "test@example.local", role);
            new AccessibleConversationDirectoryAdapter(jdbc).search(caller, "Alice", null, 1);
            var sql = ArgumentCaptor.forClass(String.class);
            @SuppressWarnings("unchecked") var params = ArgumentCaptor.forClass(Map.class);
            verify(jdbc).query(sql.capture(), params.capture(), org.mockito.ArgumentMatchers.<RowMapper<AccessibleConversationDirectory.Item>>any());
            assertThat(sql.getValue()).contains("c.run_id is null").contains("limit 31 offset :offset");
            assertThat(params.getValue()).containsEntry("userId", caller.userId()).containsEntry("offset", 30).containsEntry("query", "Alice");
            if (role == UserRole.CUSTOMER) assertThat(sql.getValue()).contains("and (cu.user_id = :userId)");
            else if (role == UserRole.ADVISOR) assertThat(sql.getValue()).contains("and (a.user_id = :userId)");
            else assertThat(sql.getValue()).contains("and (true)");
        }
    }
    @Test void rejectsUnboundedSearchBeforeDatabaseAccess() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var adapter = new AccessibleConversationDirectoryAdapter(jdbc);
        var caller = new AuthenticatedPrincipal(UUID.randomUUID(), "test@example.local", UserRole.CUSTOMER);
        assertThatThrownBy(() -> adapter.search(caller, "", null, -1)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> adapter.search(caller, "x".repeat(121), null, 0)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(jdbc);
    }
}
