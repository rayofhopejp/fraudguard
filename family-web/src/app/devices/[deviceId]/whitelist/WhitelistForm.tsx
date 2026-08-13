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

  // 編集中の1件。電話番号は変更させない(番号が変われば別の相手であり、登録し直すのが筋)。
  const [editing, setEditing] = useState<WhitelistEntry | null>(null);
  const [editName, setEditName] = useState("");
  const [editNote, setEditNote] = useState("");
  const [editEnabled, setEditEnabled] = useState(true);

  function startEdit(entry: WhitelistEntry) {
    setEditing(entry);
    setEditName(entry.displayName);
    setEditNote(entry.note ?? "");
    setEditEnabled(entry.enabled);
    setError(null);
  }

  async function handleUpdate() {
    if (!editing) return;
    setPending(true);
    setError(null);
    try {
      await api.updateWhitelistEntry(accessToken, deviceId, editing.entryId, {
        displayName: editName,
        note: editNote.trim() === "" ? null : editNote,
        enabled: editEnabled,
      });
      setEditing(null);
      router.refresh();
    } catch {
      setError("更新に失敗しました。");
    } finally {
      setPending(false);
    }
  }

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
            {editing?.entryId === entry.entryId ? (
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap", width: "100%", alignItems: "center" }}>
                <span style={{ minWidth: 140 }}>{entry.phoneNumber}</span>
                <input value={editName} onChange={(e) => setEditName(e.target.value)} placeholder="表示名" required />
                <input value={editNote} onChange={(e) => setEditNote(e.target.value)} placeholder="備考(任意)" />
                <label style={{ display: "flex", alignItems: "center", gap: 4 }}>
                  <input type="checkbox" checked={editEnabled} onChange={(e) => setEditEnabled(e.target.checked)} />
                  有効
                </label>
                <button disabled={pending || editName.trim() === ""} onClick={handleUpdate}>
                  保存
                </button>
                <button disabled={pending} onClick={() => setEditing(null)}>
                  やめる
                </button>
              </div>
            ) : (
              <>
                <span>
                  <strong>{entry.displayName}</strong> — {entry.phoneNumber}
                  {!entry.enabled && <span> (無効)</span>}
                  {entry.note && <span style={{ color: "#666" }}> ／ {entry.note}</span>}
                </span>
                <span style={{ display: "flex", gap: 8 }}>
                  <button disabled={pending} onClick={() => startEdit(entry)}>
                    編集
                  </button>
                  <button disabled={pending} onClick={() => handleDelete(entry.entryId)}>
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
