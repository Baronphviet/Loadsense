package com.loadsense.core.repository;
import com.loadsense.core.entity.TestMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
public interface TestMetricRepository extends JpaRepository<TestMetric, UUID> {
    List<TestMetric> findByExecutionIdOrderBySampleTimeAsc(UUID id);
    List<TestMetric> findByExecutionIdAndElapsedSecondsGreaterThanOrderBySampleTimeAsc(UUID id, double elapsedSeconds);
    void deleteByExecutionId(UUID id);
    void deleteByExecutionIdIn(Collection<UUID> ids);
}
