-- requirements.md 34章: ワンタイムペアリングコード。

CREATE TABLE pairing_codes (
    code        VARCHAR(64) PRIMARY KEY,
    device_id   VARCHAR(36) NOT NULL REFERENCES monitored_devices(id),
    created_at  TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    used_at     TIMESTAMP
);
