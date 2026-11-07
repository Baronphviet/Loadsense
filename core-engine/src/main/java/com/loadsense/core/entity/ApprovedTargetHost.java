package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="approved_target_hosts", uniqueConstraints=@UniqueConstraint(columnNames={"user_id","host"}))
public class ApprovedTargetHost {
    @Id public UUID id=UUID.randomUUID();
    @Column(name="user_id",nullable=false) public UUID userId;
    @Column(nullable=false,length=253) public String host;
}
