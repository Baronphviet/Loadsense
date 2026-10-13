package com.loadsense.core.repository;
import com.loadsense.core.entity.TestReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface TestReportRepository extends JpaRepository<TestReport, UUID> {
    java.util.Optional<TestReport> findByExecutionId(UUID id);
}
