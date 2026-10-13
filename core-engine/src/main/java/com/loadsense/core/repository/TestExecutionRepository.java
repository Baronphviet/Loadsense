package com.loadsense.core.repository;
import com.loadsense.core.entity.TestExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {
    java.util.List<TestExecution> findTop100ByUserIdOrderByCreatedAtDesc(UUID userId);
    java.util.Optional<TestExecution> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByTestConfigId(UUID configId);
    java.util.List<TestExecution> findByStatusIn(java.util.Collection<com.loadsense.core.entity.ExecutionStatus> statuses);
}
