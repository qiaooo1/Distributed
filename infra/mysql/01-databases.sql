CREATE DATABASE scheduler_lab CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE report_lab CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'scheduler_app'@'%' IDENTIFIED BY 'scheduler_local';
CREATE USER 'report_app'@'%' IDENTIFIED BY 'report_local';
GRANT ALL PRIVILEGES ON scheduler_lab.* TO 'scheduler_app'@'%';
GRANT ALL PRIVILEGES ON report_lab.* TO 'report_app'@'%';
