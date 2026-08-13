import { NextResponse } from "next/server";

/**
 * requirements.md 36.2章: Cognito側のセッションも終わらせるログアウト。
 *
 * next-authの signOut() はこのアプリのセッションCookieを消すだけで、
 * Cognitoのホスト型UIのログイン状態はブラウザに残る。その状態で「ログイン」を押すと
 * 何も聞かれずに入り直せてしまい、利用者からは「ログアウトできていない」ように見える。
 *
 * 家族の端末を共有しているPCから離れるときに、確実にサインアウトできる必要があるため、
 * Cognitoの /logout へ送って向こうのセッションも切る。
 *
 * ホスト型UIのドメインが未設定の場合は、このアプリのログアウトだけで終える
 * (設定漏れでログアウト自体ができなくなるほうが困る)。
 */
export function GET(request: Request) {
  // リバースプロキシの背後では、リクエストのURLはコンテナ内部のホスト名(http://xxxx:3000)になる。
  // Cognitoのlogout_uriは登録済みのURLと完全一致する必要があるため、内部のURLでは弾かれる。
  // 公開URLはNEXTAUTH_URLに入っているのでそれを使う。
  const origin = process.env.NEXTAUTH_URL ?? new URL(request.url).origin;
  const domain = process.env.COGNITO_HOSTED_UI_DOMAIN;
  const clientId = process.env.COGNITO_CLIENT_ID;

  if (!domain || !clientId) {
    return NextResponse.redirect(`${origin.replace(/\/$/, "")}/login`);
  }

  const logoutUrl = new URL(`${domain.replace(/\/$/, "")}/logout`);
  logoutUrl.searchParams.set("client_id", clientId);
  logoutUrl.searchParams.set("logout_uri", origin.replace(/\/$/, ""));
  return NextResponse.redirect(logoutUrl.toString());
}
