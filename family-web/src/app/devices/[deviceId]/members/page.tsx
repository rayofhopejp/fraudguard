import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { MembersManager } from "./MembersManager";

/** requirements.md 16.2章: この端末を見守っている家族の管理。 */
export default async function MembersPage({ params }: { params: { deviceId: string } }) {
  const accessToken = await requireAccessToken();
  // 取得に失敗したときに空配列を返すと「共有している家族がいない」と読めてしまい、
  // 実際にはAPIが壊れている場合と区別がつかない(シリアライズ漏れで400を返していた際、
  // 画面には何のエラーも出ず、ただ一覧が空に見えた)。失敗は失敗として出す。
  let members: Awaited<ReturnType<typeof api.listDeviceMembers>> | null = null;
  let error: string | null = null;
  try {
    members = await api.listDeviceMembers(accessToken, params.deviceId);
  } catch (e) {
    error = e instanceof Error ? e.message : "一覧を取得できませんでした";
  }

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <a href={`/devices/${params.deviceId}`}>← 端末に戻る</a>
      <h1>見守っている家族</h1>
      {error && <p style={{ color: "#b71c1c" }}>一覧を取得できませんでした: {error}</p>}
      {members && <MembersManager deviceId={params.deviceId} members={members} />}
    </main>
  );
}
