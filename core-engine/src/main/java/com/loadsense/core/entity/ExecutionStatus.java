package com.loadsense.core.entity;
public enum ExecutionStatus {
    PENDING, RUNNING, COMPLETED, FAILED, STOPPED;
    public boolean terminal() { return this == COMPLETED || this == FAILED || this == STOPPED; }
}
