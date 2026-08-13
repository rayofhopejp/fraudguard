# docs/deployment.md Part C: Lightsailインスタンス上でDockerとして稼働させる想定のビルド。
# next.config.mjs の output: "standalone" と対になっている。
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .

# NEXT_PUBLIC_* はビルド時にクライアントバンドルへ埋め込まれる。実行時の環境変数では届かない。
# 未指定だと空文字で焼き込まれ、ブラウザはAPIではなく自分自身の相対パスを叩いてしまう
# (実際に、家族が押した遠隔切断がAPIに一度も届かない不具合として発覚した)。
ARG NEXT_PUBLIC_API_BASE_URL
RUN test -n "$NEXT_PUBLIC_API_BASE_URL" || (echo "NEXT_PUBLIC_API_BASE_URL build arg is required" && false)
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN npm run build

FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
