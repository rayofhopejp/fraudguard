import { redirect } from "next/navigation";

// TODO: NextAuthのgetServerSession()でログイン状態を確認し、未ログインなら/loginへ。
export default function RootPage() {
  redirect("/devices");
}
