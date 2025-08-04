package org.example.locktest.trip;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRequestDto {
    private Long memberId;
    private Long expenseId;
    private BigDecimal amount;
}