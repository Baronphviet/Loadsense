package com.loadsense.core.repository;
import com.loadsense.core.entity.ExecutionStatus;
import com.loadsense.core.entity.TestExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {
    List<TestExecution> findTop100ByUserIdOrderByCreatedAtDesc(UUID userId);
    java.util.Optional<TestExecution> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByTestConfigId(UUID configId);
    boolean existsByTestConfigIdAndStatusIn(UUID configId, Collection<ExecutionStatus> statuses);
    List<TestExecution> findByTestConfigId(UUID configId);
    List<TestExecution> findByMockDataSetIdIn(Collection<UUID> datasetIds);
    List<TestExecution> findByUserIdAndStatusIn(UUID userId, Collection<ExecutionStatus> statuses);
    List<TestExecution> findByStatusIn(Collection<ExecutionStatus> statuses);
}
