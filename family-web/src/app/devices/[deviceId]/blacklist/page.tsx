import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { BlacklistForm } from "./BlacklistForm";

/** requirements.md 18章[v2]: ブラックリスト管理。 */
export default async function BlacklistPage({ params }: { params: { deviceId: string } }) {
  const accessToken = await requireAccessToken();

  // 取得失敗を空配列にすると「登録が無い」と区別がつかない。失敗は失敗として出す。
  let entries: Awaited<ReturnType<typeof api.listBlacklist>> | null = null;
  let error: string | null = null;
  try {
    entries = await api.listBlacklist(accessToken, params.deviceId);
  } catch (e) {
    error = e instanceof Error ? e.message : "一覧を取得できませんでした";
  }

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <p>
        <a href={`/devices/${params.deviceId}`}>← 警告履歴</a>
      </p>
      <h1>ブラックリスト</h1>
      {error && <p style={{ color: "var(--color-critical)" }}>一覧を取得できませんでした: {error}</p>}
      {entries && <BlacklistForm deviceId={params.deviceId} entries={entries} />}
    </main>
  );
}
