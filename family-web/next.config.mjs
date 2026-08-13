/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // docs/deployment.md Part C: DockerイメージをLightsail上で動かすためstandalone出力を使う。
  output: "standalone",
};

export default nextConfig;
