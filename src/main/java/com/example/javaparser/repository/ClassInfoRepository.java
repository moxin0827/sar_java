package com.example.javaparser.repository;

import com.example.javaparser.entity.ClassInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassInfoRepository extends JpaRepository<ClassInfo, Long> {
    List<ClassInfo> findByProjectId(Long projectId);
    Optional<ClassInfo> findByProjectIdAndFullyQualifiedName(Long projectId, String fullyQualifiedName);
    List<ClassInfo> findByProjectIdAndSemanticEmbeddingIsNull(Long projectId);
}
