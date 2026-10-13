package com.loadsense.core.repository;
import com.loadsense.core.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    java.util.Optional<UserAccount> findByUsername(String username);
}
