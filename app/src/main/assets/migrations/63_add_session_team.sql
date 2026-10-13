-- 专家团·编队：会话级记录召唤的编队名（对应 teams/<name>.md），NULL 表示未使用编队
ALTER TABLE chat_sessions ADD COLUMN teamName TEXT;
