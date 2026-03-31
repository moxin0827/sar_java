package com.example.javaparser.repository;

import com.example.javaparser.entity.FunctionKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FunctionKnowledgeRepository extends JpaRepository<FunctionKnowledge, Long> {
    List<FunctionKnowledge> findByProjectId(Long projectId);
    List<FunctionKnowledge> findByProjectIdAndParentFunctionIdIsNull(Long projectId);
}
