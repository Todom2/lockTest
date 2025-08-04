package org.example.locktest.trip;

import org.example.locktest.Account.Account;
import org.example.locktest.Account.AccountMapper;
import org.example.locktest.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class SettlementServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SettlementServiceTest.class);

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private ExpenseMapper expenseMapper;

    @Autowired
    private SettlementMapper settlementMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int THREAD_COUNT = 2;
    private static final int OPERATIONS_PER_THREAD = 100;
    private static final BigDecimal TRANSFER_AMOUNT = new BigDecimal("1000");

    @BeforeEach
    void setUp() {
        log.info("Test data initialization...");

        // Add version column if not exists
        try {
            jdbcTemplate.execute("ALTER TABLE ACCOUNT ADD COLUMN version INT DEFAULT 0");
        } catch (Exception e) {
            // Column already exists, ignore
        }

        // Reset test data
        jdbcTemplate.update("UPDATE ACCOUNT SET balance = 1000000.00, version = 0 WHERE account_id IN (1, 2, 3, 4)");
        jdbcTemplate.update("UPDATE SETTLEMENT_NOTES SET is_payed = false");
        jdbcTemplate.update("UPDATE EXPENSE SET settlement_completed = false");
    }

    @Test
    void testDatabaseConnection() {
        Integer accountCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class);
        Integer expenseCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM EXPENSE", Integer.class);
        Integer settlementCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SETTLEMENT_NOTES", Integer.class);

        log.info("Database status - Accounts: {}, Expenses: {}, Settlements: {}",
                accountCount, expenseCount, settlementCount);

        assertTrue(accountCount > 0, "Account table should have data");
        assertTrue(expenseCount > 0, "Expense table should have data");
        assertTrue(settlementCount > 0, "Settlement notes table should have data");
    }

    @Test
    void testSingleSettlement() {
        // 실제 데이터로 테스트
        SettlementRequestDto dto = new SettlementRequestDto();
        dto.setExpenseId(1L);
        dto.setMemberId(3L);
        dto.setAmount(new BigDecimal("64333"));

        boolean result = settlementService.settle(dto);
        assertTrue(result);
        log.info("Single settlement test success");
    }

    @Test
    void comparePessimisticVsOptimisticLock() throws InterruptedException {
        log.info("=== Performance Test Start ===");
        log.info("Thread count: {}, Operations per thread: {}", THREAD_COUNT, OPERATIONS_PER_THREAD);

        // 1. Pessimistic lock test
        PerformanceResult pessimisticResult = testPessimisticLock();

        // 2. Reset data
        resetTestData();
        Thread.sleep(1000);

        // 3. Optimistic lock test
        PerformanceResult optimisticResult = testOptimisticLock();

        // 4. Compare results
        printResults(pessimisticResult, optimisticResult);
    }

    private PerformanceResult testPessimisticLock() throws InterruptedException {
        log.info("\n=== Pessimistic Lock (settle) Test Start ===");
        return runPerformanceTest(true);
    }

    private PerformanceResult testOptimisticLock() throws InterruptedException {
        log.info("\n=== Optimistic Lock (settle2) Test Start ===");
        return runPerformanceTest(false);
    }

    private PerformanceResult runPerformanceTest(boolean usePessimisticLock) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger retryCount = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);

        List<Long> responseTimes = new CopyOnWriteArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        long startTime = System.currentTimeMillis();

                        try {
                            // 정산 노트 중에서 아직 지불되지 않은 것 찾기
                            List<SettlementNotes> unpaidNotes = jdbcTemplate.query(
                                    "SELECT * FROM SETTLEMENT_NOTES WHERE is_payed = false AND received = true",
                                    (rs, rowNum) -> {
                                        SettlementNotes note = new SettlementNotes();
                                        note.setSettlementId(rs.getLong("settlement_id"));
                                        note.setExpenseId(rs.getLong("expense_id"));
                                        note.setMemberId(rs.getLong("member_id"));
                                        note.setShareAmount(rs.getBigDecimal("share_amount"));
                                        note.setIsPayed(rs.getBoolean("is_payed"));
                                        return note;
                                    }
                            );

                            if (!unpaidNotes.isEmpty()) {
                                // 순환하면서 정산 실행
                                SettlementNotes targetNote = unpaidNotes.get(j % unpaidNotes.size());

                                SettlementRequestDto dto = new SettlementRequestDto();
                                dto.setExpenseId(targetNote.getExpenseId());
                                dto.setMemberId(targetNote.getMemberId());
                                dto.setAmount(targetNote.getShareAmount());

                                log.debug("Thread {} executing settlement for expense {} member {}",
                                        threadId, dto.getExpenseId(), dto.getMemberId());

                                try {
                                    if (usePessimisticLock) {
                                        boolean success = settlementService.settle(dto);
                                        if (success) {
                                            successCount.incrementAndGet();
                                        }
                                    } else {
                                        try {
                                            int retries = settlementService.settle2(dto);
                                            successCount.incrementAndGet();
                                            retryCount.addAndGet(retries); // 반환된 재시도 횟수 누적
                                        } catch (BusinessException e) {
                                            failureCount.incrementAndGet();
                                            log.debug("Thread {} - Operation failed with BusinessException: {}", threadId, e.getMessage());
                                        }
                                    }
                                } catch (BusinessException e) {
                                    failureCount.incrementAndGet();
                                    log.debug("Thread {} - Operation failed with BusinessException: {}", threadId, e.getMessage());
                                }
                            } else {
                                log.warn("No unpaid settlement notes found, counting as a failure");
                                failureCount.incrementAndGet(); // 데이터가 없는 경우도 실패로 카운트
                            }

                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                            if (e.getMessage() != null && e.getMessage().contains("계좌") ||
                                    e.getMessage() != null && e.getMessage().contains("재시도")) {
                                retryCount.incrementAndGet();
                            }
                            log.debug("Thread {} - Operation {} failed: {}", threadId, j, e.getMessage());
                        }

                        long responseTime = System.currentTimeMillis() - startTime;
                        responseTimes.add(responseTime);
                        totalResponseTime.addAndGet(responseTime);
                    }

                } catch (Exception e) {
                    log.error("Thread {} execution error", threadId, e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long testStartTime = System.currentTimeMillis();
        startLatch.countDown();

        boolean completed = endLatch.await(5, TimeUnit.MINUTES);
        long totalTestTime = System.currentTimeMillis() - testStartTime;

        executor.shutdown();

        if (!completed) {
            log.error("Test timeout!");
            executor.shutdownNow();
        }

        PerformanceResult result = new PerformanceResult();
        result.lockType = usePessimisticLock ? "Pessimistic" : "Optimistic";
        result.totalOperations = THREAD_COUNT * OPERATIONS_PER_THREAD;
        result.successCount = successCount.get();
        result.failureCount = failureCount.get();
        result.retryCount = retryCount.get();
        result.totalTestTime = totalTestTime;
        result.avgResponseTime = responseTimes.isEmpty() ? 0 : totalResponseTime.get() / responseTimes.size();
        result.minResponseTime = responseTimes.stream().mapToLong(Long::longValue).min().orElse(0);
        result.maxResponseTime = responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        result.throughput = calculateThroughput(successCount.get(), totalTestTime);
        result.responseTimes = responseTimes;

        return result;
    }

    @Transactional
    public void resetTestData() {
        log.info("Resetting test data...");
        jdbcTemplate.update("UPDATE ACCOUNT SET balance = 1000000.00, version = 0");
        jdbcTemplate.update("UPDATE SETTLEMENT_NOTES SET is_payed = false");
        jdbcTemplate.update("UPDATE EXPENSE SET settlement_completed = false");
    }

    private double calculateThroughput(int successCount, long totalTime) {
        if (totalTime == 0) return 0;
        return (double) successCount / (totalTime / 1000.0);
    }

    private void printResults(PerformanceResult pessimistic, PerformanceResult optimistic) {
        log.info("\n" + "=".repeat(80));
        log.info("Performance Test Results");
        log.info("=".repeat(80));

        log.info("\n[Pessimistic Lock - settle()]");
        printResult(pessimistic);

        log.info("\n[Optimistic Lock - settle2()]");
        printResult(optimistic);

        log.info("\n[Comparison Analysis]");
        compareResults(pessimistic, optimistic);
    }

    private void printResult(PerformanceResult result) {
        log.info("Total operations: {}", result.totalOperations);
        double successRate = (double) result.successCount / result.totalOperations * 100;
        log.info("Success: {} ({}%)", result.successCount, String.format("%.2f", successRate));
        double failureRate = (double) result.failureCount / result.totalOperations * 100;
        log.info("Failure: {} ({}%)", result.failureCount, String.format("%.2f", failureRate));
        log.info("Retry count: {}", result.retryCount);
        log.info("Total execution time: {} ms", result.totalTestTime);
        log.info("Average response time: {} ms", result.avgResponseTime);
        log.info("Min response time: {} ms", result.minResponseTime);
        log.info("Max response time: {} ms", result.maxResponseTime);
        log.info("Throughput (TPS): {}", String.format("%.2f", result.throughput));

        List<Long> sortedTimes = new ArrayList<>(result.responseTimes);
        sortedTimes.sort(Long::compareTo);
        if (!sortedTimes.isEmpty()) {
            log.info("50% (median): {} ms", sortedTimes.get(sortedTimes.size() / 2));
            log.info("95%: {} ms", sortedTimes.get((int) (sortedTimes.size() * 0.95)));
            log.info("99%: {} ms", sortedTimes.get((int) (sortedTimes.size() * 0.99)));
        }
    }

    private void compareResults(PerformanceResult pessimistic, PerformanceResult optimistic) {
        double throughputDiff = 0;
        if (pessimistic.throughput > 0) {
            throughputDiff = ((optimistic.throughput - pessimistic.throughput) / pessimistic.throughput) * 100;
        }

        double avgResponseDiff = 0;
        if (pessimistic.avgResponseTime > 0) {
            avgResponseDiff = ((double)(pessimistic.avgResponseTime - optimistic.avgResponseTime) / pessimistic.avgResponseTime) * 100;
        }

        log.info("Throughput difference: {}% {}",
                String.format("%.2f", throughputDiff),
                throughputDiff > 0 ? "(Optimistic is faster)" : "(Pessimistic is faster)");
        log.info("Avg response time difference: {}% {}",
                String.format("%.2f", avgResponseDiff),
                avgResponseDiff > 0 ? "(Optimistic is faster)" : "(Pessimistic is faster)");
        log.info("Retry count difference: {} (Pessimistic) vs {} (Optimistic)",
                pessimistic.retryCount, optimistic.retryCount);

        log.info("\n[Recommendations]");
        if (optimistic.retryCount > pessimistic.retryCount * 2) {
            log.info("- High conflict environment detected. Pessimistic lock is recommended.");
        } else if (throughputDiff > 20) {
            log.info("- Low conflict environment detected. Optimistic lock is recommended.");
        } else {
            log.info("- Performance difference is not significant. Choose based on system characteristics.");
        }
    }

    // PerformanceResult class without Lombok
    static class PerformanceResult {
        String lockType;
        int totalOperations;
        int successCount;
        int failureCount;
        int retryCount;
        long totalTestTime;
        long avgResponseTime;
        long minResponseTime;
        long maxResponseTime;
        double throughput;
        List<Long> responseTimes;
    }
}