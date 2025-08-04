package org.example.locktest.Account;

import lombok.*;
import org.example.locktest.Bank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
@ToString
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private Long accountId;
    private Long memberId;
    private String accountNumber;
    private String accountPassword;
    private Bank bank;
    private BigDecimal balance;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
