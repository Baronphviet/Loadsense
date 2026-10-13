package com.loadsense.core.repository;
import com.loadsense.core.entity.TestMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface TestMetricRepository extends JpaRepository<TestMetric, UUID> {
    java.util.List<TestMetric> findByExecutionIdOrderBySampleTimeAsc(UUID id);
}
