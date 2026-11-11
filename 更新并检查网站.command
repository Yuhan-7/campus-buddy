#!/bin/sh
cd "$(dirname "$0")" || exit 1
echo '正在更新网站，已有账号和数据会保留……'
if ! docker compose up --build -d; then
  echo '更新失败。请把上面的错误文字发给我，不要发送 .env 文件。'
  read -r _
  exit 1
fi
echo '正在等待后端启动……'
i=0
while [ "$i" -lt 40 ]; do
  if curl -fsS --max-time 3 http://localhost:8088/api/health 2>/dev/null | grep -q '"status":"ok"'; then
    echo '更新成功。现在打开 http://localhost:8088/ ，刷新页面后登录。'
    read -r _
    exit 0
  fi
  i=$((i + 1))
  sleep 3
done
echo '前端已经启动，但后端仍未就绪。后端最后 35 行日志：'
docker compose logs --tail=35 backend
echo '请把这些日志发给我，不要发送 .env 文件。'
read -r _
exit 1
