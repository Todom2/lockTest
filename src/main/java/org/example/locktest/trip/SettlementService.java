package org.example.locktest.trip;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.locktest.Account.Account;
import org.example.locktest.Account.AccountMapper;
import org.example.locktest.BusinessException;
import org.example.locktest.StatusCode;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class SettlementService {
    private final ExpenseMapper expenseMapper;
    private final AccountMapper accountMapper;
    private final SettlementMapper settlementMapper;

    private static final int MAX_RETRIES = 50;
    private static final long RETRY_DELAY_MS = 200;

    @Transactional
    public boolean settle(SettlementRequestDto dto){
        log.info("settle 메서드 호출 시작: {}", dto);
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("settle 실패: 금액이 유효하지 않습니다. amount={}", dto.getAmount());
            throw new BusinessException(StatusCode.BAD_REQUEST, "금액은 0원 이상이여야 합니다.");
        }
        for(int retryCount = 0; retryCount < MAX_RETRIES ; retryCount++){
            try{
                Expense expense = expenseMapper.searchByExpenseId(dto.getExpenseId());
                if (expense == null) {
                    log.warn("settle 실패: expenseId {}에 해당하는 Expense를 찾을 수 없습니다.", dto.getExpenseId());
                    throw new BusinessException(StatusCode.INTERNAL_ERROR, "정산 처리 중 내부 데이터 오류가 발생했습니다. (관련 비용을 찾을 수 없음)");
                }
                log.info("expenseId : {}", expense.getMemberId());
                Long senderId = dto.getMemberId();
                log.info("senderId : {}", senderId);
                Long receiverId = expense.getMemberId();

                Account receiverAccount;
                Account senderAccount;

                try {
                    if (senderId.compareTo(receiverId) < 0) {
                        senderAccount = accountMapper.searchAccountByMemberIdForUpdate(senderId);
                        receiverAccount = accountMapper.searchAccountByMemberIdForUpdate(receiverId);
                    } else {
                        receiverAccount = accountMapper.searchAccountByMemberIdForUpdate(receiverId);
                        senderAccount = accountMapper.searchAccountByMemberIdForUpdate(senderId);
                    }
                } catch (PessimisticLockingFailureException e) {
                    log.warn("settle: 잠금 획득 실패 (비관적 잠금 경합). 재시도 #{}", retryCount + 1);
                    throw e;
                } catch (DataAccessException e) {
                    log.error("settle 실패: 계좌 조회 및 잠금 중 DB 오류 발생 - {}", e.getMessage(), e);
                    throw new BusinessException(StatusCode.INTERNAL_ERROR, "연동 계좌 조회 중 서버 오류가 발생했습니다.");
                }

                if (senderAccount == null || receiverAccount == null) {
                    log.warn("settle 실패: 연동된 계좌를 찾을 수 없습니다. senderId={}, receiverId={}", senderId, receiverId);
                    throw new BusinessException(StatusCode.BAD_REQUEST, "연동된 계좌를 찾을 수 없습니다.");
                }

                BigDecimal amount = verificationAmount(dto, senderAccount, receiverAccount);
                log.info("amount : {}",amount);

                if (senderAccount.getBalance().subtract(amount).compareTo(BigDecimal.ZERO) < 0) {
                    log.warn("settle 실패: 계좌 잔액 부족. senderId={}, balance={}, amount={}", senderId, senderAccount.getBalance(), amount);
                    throw new BusinessException(StatusCode.BAD_REQUEST, "계좌 잔액을 확인해주세요.");
                }

                try {
                    accountMapper.transactionBalance(receiverId, senderId, amount);
                    log.info("settle: 계좌 트랜잭션 완료. senderId {} -> receiverId {} 에게 {}원 송금.", senderId, receiverId, amount);
                } catch (DataAccessException e) {
                    log.error("settle 실패: 계좌 이체 중 DB 오류 발생 - {}", e.getMessage(), e);
                    throw new BusinessException(StatusCode.INTERNAL_ERROR, "계좌 이체 중 서버 오류가 발생했습니다.");
                }

                try {
                    settlementMapper.updateIsPayedByExpenseIdAndMemberID(dto.getExpenseId(), senderId);
                    log.info("settle: SettlementNotes.isPayed 업데이트 완료. expenseId={}, memberId={}", dto.getExpenseId(), senderId);
                } catch (DataAccessException e) {
                    log.error("settle 실패: SETTLEMENT_NOTES is_payed 업데이트 중 DB 오류 발생 - {}", e.getMessage(), e);
                    throw new BusinessException(StatusCode.INTERNAL_ERROR, "정산 내역 업데이트 중 서버 오류가 발생했습니다.");
                }

                try{
                    List<SettlementNotes> settlementNotes = settlementMapper.searchByExpenseId(dto.getExpenseId());
                    boolean allSettlementsPayed = true;
                    for(SettlementNotes settlementNote : settlementNotes) {
                        if(settlementNote.getIsPayed() == null ||  !settlementNote.getIsPayed()) {
                            allSettlementsPayed = false;
                            break;
                        }
                    }
                    if (allSettlementsPayed) {
                        expenseMapper.updateSettlementCompleted(dto.getExpenseId(), true);
                        log.info("settle: Expense.settlement_completed 업데이트 완료. expenseId={}", dto.getExpenseId());
                    }
                } catch (DataAccessException e) {
                    log.error("settle 실패: 전체 정산 완료 상태 업데이트 중 DB 오류 발생 - {}", e.getMessage(), e);
                    throw new BusinessException(StatusCode.INTERNAL_ERROR, "전체 정산 상태 업데이트 중 서버 오류가 발생했습니다.");
                }

                log.info("settle 메서드 완료: expenseId={}", dto.getExpenseId());
                return true;
            } catch (BusinessException e) {
                throw e;
            } catch (PessimisticLockingFailureException e) {
                log.warn("settle: 잠금 획득 실패 또는 데드락 발생. 재시도 시도 중 ({} / {}).", retryCount + 1, MAX_RETRIES);
                if (retryCount < MAX_RETRIES - 1) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS * (retryCount + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(StatusCode.INTERNAL_ERROR, "정산 처리 중 재시도 대기 오류가 발생했습니다.");
                    }
                } else {
                    log.error("settle 실패: 최대 재시도 횟수 초과. expenseId={}", dto.getExpenseId(), e);
                    throw new BusinessException(StatusCode.INTERNAL_ERROR, "일시적인 서버 부하로 정산에 실패했습니다. 잠시 후 다시 시도해주세요.");
                }
            } catch (DataAccessException e) {
                log.error("settle 실패: 알 수 없는 DB 오류 발생 (재시도 대상 아님) - {}", e.getMessage(), e);
                throw new BusinessException(StatusCode.INTERNAL_ERROR, "정산 처리 중 서버 오류가 발생했습니다.");
            }
        }
        return false;
    }

    private static BigDecimal verificationAmount(SettlementRequestDto dto, Account senderAccount, Account receiverAccount) {
        BigDecimal amount = dto.getAmount();
        if(amount == null || amount.compareTo(BigDecimal.ZERO) <= 0){
            throw new BusinessException(StatusCode.BAD_REQUEST, "금액은 0원 이상이여야 합니다.");
        }
        if(senderAccount.getBalance().subtract(amount).compareTo(BigDecimal.ZERO)<=0){
            throw new BusinessException(StatusCode.BAD_REQUEST, "계좌 잔액을 확인해주세요.");
        }
        return amount;
    }

    // 낙관적 락 메서드 - 재시도 로직만 담당 (트랜잭션 없음)
    public int settle2(SettlementRequestDto dto) {
        log.info("settle2 메서드 호출 시작: {}", dto);
        for (int retryCount = 0; retryCount < MAX_RETRIES; retryCount++) {
            try {
                // 실제 트랜잭션 로직을 별도 메서드로 호출
                executeSettle2Transaction(dto);
                return retryCount;
            } catch (OptimisticLockingFailureException e) {
                log.warn("settle2: 낙관적 잠금 실패 (버전 충돌). 재시도 시도 중 ({} / {}).", retryCount + 1, MAX_RETRIES);
                if (retryCount < MAX_RETRIES - 1) {
                    try {
                        long baseDelay = RETRY_DELAY_MS * (retryCount + 1);
                        long randomDelay = (long) (Math.random() * baseDelay * 0.5);
                        TimeUnit.MILLISECONDS.sleep(baseDelay + randomDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(StatusCode.INTERNAL_ERROR, "정산 처리 중 재시도 대기 오류가 발생했습니다.");
                    }
                } else {
                    log.error("settle2 실패: 최대 재시도 횟수 초과. expenseId={}", dto.getExpenseId(), e);
                    throw new BusinessException(StatusCode.INTERNAL_ERROR, "일시적인 서버 부하로 정산에 실패했습니다.");
                }
            }
        }
        throw new BusinessException(StatusCode.INTERNAL_ERROR, "최대 재시도 횟수 초과");
    }

    // 실제 비즈니스 로직을 담은 private 메서드 (새로운 트랜잭션 적용)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected boolean executeSettle2Transaction(SettlementRequestDto dto) {
        log.info("executeSettle2Transaction 메서드 호출 시작: {}", dto);
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("executeSettle2Transaction 실패: 금액이 유효하지 않습니다. amount={}", dto.getAmount());
            throw new BusinessException(StatusCode.BAD_REQUEST, "금액은 0원 이상이여야 합니다.");
        }
        Expense expense = expenseMapper.searchByExpenseId(dto.getExpenseId());
        if (expense == null) {
            log.warn("executeSettle2Transaction 실패: expenseId {}에 해당하는 Expense를 찾을 수 없습니다.", dto.getExpenseId());
            throw new BusinessException(StatusCode.INTERNAL_ERROR, "정산 처리 중 내부 데이터 오류가 발생했습니다. (관련 비용을 찾을 수 없음)");
        }
        Long senderId = dto.getMemberId();
        Long receiverId = expense.getMemberId();

        Account senderAccount = accountMapper.searchAccountByMemberId(senderId);
        Account receiverAccount = accountMapper.searchAccountByMemberId(receiverId);

        if (senderAccount == null || receiverAccount == null) {
            log.warn("executeSettle2Transaction 실패: 연동된 계좌를 찾을 수 없습니다. senderId={}, receiverId={}", senderId, receiverId);
            throw new BusinessException(StatusCode.BAD_REQUEST, "연동된 계좌를 찾을 수 없습니다.");
        }
        BigDecimal amount = verificationAmount(dto, senderAccount, receiverAccount);
        if (senderAccount.getBalance().subtract(amount).compareTo(BigDecimal.ZERO) < 0) {
            log.warn("executeSettle2Transaction 실패: 계좌 잔액 부족. senderId={}, balance={}, amount={}", senderId, senderAccount.getBalance(), amount);
            throw new BusinessException(StatusCode.BAD_REQUEST, "계좌 잔액을 확인해주세요.");
        }
        try {
            int updateCount = accountMapper.updateBalancesWithOptimisticLock(
                    senderId,
                    receiverId,
                    amount,
                    senderAccount.getVersion(),
                    receiverAccount.getVersion()
            );

            if (updateCount != 2) {
                throw new OptimisticLockingFailureException("계좌 버전 충돌");
            }
            log.info("executeSettle2Transaction: 계좌 트랜잭션 완료. senderId {} -> receiverId {} 에게 {}원 송금.", senderId, receiverId, amount);
        } catch (DataAccessException e) {
            log.error("executeSettle2Transaction 실패: 계좌 업데이트 중 DB 오류 발생 - {}", e.getMessage(), e);
            throw new BusinessException(StatusCode.INTERNAL_ERROR, "계좌 이체 중 서버 오류가 발생했습니다.");
        }
        try {
            settlementMapper.updateIsPayedByExpenseIdAndMemberID(dto.getExpenseId(), senderId);
            log.info("executeSettle2Transaction: SettlementNotes.isPayed 업데이트 완료. expenseId={}, memberId={}", dto.getExpenseId(), senderId);
        } catch (DataAccessException e) {
            log.error("executeSettle2Transaction 실패: SETTLEMENT_NOTES is_payed 업데이트 중 DB 오류 발생 - {}", e.getMessage(), e);
            throw new BusinessException(StatusCode.INTERNAL_ERROR, "정산 내역 업데이트 중 서버 오류가 발생했습니다.");
        }
        try{
            List<SettlementNotes> settlementNotes = settlementMapper.searchByExpenseId(dto.getExpenseId());
            boolean allSettlementsPayed = settlementNotes.stream().allMatch(s -> s.getIsPayed() != null && s.getIsPayed());

            if (allSettlementsPayed) {
                expenseMapper.updateSettlementCompleted(dto.getExpenseId(), true);
                log.info("executeSettle2Transaction: Expense.settlement_completed 업데이트 완료. expenseId={}", dto.getExpenseId());
            }
        } catch (DataAccessException e) {
            log.error("executeSettle2Transaction 실패: 전체 정산 완료 상태 업데이트 중 DB 오류 발생 - {}", e.getMessage(), e);
            throw new BusinessException(StatusCode.INTERNAL_ERROR, "전체 정산 상태 업데이트 중 서버 오류가 발생했습니다.");
        }
        log.info("executeSettle2Transaction 메서드 완료: expenseId={}", dto.getExpenseId());
        return true;
    }
}