import { getServerSession } from "next-auth";
import { redirect } from "next/navigation";
import { authOptions } from "./auth";

/**
 * サーバーコンポーネント用。未ログイン、またはトークン更新に失敗している場合はログイン画面へ送る。
 * requirements.md 25章: 家族ユーザー認証を全ページで必須とする。
 */
export async function requireAccessToken(): Promise<string> {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken || session.error === "RefreshTokenError") {
    redirect("/login");
  }
  return session.accessToken;
}
