package com.legalai.agent.repository;

import com.legalai.agent.entity.AuditLog;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends CrudRepository<AuditLog, Long> {

    /**
     * Finds all audit logs for a specific user
     */
    List<AuditLog> findByUser(String user);

    /**
     * Finds audit logs by action type
     */
    List<AuditLog> findByAction(String action);

    /**
     * Finds audit logs within a date range
     */
    List<AuditLog> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Finds audit logs for a user within a date range
     */
    List<AuditLog> findByUserAndTimestampBetween(String user, LocalDateTime start, LocalDateTime end);

    /**
     * Finds recent audit logs ordered by timestamp descending
     */
    List<AuditLog> findTop100ByOrderByTimestampDesc();
}

