"use client";

import { useState } from "react";
import { useSession } from "next-auth/react";
import { api } from "@/lib/api";

/**
 * requirements.md 34章: 新しい監視対象端末を登録し、ペアリングコードを発行する。
 *
 * これまでコードの発行はサーバー上の開発ツールでしかできず、
 * 家族が自分で端末を追加する手段が無かった。
 */
export function AddDeviceForm() {
  const { data: session } = useSession();
  const [deviceName, setDeviceName] = useState("");
  const [pairingCode, setPairingCode] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit() {
    setPending(true);
    setError(null);
    try {
      const result = await api.createPairingCode(session?.accessToken, deviceName.trim());
      setPairingCode(result.pairingCode);
      setDeviceName("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "登録に失敗しました");
    } finally {
      setPending(false);
    }
  }

  if (pairingCode) {
    return (
      <section style={{ border: "1px solid #ccc", borderRadius: 8, padding: 16, marginBottom: 24 }}>
        <p>見守る端末で、FraudGuardアプリを開いて次のコードを入力してください。</p>
        <p style={{ fontSize: 32, fontWeight: 600, letterSpacing: 4, margin: "12px 0" }}>{pairingCode}</p>
        <p style={{ color: "#666" }}>
          このコードは<strong>15分</strong>で使えなくなります。過ぎた場合はもう一度登録してください。
        </p>
        <button onClick={() => setPairingCode(null)}>閉じる</button>
      </section>
    );
  }

  return (
    <section style={{ marginBottom: 24 }}>
      <h2 style={{ fontSize: 18 }}>端末を追加する</h2>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <input
          value={deviceName}
          onChange={(e) => setDeviceName(e.target.value)}
          placeholder="端末の名前（例：母のスマホ）"
          style={{ flex: 1, minWidth: 200, padding: 8 }}
        />
        <button onClick={submit} disabled={pending || deviceName.trim().length === 0}>
          {pending ? "登録中…" : "ペアリングコードを発行"}
        </button>
      </div>
      {error && <p style={{ color: "#b71c1c" }}>{error}</p>}
    </section>
  );
}
