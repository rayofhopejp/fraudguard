"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { api } from "@/lib/api";
import type { DeviceMember } from "@/lib/types";

/**
 * requirements.md 16.2章: 1台の端末を複数の家族で見守る。
 *
 * 通知を受けた人がその場で動けないと意味がないため、追加された家族も
 * 遠隔切断とホワイトリスト編集ができる(サーバー側で権限を付与している)。
 */
export function MembersManager({ deviceId, members }: { deviceId: string; members: DeviceMember[] }) {
  const { data: session } = useSession();
  const myEmail = session?.user?.email;
  const iAmOwner = members.some((m) => m.isOwner && m.email === myEmail);
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function run(action: () => Promise<unknown>, successMessage: string) {
    setPending(true);
    setMessage(null);
    setError(null);
    try {
      await action();
      setMessage(successMessage);
      router.refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作に失敗しました");
    } finally {
      setPending(false);
    }
  }

  return (
    <section>
      <ul style={{ listStyle: "none", padding: 0 }}>
        {members.map((member) => (
          <li
            key={member.familyUserId}
            style={{ display: "flex", alignItems: "center", gap: 8, padding: "8px 0", borderBottom: "1px solid #eee" }}
          >
            <span style={{ flex: 1 }}>
              {member.displayName || member.email}
              {member.isOwner && <span style={{ color: "#666" }}>（登録者）</span>}
              <br />
              <span style={{ color: "#666", fontSize: 14 }}>{member.email}</span>
            </span>
            {/*
              他の家族を外せるのは端末を登録した人だけ。それ以外の人は自分だけ外せる。
              押せない操作のボタンを見せると、できることを取り違えさせる。
            */}
            {!member.isOwner && (iAmOwner || member.email === myEmail) && (
              <button
                disabled={pending}
                onClick={() =>
                  run(
                    () => api.removeDeviceMember(session?.accessToken, deviceId, member.familyUserId),
                    member.email === myEmail ? "見守りから抜けました" : "共有を解除しました",
                  )
                }
              >
                {member.email === myEmail ? "見守りから抜ける" : "共有をやめる"}
              </button>
            )}
          </li>
        ))}
      </ul>

      {!iAmOwner && (
        <p style={{ color: "#666", fontSize: 14 }}>
          他の家族を外せるのは、この端末を登録した人だけです。
        </p>
      )}

      <h2 style={{ fontSize: 18, marginTop: 24 }}>家族を追加する</h2>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <input
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="メールアドレス"
          type="email"
          style={{ flex: 1, minWidth: 220, padding: 8 }}
        />
        <button
          disabled={pending || email.trim().length === 0}
          onClick={() => run(() => api.addDeviceMember(session?.accessToken, deviceId, email.trim()), "追加しました")}
        >
          追加
        </button>
      </div>
      <p style={{ color: "#666", fontSize: 14 }}>
        追加する方には、先にこの画面へ一度ログインしてもらってください。
      </p>

      {message && <p style={{ color: "#2e7d32" }}>{message}</p>}
      {error && <p style={{ color: "#b71c1c" }}>{error}</p>}
    </section>
  );
}
