"use client";

import { signIn } from "next-auth/react";

/** requirements.md 36.2章: Cognito Hosted UI経由のサインイン。 */
export default function LoginPage() {
  return (
    <main style={{ display: "flex", flexDirection: "column", alignItems: "center", marginTop: "20vh", gap: 16 }}>
      <h1>FraudGuard Family</h1>
      <button onClick={() => signIn("cognito")}>ログイン</button>
    </main>
  );
}
