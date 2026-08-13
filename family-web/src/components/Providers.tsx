"use client";

import { SessionProvider } from "next-auth/react";

/** クライアントコンポーネント(EventActions等)がuseSessionでアクセストークンを取れるようにする。 */
export function Providers({ children }: { children: React.ReactNode }) {
  return <SessionProvider>{children}</SessionProvider>;
}
