package com.kw.knowone.common.schedule;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kw.knowone.common.idempotency.IdempotencyService;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.idempotency.MutationResponse;

@Service
public class ScheduleMutationService {
    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;

    public ScheduleMutationService(JdbcTemplate jdbcTemplate, IdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public IdempotentResult execute(UUID userId, String operation, String idempotencyKey, Object request,
            Runnable authorizationCheck, Supplier<MutationResponse> mutation) {
        jdbcTemplate.queryForList("SELECT id FROM schedule_guard WHERE id = 1 FOR UPDATE");
        authorizationCheck.run();
        return idempotencyService.executeLocked(userId, operation, idempotencyKey, request, mutation);
    }
}
