package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AccountSummaryDto;
import cz.vsb.minibank.api.dto.NewPaymentRequest;
import cz.vsb.minibank.api.dto.NewPaymentResultDto;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.value.Money;


import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final TransferApplicationService transferService;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FeePolicy feePolicy;

    // Пока без логина — считаем, что работаем от имени customerId = 1
    private static final int CURRENT_CUSTOMER_ID = 2;

    public PaymentController(TransferApplicationService transferService,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FeePolicy feePolicy) {
        this.transferService = transferService;
        this.accounts = accounts;
        this.transfers = transfers;
        this.feePolicy = feePolicy;
    }

    // 1) Список счетов клиента для select "From"
    @GetMapping("/customers/{customerId}/accounts")
    public List<AccountSummaryDto> listAccounts(@PathVariable("customerId") int customerId) {
        return accounts.byCustomerId(customerId).stream()
                .map(this::toAccountSummary)
                .toList();
    }

    // Шорткат: /api/me/accounts → current customer
    @GetMapping("/me/accounts")
    public List<AccountSummaryDto> listMyAccounts() {
        return listAccounts(CURRENT_CUSTOMER_ID);
    }

    // 2) Создать платёж по IBAN (форма WEB-1)
    @PostMapping("/payments")
    public ResponseEntity<NewPaymentResultDto> createPayment(@RequestBody NewPaymentRequest req) {

        int customerId = (req.customerId() != 0)
                ? req.customerId()
                : CURRENT_CUSTOMER_ID;

        // UC 04 – Submit Payment Order (to an arbitrary IBAN)
        int transferId = transferService.submitPaymentToIban(
                customerId,
                req.sourceAccountId(),
                req.targetIban(),
                req.amountCzk(),
                req.message()
        );

        // Репозиторий возвращает Optional → orElseThrow
        Transfer t = transfers.byId(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        boolean authorizationRequired = (t.status() == TransferStatus.WAITING_AUTH);

        Money fee = t.feeAmount(feePolicy);
        Money charged = t.amount().plus(fee);

        NewPaymentResultDto dto = new NewPaymentResultDto(
                t.id(),
                t.status().name(),
                charged.toString(),      // chargedAmount
                fee.toString(),          // feeAmount
                acc.balance().toString(),
                authorizationRequired
        );

        return ResponseEntity.ok(dto);
    }

    private AccountSummaryDto toAccountSummary(Account a) {
        return new AccountSummaryDto(
                a.id(),
                a.iban().value(),         // IBAN → String
                a.balance().toString()    // Money → "amount currency"
        );
    }
}
