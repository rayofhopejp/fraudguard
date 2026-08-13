"use client";

import { signIn, signOut, useSession } from "next-auth/react";

/** requirements.md 36.2章: Cognito Hosted UI経由のサインイン。 */
export default function LoginPage() {
  const { data: session, status } = useSession();

  return (
    <main style={{ display: "flex", flexDirection: "column", alignItems: "center", marginTop: "20vh", gap: 16 }}>
      <h1>FraudGuard Family</h1>

      {status === "loading" && <p>読み込み中...</p>}

      {status !== "loading" && session?.accessToken && (
        <>
          <p>{session.user?.email ?? "ログイン済み"}</p>
          <a href="/devices">
            <button>監視端末を見る</button>
          </a>
          <button onClick={() => signOut()}>ログアウト</button>
        </>
      )}

      {status !== "loading" && !session?.accessToken && (
        <>
          {/* トークン更新に失敗した場合は再ログインが必要な理由を明示する(黙って弾かれるのを避ける)。 */}
          {session?.error === "RefreshTokenError" && <p>セッションの有効期限が切れました。再度ログインしてください。</p>}
          <button onClick={() => signIn("cognito", { callbackUrl: "/devices" })}>ログイン</button>
        </>
      )}
    </main>
  );
}
