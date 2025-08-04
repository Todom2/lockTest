package org.example.locktest.trip;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SettlementMapper {
    void updateIsPayedByExpenseIdAndMemberID(
            @Param("expenseId")Long expenseId,
            @Param("senderId")Long senderId);
    List<SettlementNotes> searchByExpenseId(Long expenseId);
}
