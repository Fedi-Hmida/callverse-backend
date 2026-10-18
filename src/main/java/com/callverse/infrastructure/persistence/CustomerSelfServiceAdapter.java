package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.CustomerSelfService;
import com.callverse.core.application.interfaces.TransactionSummary;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.*;
import com.callverse.core.domain.exceptions.EmailAlreadyUsedException;
import com.callverse.infrastructure.persistence.repositories.AppUserRepository;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class CustomerSelfServiceAdapter implements CustomerSelfService {
    private final AppUserRepository users;
    private final CustomerRepository customers;
    private final JdbcTemplate jdbc;

    @Override @Transactional
    public Registered register(Registration registration) {
        if (users.existsByEmailIgnoreCase(registration.email())) throw new EmailAlreadyUsedException();
        AppUser user = new AppUser();
        user.setEmail(registration.email()); user.setPasswordHash(registration.passwordHash());
        user.setFirstName(registration.firstName()); user.setLastName(registration.lastName());
        user.setRole(UserRole.CUSTOMER); user.setActive(true);
        try { users.saveAndFlush(user); }
        catch (DataIntegrityViolationException failure) {
            if (String.valueOf(failure.getMostSpecificCause().getMessage()).contains("app_user_email_key"))
                throw new EmailAlreadyUsedException();
            throw failure;
        }
        Customer customer = new Customer();
        customer.setUser(user); customer.setExternalRef("WEB-" + UUID.randomUUID());
        customer.setFirstName(registration.firstName()); customer.setLastName(registration.lastName());
        customer.setRegion(registration.region()); customer.setPhone(registration.phone());
        customer.setSimulated(false);
        customers.saveAndFlush(customer);
        // A portal identity is not a bank account: no balances, cards or IBANs are invented here.
        return new Registered(user.getId(), user.getEmail());
    }

    @Override @Transactional(readOnly = true)
    public UUID customerId(UUID userId) {
        List<UUID> matches = jdbc.query("select id from customer where user_id=? and is_simulated=false order by created_at,id limit 2",
                (rs, row) -> rs.getObject("id", UUID.class), userId);
        if (matches.isEmpty()) throw new ResourceNotFoundException("Customer profile", userId);
        if (matches.size() > 1) throw new InvalidRequestException("Multiple linked customer profiles require staff review.");
        return matches.getFirst();
    }

    @Override @Transactional(readOnly = true)
    public List<TransactionSummary> statement(UUID customerId, LocalDate from, LocalDate to) {
        List<TransactionSummary> result = jdbc.query("""
            select t.* from bank_transaction t join account a on a.id=t.account_id
            where a.customer_id=? and t.booked_at>=? and t.booked_at<?
            order by t.booked_at desc,t.id limit 5001
            """, (rs, row) -> new TransactionSummary(rs.getObject("id",UUID.class), rs.getObject("account_id",UUID.class),
                TransactionType.valueOf(rs.getString("type")), rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getString("label"),rs.getString("counterparty"),TransactionStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("booked_at").toInstant()), customerId,
                Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)), Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        if (result.size()>5000) throw new InvalidRequestException("Choose a shorter period (maximum 5000 movements).");
        return result;
    }
}
