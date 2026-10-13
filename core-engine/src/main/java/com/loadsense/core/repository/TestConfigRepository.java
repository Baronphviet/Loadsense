package com.loadsense.core.repository;
import com.loadsense.core.entity.TestConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface TestConfigRepository extends JpaRepository<TestConfig, UUID> {
    java.util.List<TestConfig> findByUserIdOrderByUpdatedAtDesc(UUID userId);
    java.util.Optional<TestConfig> findByIdAndUserId(UUID id, UUID userId);
}
