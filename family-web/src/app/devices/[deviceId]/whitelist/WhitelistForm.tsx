"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { api } from "@/lib/api";
import type { WhitelistEntry } from "@/lib/types";

/** requirements.md 6.2章: 家族アプリからホワイトリスト追加・編集を可能にする。 */
export function WhitelistForm({ deviceId, entries }: { deviceId: string; entries: WhitelistEntry[] }) {
  const { data: session } = useSession();
  const accessToken = session?.accessToken;
  const router = useRouter();

  const [phoneNumber, setPhoneNumber] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setPending(true);
    setError(null);
    try {
      // 番号のE.164正規化はサーバー側が行う(不正な番号は400で返る)。
      await api.addWhitelistEntry(accessToken, deviceId, { phoneNumber, displayName });
      setPhoneNumber("");
      setDisplayName("");
      router.refresh();
    } catch {
      setError("追加に失敗しました。電話番号の形式をご確認ください。");
    } finally {
      setPending(false);
    }
  }

  async function handleDelete(entryId: string) {
    setPending(true);
    setError(null);
    try {
      await api.deleteWhitelistEntry(accessToken, deviceId, entryId);
      router.refresh();
    } catch {
      setError("削除に失敗しました。");
    } finally {
      setPending(false);
    }
  }

  return (
    <div>
      <form onSubmit={handleSubmit} style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <input
          placeholder="電話番号"
          value={phoneNumber}
          onChange={(e) => setPhoneNumber(e.target.value)}
          required
        />
        <input
          placeholder="表示名(例: 病院)"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          required
        />
        <button type="submit" disabled={pending}>
          追加
        </button>
      </form>

      {error && <p style={{ color: "var(--color-critical)" }}>{error}</p>}

      <ul style={{ listStyle: "none", padding: 0, marginTop: 24 }}>
        {entries.map((entry) => (
          <li
            key={entry.entryId}
            style={{
              padding: "8px 0",
              borderBottom: "1px solid var(--color-border)",
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
            }}
          >
            <span>
              <strong>{entry.displayName}</strong> — {entry.phoneNumber}
              {!entry.enabled && <span> (無効)</span>}
            </span>
            <button disabled={pending} onClick={() => handleDelete(entry.entryId)}>
              削除
            </button>
          </li>
        ))}
        {entries.length === 0 && <li>登録された番号がありません。</li>}
      </ul>
    </div>
  );
}
