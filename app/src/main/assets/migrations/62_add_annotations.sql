-- 注意：本文件不再直接执行——62 号迁移改由 Migration61To62（core/db/IdempotentMigrations.kt）幂等执行，
-- 以兼容曾装过旧编号 60/61 的中间开发版设备（需补官方 60/61 两列）。本文件保留作 SQL 审计与编号校验。
-- 截图标注账本：截图落盘在工作区 .aicode/annotations/，本表只索引与状态机
CREATE TABLE IF NOT EXISTS `annotations` (
    `id` TEXT NOT NULL,
    `type` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `imagePath` TEXT NOT NULL,
    `marksJson` TEXT NOT NULL,
    `note` TEXT NOT NULL,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    PRIMARY KEY(`id`)
);
