#!/bin/sh
# 重新构建前端并打包为免 Docker 运行的 jar（需要本机已安装 Node.js 22 与 Maven、Java 21）。
set -e
cd "$(dirname "$0")"
(cd frontend && npm ci && npm run build)
rm -rf backend/src/main/resources/static
mkdir -p backend/src/main/resources/static
cp -R frontend/dist/. backend/src/main/resources/static/
(cd backend && mvn -q package)
cp backend/target/campus-buddy-api-1.0.0.jar campus-buddy-local.jar
echo "已生成 campus-buddy-local.jar，运行：java -jar campus-buddy-local.jar --spring.profiles.active=local"
