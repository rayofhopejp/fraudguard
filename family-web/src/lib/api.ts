import type { BlacklistEntry, DeviceMember, FraudGuardEvent, MonitoredDevice, WhitelistEntry } from "./types";

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

  // 本文の有無はステータスから決め打ちできない。204だけでなく、201や200でも
  // 本文を返さないエンドポイントがある(共有の追加・解除など)。
  // 空の応答をJSONとして読もうとすると "Unexpected end of JSON input" になり、
  // 成功しているのに失敗したように見える。先にテキストとして受け取ってから判断する。
  const text = await response.text();

  if (!response.ok) {
    // サーバーが理由を返している場合はそれを使う。「その方はまだログインしていません」のように、
    // 次に何をすればよいかが分かるメッセージを画面に出したいため。
    let message: string | undefined;
    try {
      message = (JSON.parse(text) as { message?: string })?.message;
    } catch {
      message = undefined;
    }
    throw new Error(message ?? `API request failed: ${response.status} ${path}`);
  }

  return (text ? (JSON.parse(text) as T) : (undefined as T));
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

  updateWhitelistEntry: (
    accessToken: string | undefined,
    deviceId: string,
    entryId: string,
    body: { displayName: string; note?: string | null; enabled: boolean },
  ) =>
    request<WhitelistEntry>(`/devices/${deviceId}/whitelist/${entryId}`, accessToken, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),

  deleteWhitelistEntry: (accessToken: string | undefined, deviceId: string, entryId: string) =>
    request<void>(`/devices/${deviceId}/whitelist/${entryId}`, accessToken, { method: "DELETE" }),

  listBlacklist: (accessToken: string | undefined, deviceId: string) =>
    request<BlacklistEntry[]>(`/devices/${deviceId}/blacklist`, accessToken),

  updateBlacklistEntry: (
    accessToken: string | undefined,
    deviceId: string,
    entryId: string,
    body: { reason?: string | null },
  ) =>
    request<BlacklistEntry>(`/devices/${deviceId}/blacklist/${entryId}`, accessToken, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),

  deleteBlacklistEntry: (accessToken: string | undefined, deviceId: string, entryId: string) =>
    request<void>(`/devices/${deviceId}/blacklist/${entryId}`, accessToken, { method: "DELETE" }),

  // requirements.md 18章[v2]: ブラックリスト登録は「常にCRITICAL即時警告」の効果を持つ(自動拒否はしない)。
  addBlacklistEntry: (accessToken: string | undefined, deviceId: string, body: { phoneNumber: string; reason?: string }) =>
    request<BlacklistEntry>(`/devices/${deviceId}/blacklist`, accessToken, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // requirements.md 34章: 端末の枠を作り、ペアリングコードを発行する
  createPairingCode: (accessToken: string | undefined, deviceName: string) =>
    request<{ deviceId: string; pairingCode: string }>("/devices/pairing-codes", accessToken, {
      method: "POST",
      body: JSON.stringify({ deviceName }),
    }),

  // requirements.md 16.2章: 端末を共有している家族
  listDeviceMembers: (accessToken: string | undefined, deviceId: string) =>
    request<DeviceMember[]>(`/devices/${deviceId}/members`, accessToken),

  addDeviceMember: (accessToken: string | undefined, deviceId: string, email: string) =>
    request<void>(`/devices/${deviceId}/members`, accessToken, {
      method: "POST",
      body: JSON.stringify({ email }),
    }),

  removeDeviceMember: (accessToken: string | undefined, deviceId: string, familyUserId: string) =>
    request<void>(`/devices/${deviceId}/members/${familyUserId}`, accessToken, { method: "DELETE" }),

  // requirements.md 8章: 遠隔通話切断
  disconnectCall: (accessToken: string | undefined, deviceId: string, callId: string) =>
    request<{ deviceId: string; callId: string }>(`/devices/${deviceId}/commands`, accessToken, {
      method: "POST",
      body: JSON.stringify({ callId, type: "DISCONNECT_CALL" }),
    }),
};
