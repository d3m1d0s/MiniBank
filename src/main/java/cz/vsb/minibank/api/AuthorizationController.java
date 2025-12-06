package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AuthorizePaymentRequest;
import cz.vsb.minibank.api.dto.AuthorizePaymentResult;
import cz.vsb.minibank.api.dto.TransferDetailsDto;
import cz.vsb.minibank.api.dto.WaitingTransferItemDto;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.value.Money;


import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import static cz.vsb.minibank.api.AuthHelpers.requireCustomerId;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api")
public class AuthorizationController {

    private final TransferApplicationService transferService;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FeePolicy feePolicy;


    public AuthorizationController(TransferApplicationService transferService,
                                   AccountRepository accounts,
                                   TransferRepository transfers,
                                   FeePolicy feePolicy) {
        this.transferService = transferService;
        this.accounts = accounts;
        this.transfers = transfers;
        this.feePolicy = feePolicy;
    }


    // 1) Список всех WAITING_AUTH переводов клиента (таблица слева на WEB-2)
    @GetMapping("/customers/{customerId}/waiting-transfers")
    public List<WaitingTransferItemDto> listWaiting(@PathVariable("customerId") int customerId) {
        List<WaitingTransferItemDto> result = new ArrayList<>();

        // Берём все счета клиента
        for (Account acc : accounts.byCustomerId(customerId)) {
            // И все переводы с каждого счёта
            for (Transfer t : transfers.bySourceAccount(acc.id())) {
                if (t.status() == TransferStatus.WAITING_AUTH) {
                    result.add(new WaitingTransferItemDto(
                            t.id(),
                            t.targetIbanSnapshot(),
                            t.amount().toString(),             // "1340.00 CZK"
                            t.createdAt().toString(),          // ISO-строка
                            t.authMethod() != null
                                    ? t.authMethod().toString()
                                    : ""
                    ));
                }
            }
        }
        return result;
    }

    // Шорткат: /api/me/waiting-transfers
    @GetMapping("/me/waiting-transfers")
    public List<WaitingTransferItemDto> listMyWaiting() {
        int customerId = requireCustomerId();
        return listWaiting(customerId);
    }

    // 2) Детали конкретного перевода (правая панель WEB-2)
    @GetMapping("/transfers/{id}")
    public TransferDetailsDto transferDetails(@PathVariable("id") int id) {
        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        var fee = t.feeAmount(feePolicy);

        // Остаток попыток
        int maxAttempts = TransferApplicationService.MAX_OTP_ATTEMPTS;       // или своё значение
        int triesLeft = Math.max(0, maxAttempts - t.authAttempts());

        // Время, когда истечёт авторизация
        String authValidUntilStr = null;
        if (t.authValidUntil() != null) {
            authValidUntilStr = t.authValidUntil().toString(); // ISO-строка
        }

        return new TransferDetailsDto(
                t.id(),
                acc.iban().value(),            // fromIban
                acc.balance().toString(),      // fromBalance
                t.targetIbanSnapshot(),        // toIban
                t.amount().toString(),         // amount
                fee.toString(),                // feeAmount
                t.status().name(),             // status
                t.createdAt().toString(),      // createdAt
                t.authMethod() != null
                        ? t.authMethod().toString()
                        : "",
                triesLeft,                     // triesLeft
                authValidUntilStr              // authValidUntil
        );
    }



    // 3) Авторизация платежа (UC05)
    @PostMapping("/transfers/{id}/authorize")
    public AuthorizePaymentResult authorize(@PathVariable("id") int id,
                                            @RequestBody AuthorizePaymentRequest req) {
        // Вся бизнес-логика уже в сервисе
        transferService.authorizePayment(id, req.otp());

        // Достаём обновлённое состояние
        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        // Считаем chargedAmount только если реально отправили платёж
        String chargedAmount = null;
        if (t.status() == TransferStatus.SENT) {
            var fee = t.feeAmount(feePolicy);        // Money
            var total = t.amount().plus(fee);        // amount + fee
            chargedAmount = total.toString();        // "10525.00 CZK"
        }

        return new AuthorizePaymentResult(
                t.id(),
                t.status().name(),
                chargedAmount,                // null, если DECLINED / WAITING_AUTH
                acc.balance().toString(),
                t.declineReason()
        );
    }


    // 4) Отмена платежа клиентом (UC19)
    @PostMapping("/transfers/{id}/cancel")
    public AuthorizePaymentResult cancel(@PathVariable("id") int id) {
        transferService.cancelPayment(id);

        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        return new AuthorizePaymentResult(
                t.id(),
                t.status().name(),
                null,                        // ничего не списывали
                acc.balance().toString(),
                t.declineReason()
        );
    }
}
