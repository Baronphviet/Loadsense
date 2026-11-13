package com.loadsense.core.repository;

import com.loadsense.core.entity.TestSuite;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestSuiteRepository extends JpaRepository<TestSuite, UUID> {
    List<TestSuite> findByUserIdOrderByUpdatedAtDesc(UUID userId);
    Optional<TestSuite> findByIdAndUserId(UUID id, UUID userId);
}
