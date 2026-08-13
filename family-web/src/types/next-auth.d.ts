import "next-auth";
import "next-auth/jwt";

/**
 * requirements.md 36.2章: サーバーAPI(family-auth)はCognitoのJWTを要求するため、
 * セッションからアクセストークンを取り出せるように型を拡張する。
 */
declare module "next-auth" {
  interface Session {
    accessToken?: string;
    error?: "RefreshTokenError";
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    idToken?: string;
    refreshToken?: string;
    /** idTokenの有効期限(epoch ms)。 */
    expiresAt?: number;
    error?: "RefreshTokenError";
  }
}
