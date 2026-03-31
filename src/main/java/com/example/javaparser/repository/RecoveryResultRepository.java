package com.example.javaparser.repository;

import com.example.javaparser.entity.RecoveryResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryResultRepository extends JpaRepository<RecoveryResult, Long> {
    List<RecoveryResult> findByProjectId(Long projectId);
    Optional<RecoveryResult> findTopByProjectIdOrderByCreatedAtDesc(Long projectId);
}
