-- requirements.md 8章[v2]: 遠隔コマンドのFCM即時送信先。

ALTER TABLE monitored_devices ADD COLUMN fcm_token VARCHAR(512);
