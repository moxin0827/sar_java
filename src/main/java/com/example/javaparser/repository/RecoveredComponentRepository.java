package com.example.javaparser.repository;

import com.example.javaparser.entity.RecoveredComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoveredComponentRepository extends JpaRepository<RecoveredComponent, Long> {
    List<RecoveredComponent> findByProjectId(Long projectId);
    List<RecoveredComponent> findByRecoveryResultId(Long recoveryResultId);
}
