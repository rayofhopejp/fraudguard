-- requirements.md 20.2章のエンティティに対応する初期スキーマ。

CREATE TABLE family_users (
    id              VARCHAR(36) PRIMARY KEY,
    cognito_sub     VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(100) NOT NULL,
    email           VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMP NOT NULL
);

CREATE TABLE monitored_devices (
    id                      VARCHAR(36) PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    owner_family_user_id    VARCHAR(36) NOT NULL REFERENCES family_users(id),
    created_at              TIMESTAMP NOT NULL
);

CREATE TABLE device_members (
    device_id           VARCHAR(36) NOT NULL REFERENCES monitored_devices(id),
    family_user_id       VARCHAR(36) NOT NULL REFERENCES family_users(id),
    min_risk_level       VARCHAR(20) NOT NULL,
    can_disconnect_call  BOOLEAN NOT NULL,
    can_edit_whitelist   BOOLEAN NOT NULL,
    created_at           TIMESTAMP NOT NULL,
    PRIMARY KEY (device_id, family_user_id)
);

CREATE TABLE push_devices (
    id              VARCHAR(36) PRIMARY KEY,
    family_user_id  VARCHAR(36) NOT NULL REFERENCES family_users(id),
    fcm_token       VARCHAR(512) NOT NULL,
    platform        VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE TABLE events (
    id              VARCHAR(36) PRIMARY KEY,
    device_id       VARCHAR(36) NOT NULL REFERENCES monitored_devices(id),
    type            VARCHAR(40) NOT NULL,
    risk_level      VARCHAR(20) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    detail          VARCHAR(2000) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    metadata_json   TEXT NOT NULL,
    acknowledged    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL
);
CREATE INDEX idx_events_device_id ON events(device_id);
CREATE INDEX idx_events_timestamp ON events(event_timestamp);

CREATE TABLE whitelist (
    id              VARCHAR(36) PRIMARY KEY,
    device_id       VARCHAR(36) NOT NULL REFERENCES monitored_devices(id),
    phone_number    VARCHAR(32) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    note            VARCHAR(500),
    created_by      VARCHAR(36) NOT NULL REFERENCES family_users(id),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX idx_whitelist_device_phone ON whitelist(device_id, phone_number);

CREATE TABLE blacklist (
    id              VARCHAR(36) PRIMARY KEY,
    device_id       VARCHAR(36) NOT NULL REFERENCES monitored_devices(id),
    phone_number    VARCHAR(32) NOT NULL,
    reason          VARCHAR(500),
    created_by      VARCHAR(36) NOT NULL REFERENCES family_users(id),
    created_at      TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX idx_blacklist_device_phone ON blacklist(device_id, phone_number);

CREATE TABLE remote_commands (
    id                          VARCHAR(36) PRIMARY KEY,
    device_id                   VARCHAR(36) NOT NULL REFERENCES monitored_devices(id),
    call_id                     VARCHAR(64) NOT NULL,
    type                        VARCHAR(40) NOT NULL,
    issued_by_family_user_id    VARCHAR(36) NOT NULL REFERENCES family_users(id),
    issued_at                   TIMESTAMP NOT NULL,
    expires_at                  TIMESTAMP NOT NULL,
    nonce                       VARCHAR(64) NOT NULL,
    signature                   VARCHAR(512) NOT NULL,
    delivered                   BOOLEAN NOT NULL DEFAULT FALSE,
    executed_success            BOOLEAN,
    executed_failure_reason     VARCHAR(500),
    executed_at                 TIMESTAMP
);
CREATE INDEX idx_remote_commands_device_id ON remote_commands(device_id, delivered);

CREATE TABLE acknowledgements (
    id                  VARCHAR(36) PRIMARY KEY,
    event_id            VARCHAR(36) NOT NULL REFERENCES events(id),
    family_user_id      VARCHAR(36) NOT NULL REFERENCES family_users(id),
    acknowledged_at     TIMESTAMP NOT NULL
);

CREATE TABLE audit_logs (
    id                          VARCHAR(36) PRIMARY KEY,
    actor_family_user_id        VARCHAR(36),
    actor_device_id             VARCHAR(36),
    action                      VARCHAR(100) NOT NULL,
    target_type                 VARCHAR(50),
    target_id                   VARCHAR(64),
    result                      VARCHAR(20) NOT NULL,
    detail                      VARCHAR(1000),
    created_at                  TIMESTAMP NOT NULL
);

CREATE TABLE device_heartbeats (
    id                              VARCHAR(36) PRIMARY KEY,
    device_id                       VARCHAR(36) NOT NULL REFERENCES monitored_devices(id),
    received_at                     TIMESTAMP NOT NULL,
    notification_listener_enabled   BOOLEAN NOT NULL,
    role_dialer_held                BOOLEAN NOT NULL,
    app_version                     VARCHAR(30) NOT NULL
);
CREATE INDEX idx_device_heartbeats_device_id ON device_heartbeats(device_id, received_at);

CREATE TABLE device_pairings (
    device_id           VARCHAR(36) PRIMARY KEY REFERENCES monitored_devices(id),
    api_key_hash        VARCHAR(128) NOT NULL,
    server_public_key   VARCHAR(128) NOT NULL,
    paired_at           TIMESTAMP NOT NULL,
    revoked_at           TIMESTAMP
);
