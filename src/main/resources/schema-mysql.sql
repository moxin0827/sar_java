-- SAR JavaParser MySQL 初始化脚本（备用）
-- 正常情况下 Hibernate ddl-auto: update 会自动建表，此脚本仅作参考

CREATE DATABASE IF NOT EXISTS sarpro
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE sarpro;

-- 1. projects
CREATE TABLE IF NOT EXISTS projects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    source_path VARCHAR(1024),
    status VARCHAR(255),
    created_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. class_info
CREATE TABLE IF NOT EXISTS class_info (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    fully_qualified_name VARCHAR(512) NOT NULL,
    simple_name VARCHAR(255),
    package_name VARCHAR(255),
    class_type VARCHAR(255),
    annotations VARCHAR(2048),
    javadoc_comment TEXT,
    method_names TEXT,
    field_names TEXT,
    semantic_embedding MEDIUMTEXT,
    functional_summary VARCHAR(2048),
    PRIMARY KEY (id),
    INDEX idx_class_info_project_id (project_id),
    INDEX idx_class_info_project_fqn (project_id, fully_qualified_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. class_relations
CREATE TABLE IF NOT EXISTS class_relations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    source_class_name VARCHAR(512) NOT NULL,
    target_class_name VARCHAR(512) NOT NULL,
    relation_type VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_class_relations_project_id (project_id),
    INDEX idx_class_relations_project_type (project_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. function_knowledge
CREATE TABLE IF NOT EXISTS function_knowledge (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    function_name VARCHAR(255) NOT NULL,
    description VARCHAR(2048),
    related_terms VARCHAR(2048),
    parent_function_id BIGINT,
    related_class_names TEXT,
    is_leaf TINYINT(1),
    source VARCHAR(255),
    PRIMARY KEY (id),
    INDEX idx_fk_project_id (project_id),
    INDEX idx_fk_project_parent (project_id, parent_function_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. recovery_results
CREATE TABLE IF NOT EXISTS recovery_results (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    struct_weight DOUBLE,
    semantic_weight DOUBLE,
    threshold DOUBLE,
    total_components INT,
    total_classes INT,
    processing_time_ms BIGINT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_recovery_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. components
CREATE TABLE IF NOT EXISTS components (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    recovery_result_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    parent_component_id BIGINT,
    level INT,
    class_names TEXT,
    source VARCHAR(255),
    PRIMARY KEY (id),
    INDEX idx_components_project_id (project_id),
    INDEX idx_components_recovery_id (recovery_result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
