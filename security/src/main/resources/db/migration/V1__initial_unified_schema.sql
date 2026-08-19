-- Một database dùng chung: bảng users + user_permissions (security), Students (qlsv), Teachers (qlgv).

CREATE TABLE users (
    user_id INT NOT NULL AUTO_INCREMENT,
    type VARCHAR(50) NULL,
    username VARCHAR(255) NULL,
    password VARCHAR(255) NULL,
    first_name VARCHAR(255) NULL,
    last_name VARCHAR(255) NULL,
    gender VARCHAR(50) NULL,
    birth DATE NULL,
    phone_number VARCHAR(255) NULL,
    email VARCHAR(255) NULL,
    role VARCHAR(50) NULL,
    status VARCHAR(50) NULL,
    version BIGINT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_permissions (
    user_id INT NOT NULL,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, permission),
    CONSTRAINT fk_user_permissions_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Students (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    score DOUBLE NULL,
    `class` VARCHAR(255) NULL,
    major VARCHAR(255) NULL,
    graduate TINYINT(1) NULL,
    version BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_students_user_id (user_id),
    CONSTRAINT fk_students_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Teachers (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    class_managing VARCHAR(255) NULL,
    department VARCHAR(255) NULL,
    version BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_teachers_user_id (user_id),
    CONSTRAINT fk_teachers_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
