import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "FraudGuard Family",
  description: "監視対象端末からの警告確認・遠隔操作",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
