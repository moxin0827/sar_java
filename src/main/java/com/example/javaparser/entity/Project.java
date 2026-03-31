package com.example.javaparser.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sessionId;

    private String name;

    @Column(length = 1024)
    private String sourcePath;

    @Column(length = 1024)
    private String outputPath;  // XMI 输出目录路径

    private String status;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = "CREATED";
    }
}
