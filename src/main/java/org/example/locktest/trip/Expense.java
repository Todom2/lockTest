package org.example.locktest.trip;

import lombok.*;
import org.example.locktest.Location;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Expense {
    private Long expenseId;
    private Long tripId;
    private Long memberId;
    private String expenseName;
    private BigDecimal amount;
    private Location location;
    private Boolean settlementCompleted;
    private LocalDateTime expenseDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}