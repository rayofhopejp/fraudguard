// requirements.md 22章, 15章: サーバー側(server/domain/model)と対になる型定義。

export type RiskLevel = "INFO" | "NOTICE" | "WARNING" | "CRITICAL";

export type EventType =
  | "CALL_INCOMING"
  | "CALL_OUTGOING"
  | "CALL_LONG_DURATION"
  | "CALL_BURST_FOREIGN"
  | "SMS_RECEIVED"
  | "APP_MESSAGING_INSTALLED"
  | "APP_REMOTE_CONTROL_INSTALLED"
  | "APP_LAUNCHED_AFTER_INSTALL"
  | "NOTIFICATION_OBSERVED"
  | "CORRELATED_RISK"
  | "DEVICE_HEALTH";

export interface EventMetadata {
  phoneNumber?: string;
  callId?: string;
  direction?: "INCOMING" | "OUTGOING";
  durationSeconds?: number;
  packageName?: string;
  appName?: string;
  messageBody?: string;
  sourceApp?: string;
  reason?: string;
  confidence?: number;
}

export interface FraudGuardEvent {
  eventId: string;
  deviceId: string;
  type: EventType;
  riskLevel: RiskLevel;
  title: string;
  detail: string;
  timestamp: string;
  metadata: EventMetadata;
  acknowledged: boolean;
  createdAt: string;
}

export interface MonitoredDevice {
  deviceId: string;
  name: string;
  createdAt: string;
  lastHeartbeatAt: string | null;
  /** ペアリングを無効化した日時。設定されていればこの端末はもう何も送ってこない。 */
  revokedAt: string | null;
}

export interface WhitelistEntry {
  entryId: string;
  deviceId: string;
  phoneNumber: string;
  displayName: string;
  enabled: boolean;
  note?: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

/** requirements.md 16.2章: 端末を共有している家族。 */
export type DeviceMember = {
  familyUserId: string;
  displayName: string;
  email: string;
  isOwner: boolean;
};

/** requirements.md 18章[v2]: ブラックリスト。着信を常にCRITICALとして即時警告する(自動拒否はしない)。 */
export type BlacklistEntry = {
  entryId: string;
  phoneNumber: string;
  reason: string | null;
  createdAt: string;
};
