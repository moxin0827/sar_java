package com.example.javaparser.repository;

import com.example.javaparser.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findBySessionId(String sessionId);
}
