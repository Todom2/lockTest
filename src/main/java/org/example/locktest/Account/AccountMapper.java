package org.example.locktest.Account;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.locktest.Account.Account;

import java.math.BigDecimal;

@Mapper
public interface AccountMapper {
    boolean existsByAccountNumberAndAccountPassword(@Param("accountNumber") String accountNumber,
                                                    @Param("accountPassword") String accountPassword);

    boolean existsByNameAndAccountNumberAndAccountPassword(@Param("name") String name,
                                                           @Param("accountNumber") String accountNumber, @Param("accountPassword") String accountPassword);

    void transactionBalance(@Param("receiverId")Long receiverId, @Param("senderId") Long senderId, @Param("amount") BigDecimal amount);

    void updateMemberIdByAccountNumber(@Param("accountNumber") String accountNumber, @Param("memberId") Long memberId);

    Account searchAccountByMemberId(@Param("memberId") Long memberId);

    void withdraw(@Param("accountNumber") String accountNumber, @Param("amount") BigDecimal amount);

    Account searchAccountByMemberIdForUpdate(@Param("memberId") Long memberId);

    int updateBalanceWithVersion(
            @Param("memberId") Long memberId,
            @Param("newBalance") BigDecimal newBalance,
            @Param("currentVersion") Long currentVersion
    );
    int updateBalancesWithOptimisticLock(
            @Param("senderId") Long senderId,
            @Param("receiverId") Long receiverId,
            @Param("amount") BigDecimal amount,
            @Param("senderVersion") Long senderVersion,
            @Param("receiverVersion") Long receiverVersion
    );
}

