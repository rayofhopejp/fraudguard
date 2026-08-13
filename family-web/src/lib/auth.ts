import type { NextAuthOptions } from "next-auth";
import CognitoProvider from "next-auth/providers/cognito";

/**
 * requirements.md 36.2章: 家族ユーザー認証はAmazon Cognito User Pool。
 *
 * サーバーAPI(family-auth)はCognitoのJWTを検証するため、ここで取得したid_tokenを
 * セッションへ載せ、lib/api.ts がAuthorizationヘッダに使う。
 *
 * TODO: requirements.md 16.3章「家族ごとの通知権限設定」をCognitoのcustom attributeか
 *       サーバー側device_membersテーブルのどちらで持つか設計を詰める
 *       (現状はサーバー側のdevice_membersが正)。
 */
export const authOptions: NextAuthOptions = {
  providers: [
    CognitoProvider({
      clientId: process.env.COGNITO_CLIENT_ID ?? "",
      clientSecret: process.env.COGNITO_CLIENT_SECRET ?? "",
      issuer: process.env.COGNITO_ISSUER,
      // スコープを明示する。既定ではopenidのみが要求され、id_tokenにemailクレームが載らない。
      // サーバー側(FamilyUserRepository.resolveOrCreate)はsubとemailで家族ユーザーを作るため、
      // emailが無いと家族の識別ができない。
      authorization: { params: { scope: "openid email profile" } },
    }),
  ],
  callbacks: {
    async jwt({ token, account }) {
      // 初回サインイン時のみaccountが渡ってくる。以降はtokenに保持した値を使い回す。
      if (account) {
        token.idToken = account.id_token;
        token.refreshToken = account.refresh_token;
        token.expiresAt = account.expires_at ? account.expires_at * 1000 : undefined;
        return token;
      }

      // 有効期限内ならそのまま使う。
      if (token.expiresAt && Date.now() < token.expiresAt) {
        return token;
      }

      return refreshIdToken(token);
    },
    async session({ session, token }) {
      session.accessToken = token.idToken;
      // 更新に失敗した場合はUI側で再ログインを促せるようにする(黙って401が続くのを避ける)。
      if (token.error === "RefreshTokenError") {
        session.error = "RefreshTokenError";
      }
      return session;
    },
  },
};

/**
 * Cognitoのid_tokenは1時間程度で失効する。家族は通知を受けて久しぶりに開くことが多く、
 * 失効のたびに再ログインを強いると緊急時に操作できないため、リフレッシュトークンで更新する。
 */
async function refreshIdToken(token: Record<string, unknown> & { refreshToken?: string }) {
  if (!token.refreshToken || !process.env.COGNITO_ISSUER) {
    return { ...token, error: "RefreshTokenError" as const };
  }

  try {
    const response = await fetch(`${process.env.COGNITO_ISSUER}/oauth2/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: process.env.COGNITO_CLIENT_ID ?? "",
        client_secret: process.env.COGNITO_CLIENT_SECRET ?? "",
        refresh_token: token.refreshToken,
      }),
    });

    if (!response.ok) throw new Error(`token refresh failed: ${response.status}`);
    const refreshed = (await response.json()) as { id_token?: string; expires_in?: number };

    return {
      ...token,
      idToken: refreshed.id_token,
      expiresAt: refreshed.expires_in ? Date.now() + refreshed.expires_in * 1000 : undefined,
      error: undefined,
    };
  } catch {
    return { ...token, error: "RefreshTokenError" as const };
  }
}
