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
