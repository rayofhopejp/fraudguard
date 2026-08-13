"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { api } from "@/lib/api";

/**
 * requirements.md 25章: ペアリングの無効化。
 *
 * 端末を紛失したときに、その端末からの送信を即座に止めるための操作。
 * 無効化するとAPIキーが一切通らなくなるが、これまでの履歴は残る。
 *
 * 元に戻す操作は用意していない。戻したい場合は新しくペアリングし直す
 * (紛失した端末が持っている古いキーを再び有効にできてしまうと、無効化の意味がなくなる)。
 */
export function RevokeDeviceButton({ deviceId, deviceName }: { deviceId: string; deviceName: string }) {
  const { data: session } = useSession();
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!confirming) {
    return (
      <button onClick={() => setConfirming(true)} style={{ marginTop: 24 }}>
        この端末の見守りを停止する
      </button>
    );
  }

  return (
    <div style={{ marginTop: 24, border: "1px solid var(--color-border)", borderRadius: 8, padding: 16 }}>
      <p>
        <strong>{deviceName}</strong> の見守りを停止します。
      </p>
      <ul style={{ paddingLeft: 20 }}>
        <li>この端末からは、通話もSMSも届かなくなります</li>
        <li>これまでの履歴は残ります</li>
        <li>
          <strong>元に戻せません。</strong>また見守るには、もう一度ペアリングし直してください
        </li>
      </ul>
      {error && <p style={{ color: "var(--color-critical)" }}>{error}</p>}
      <div style={{ display: "flex", gap: 8 }}>
        <button
          disabled={pending}
          onClick={async () => {
            setPending(true);
            setError(null);
            try {
              await api.revokeDevice(session?.accessToken, deviceId);
              setConfirming(false);
              router.refresh();
            } catch (e) {
              setError(e instanceof Error ? e.message : "停止できませんでした");
            } finally {
              setPending(false);
            }
          }}
        >
          {pending ? "停止中…" : "停止する"}
        </button>
        <button disabled={pending} onClick={() => setConfirming(false)}>
          やめる
        </button>
      </div>
    </div>
  );
}
