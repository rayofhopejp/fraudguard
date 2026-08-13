-- requirements.md 10.3章: 家族が「これは家族の通話だ」とマークした通話。
-- LINE等のアプリ内通話は相手を電話番号で識別できず、ホワイトリストが使えないため、
-- 通話単位で「以降この通話については通知しない」と家族が指定できるようにする。

CREATE TABLE family_marked_calls (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    device_id   VARCHAR(36)  NOT NULL REFERENCES monitored_devices(id),
    call_id     VARCHAR(36)  NOT NULL,
    marked_at   TIMESTAMP    NOT NULL,
    -- 誰がマークしたか。Slackのリンクから操作した場合は家族ユーザーを特定できないためNULL許容。
    marked_by   VARCHAR(36)  REFERENCES family_users(id)
);

-- 同じ通話を二重にマークしても1件に保つ(Slackのリンクは何度でも押せるため)。
CREATE UNIQUE INDEX idx_family_marked_calls_unique ON family_marked_calls (device_id, call_id);
