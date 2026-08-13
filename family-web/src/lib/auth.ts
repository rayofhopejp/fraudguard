import type { NextAuthOptions } from "next-auth";
import CognitoProvider from "next-auth/providers/cognito";

/**
 * requirements.md 36.2章: 家族ユーザー認証はAmazon Cognito User Pool。
 * TODO: JWTコールバックでCognitoのid_token/access_tokenをセッションへ載せ、lib/api.tsから使う。
 * TODO: requirements.md 16.3章「家族ごとの通知権限設定」をCognitoのcustom attributeか
 *       サーバー側device_membersテーブルのどちらで持つか設計を詰める。
 */
export const authOptions: NextAuthOptions = {
  providers: [
    CognitoProvider({
      clientId: process.env.COGNITO_CLIENT_ID ?? "",
      clientSecret: process.env.COGNITO_CLIENT_SECRET ?? "",
      issuer: process.env.COGNITO_ISSUER,
    }),
  ],
  callbacks: {
    async jwt({ token, account }) {
      if (account?.id_token) {
        token.idToken = account.id_token;
      }
      return token;
    },
    async session({ session, token }) {
      // TODO: session.accessToken = token.idToken を型拡張の上で設定する
      return session;
    },
  },
};
