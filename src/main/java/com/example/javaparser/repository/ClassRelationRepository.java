package com.example.javaparser.repository;

import com.example.javaparser.entity.ClassRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClassRelationRepository extends JpaRepository<ClassRelation, Long> {
    List<ClassRelation> findByProjectId(Long projectId);
    List<ClassRelation> findByProjectIdAndRelationType(Long projectId, String relationType);
}
