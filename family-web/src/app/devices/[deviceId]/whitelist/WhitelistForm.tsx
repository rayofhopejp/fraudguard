"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";

/** requirements.md 6.2章: 家族アプリからホワイトリスト追加・編集を可能にする。 */
export function WhitelistForm({ deviceId }: { deviceId: string }) {
  const router = useRouter();
  const [phoneNumber, setPhoneNumber] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [pending, setPending] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setPending(true);
    try {
      // TODO: セッションのaccessTokenを渡す。番号のE.164正規化はサーバー側で行う想定。
      await api.addWhitelistEntry(undefined, deviceId, { phoneNumber, displayName });
      setPhoneNumber("");
      setDisplayName("");
      router.refresh();
    } finally {
      setPending(false);
    }
  }

  return (
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
  );
}
