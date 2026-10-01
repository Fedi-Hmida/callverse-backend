package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.TransactionSummary;
import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Customer;
import com.callverse.infrastructure.persistence.repositories.BankTransactionRepository;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link CustomerRecords} with Spring Data.
 *
 * <p><strong>Read-only transaction around every method</strong>, because the profile walks two lazy
 * associations (accounts, then each account's product) and {@code open-in-view} is off: outside a
 * transaction the first of them would throw. The records are complete when they leave this class,
 * so nothing lazy escapes. The walk is one query per account for the product; a customer has a
 * handful of accounts, so a fetch join is not worth its complexity here.
 *
 * <p>Package-private: callers name the port, never the adapter.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CustomerRecordsAdapter implements CustomerRecords {

    private final CustomerRepository customers;
    private final BankTransactionRepository transactions;

    @Override
    public Optional<CustomerProfile> findProfile(UUID customerId) {
        return customers.findById(customerId).map(CustomerRecordsAdapter::toProfile);
    }

    @Override
    public boolean exists(UUID customerId) {
        return customers.existsById(customerId);
    }

    @Override
    public List<TransactionSummary> findRecentTransactions(UUID customerId, int limit) {
        return transactions.findRecentForCustomer(customerId, PageRequest.of(0, limit)).stream()
                .map(CustomerRecordsAdapter::toSummary)
                .toList();
    }

    private static CustomerProfile toProfile(Customer customer) {
        List<CustomerProfile.Account> accounts =
                customer.getAccounts().stream()
                        // Newest first, so the account the customer is most likely calling about leads.
                        .sorted(Comparator.comparing(Account::getOpenedAt).reversed())
                        .map(CustomerRecordsAdapter::toAccount)
                        .toList();
        return new CustomerProfile(
                customer.getId(),
                customer.getExternalRef(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getRegion(),
                customer.getSegment(),
                customer.getTenureMonths(),
                accounts);
    }

    private static CustomerProfile.Account toAccount(Account account) {
        BankingProduct product = account.getProduct();
        return new CustomerProfile.Account(
                account.getId(),
                account.getIban(),
                account.getCurrency(),
                account.getBalance(),
                account.getOverdraftLimit(),
                account.getStatus(),
                account.getOpenedAt(),
                account.getClosedAt(),
                new CustomerProfile.Product(product.getCode(), product.getName(), product.getCategory()));
    }

    private static TransactionSummary toSummary(BankTransaction transaction) {
        return new TransactionSummary(
                transaction.getId(),
                // Reading the id of a lazy proxy does not initialise it.
                transaction.getAccount().getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getLabel(),
                transaction.getCounterparty(),
                transaction.getStatus(),
                transaction.getBookedAt());
    }
}
