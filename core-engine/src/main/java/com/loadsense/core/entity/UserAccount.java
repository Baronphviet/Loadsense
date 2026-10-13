package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="users")
public class UserAccount {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false, unique=true) public String username;
    @Column(name="password_hash", nullable=false) public String passwordHash;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
}
