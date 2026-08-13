"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { api } from "@/lib/api";
import type { BlacklistEntry } from "@/lib/types";

/**
 * requirements.md 18章[v2]: ブラックリスト管理。
 *
 * 登録しても自動で着信を拒否はしない。効果は「その番号からの着信を常にCRITICALとして即時警告する」こと。
 * 拒否まで行うと、本当に必要な連絡(病院・警察など)を取り逃す危険があるため。
 */
export function BlacklistForm({ deviceId, entries }: { deviceId: string; entries: BlacklistEntry[] }) {
  const { data: session } = useSession();
  const accessToken = session?.accessToken;
  const router = useRouter();

  const [phoneNumber, setPhoneNumber] = useState("");
  const [reason, setReason] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editReason, setEditReason] = useState("");

  async function run(action: () => Promise<unknown>, failureMessage: string) {
    setPending(true);
    setError(null);
    try {
      await action();
      router.refresh();
      return true;
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : failureMessage);
      return false;
    } finally {
      setPending(false);
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // 番号のE.164正規化はサーバー側が行う(不正な番号は400で返る)。
    const ok = await run(
      () => api.addBlacklistEntry(accessToken, deviceId, { phoneNumber, reason: reason || undefined }),
      "追加に失敗しました。電話番号の形式をご確認ください。",
    );
    if (ok) {
      setPhoneNumber("");
      setReason("");
    }
  }

  return (
    <div>
      <p style={{ color: "#666" }}>
        登録した番号からの着信は、<strong>常に最も高い危険度で通知</strong>されます。
        着信そのものを拒否はしません（本当に必要な連絡を取り逃さないため）。
      </p>

      <form onSubmit={handleSubmit} style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <input
          placeholder="電話番号"
          value={phoneNumber}
          onChange={(e) => setPhoneNumber(e.target.value)}
          required
        />
        <input placeholder="理由(任意)" value={reason} onChange={(e) => setReason(e.target.value)} />
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
            {editingId === entry.entryId ? (
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap", width: "100%", alignItems: "center" }}>
                {/* 番号は変更させない。番号が変われば別の相手であり、登録し直すのが筋。 */}
                <span style={{ minWidth: 140 }}>{entry.phoneNumber}</span>
                <input value={editReason} onChange={(e) => setEditReason(e.target.value)} placeholder="理由(任意)" />
                <button
                  disabled={pending}
                  onClick={async () => {
                    const ok = await run(
                      () =>
                        api.updateBlacklistEntry(accessToken, deviceId, entry.entryId, {
                          reason: editReason.trim() === "" ? null : editReason,
                        }),
                      "更新に失敗しました。",
                    );
                    if (ok) setEditingId(null);
                  }}
                >
                  保存
                </button>
                <button disabled={pending} onClick={() => setEditingId(null)}>
                  やめる
                </button>
              </div>
            ) : (
              <>
                <span>
                  <strong>{entry.phoneNumber}</strong>
                  {entry.reason && <span style={{ color: "#666" }}> ／ {entry.reason}</span>}
                </span>
                <span style={{ display: "flex", gap: 8 }}>
                  <button
                    disabled={pending}
                    onClick={() => {
                      setEditingId(entry.entryId);
                      setEditReason(entry.reason ?? "");
                      setError(null);
                    }}
                  >
                    編集
                  </button>
                  <button
                    disabled={pending}
                    onClick={() => run(() => api.deleteBlacklistEntry(accessToken, deviceId, entry.entryId), "削除に失敗しました。")}
                  >
                    削除
                  </button>
                </span>
              </>
            )}
          </li>
        ))}
        {entries.length === 0 && <li>登録された番号がありません。</li>}
      </ul>
    </div>
  );
}
