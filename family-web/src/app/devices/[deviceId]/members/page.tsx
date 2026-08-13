import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { MembersManager } from "./MembersManager";

/** requirements.md 16.2章: この端末を見守っている家族の管理。 */
export default async function MembersPage({ params }: { params: { deviceId: string } }) {
  const accessToken = await requireAccessToken();
  const members = await api.listDeviceMembers(accessToken, params.deviceId).catch(() => []);

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <a href={`/devices/${params.deviceId}`}>← 端末に戻る</a>
      <h1>見守っている家族</h1>
      <MembersManager deviceId={params.deviceId} members={members} />
    </main>
  );
}
