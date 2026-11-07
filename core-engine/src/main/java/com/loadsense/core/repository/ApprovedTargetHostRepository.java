package com.loadsense.core.repository;

import com.loadsense.core.entity.ApprovedTargetHost;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ApprovedTargetHostRepository extends JpaRepository<ApprovedTargetHost,UUID> {
    boolean existsByUserIdAndHost(UUID userId,String host);
    List<ApprovedTargetHost> findByUserId(UUID userId);
}
