import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { WhitelistForm } from "./WhitelistForm";

/** requirements.md 6章: ホワイトリスト管理(サーバー側を正とし、端末へ同期される)。 */
export default async function WhitelistPage({ params }: { params: { deviceId: string } }) {
  const accessToken = await requireAccessToken();
  const entries = await api.listWhitelist(accessToken, params.deviceId).catch(() => []);

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <p>
        <a href={`/devices/${params.deviceId}`}>← 警告履歴</a>
      </p>
      <h1>ホワイトリスト</h1>

      <WhitelistForm deviceId={params.deviceId} entries={entries} />
    </main>
  );
}
