package org.example.locktest.trip;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExpenseMapper {
    Expense searchByExpenseId(@Param("expenseId") Long expenseId);
    void updateSettlementCompleted(@Param("expenseId")Long expenseId, @Param("completed") boolean completed);
}
