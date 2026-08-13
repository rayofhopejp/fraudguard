import { api } from "@/lib/api";
import { WhitelistForm } from "./WhitelistForm";

/** requirements.md 6章: ホワイトリスト管理(サーバー側を正とし、端末へ同期される)。 */
export default async function WhitelistPage({ params }: { params: { deviceId: string } }) {
  const entries = await api.listWhitelist(undefined, params.deviceId).catch(() => []);

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <p>
        <a href={`/devices/${params.deviceId}`}>← 警告履歴</a>
      </p>
      <h1>ホワイトリスト</h1>

      <WhitelistForm deviceId={params.deviceId} />

      <ul style={{ listStyle: "none", padding: 0, marginTop: 24 }}>
        {entries.map((entry) => (
          <li key={entry.entryId} style={{ padding: "8px 0", borderBottom: "1px solid var(--color-border)" }}>
            <strong>{entry.displayName}</strong> — {entry.phoneNumber}
            {!entry.enabled && <span> (無効)</span>}
          </li>
        ))}
        {entries.length === 0 && <li>登録された番号がありません。</li>}
      </ul>
    </main>
  );
}
