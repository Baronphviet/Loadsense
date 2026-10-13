package com.loadsense.core.repository;
import com.loadsense.core.entity.MockDataSet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface MockDataSetRepository extends JpaRepository<MockDataSet, UUID> {
    java.util.List<MockDataSet> findByTestConfigIdOrderByCreatedAtDesc(UUID configId);
}
