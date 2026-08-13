import type { FraudGuardEvent, MonitoredDevice, WhitelistEntry } from "./types";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "";

/**
 * requirements.md 23章: サーバーREST APIのFamily側クライアント(family-auth = Cognito JWT)。
 * accessTokenはNextAuthのセッション(lib/auth.tsがCognitoのid_tokenを載せている)から渡す。
 */
async function request<T>(path: string, accessToken: string | undefined, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    throw new Error(`API request failed: ${response.status} ${path}`);
  }
  // 204 No Content(ホワイトリスト削除など)はJSONボディを持たない。
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  listDevices: (accessToken: string | undefined) =>
    request<MonitoredDevice[]>("/devices", accessToken),

  listEvents: (accessToken: string | undefined, deviceId: string) =>
    request<FraudGuardEvent[]>(`/devices/${deviceId}/events`, accessToken),

  acknowledgeEvent: (accessToken: string | undefined, eventId: string) =>
    request<{ eventId: string; acknowledged: boolean }>(`/events/${eventId}/acknowledge`, accessToken, {
      method: "POST",
    }),

  listWhitelist: (accessToken: string | undefined, deviceId: string) =>
    request<WhitelistEntry[]>(`/devices/${deviceId}/whitelist`, accessToken),

  addWhitelistEntry: (accessToken: string | undefined, deviceId: string, body: { phoneNumber: string; displayName: string; note?: string }) =>
    request<WhitelistEntry>(`/devices/${deviceId}/whitelist`, accessToken, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  deleteWhitelistEntry: (accessToken: string | undefined, deviceId: string, entryId: string) =>
    request<void>(`/devices/${deviceId}/whitelist/${entryId}`, accessToken, { method: "DELETE" }),

  // requirements.md 18章[v2]: ブラックリスト登録は「常にCRITICAL即時警告」の効果を持つ(自動拒否はしない)。
  addBlacklistEntry: (accessToken: string | undefined, deviceId: string, body: { phoneNumber: string; reason?: string }) =>
    request<{ entryId: string; phoneNumber: string }>(`/devices/${deviceId}/blacklist`, accessToken, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // requirements.md 8章: 遠隔通話切断
  disconnectCall: (accessToken: string | undefined, deviceId: string, callId: string) =>
    request<{ deviceId: string; callId: string }>(`/devices/${deviceId}/commands`, accessToken, {
      method: "POST",
      body: JSON.stringify({ callId, type: "DISCONNECT_CALL" }),
    }),
};
