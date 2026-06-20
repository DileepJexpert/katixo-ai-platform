package com.katixo.ai.commons.dto;

/**
 * Lifecycle status shared vocabulary for GPU/inference work. This is the canonical enum for the
 * platform; apps may map their own persistence enums to/from it. (image-generator keeps its own JPA
 * {@code JobStatus} for the database column; this type is the framework-neutral contract.)
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED
}
