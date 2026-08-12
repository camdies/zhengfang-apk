#!/usr/bin/env bash
# =============================================================
# SCNU SSO 协议验证脚本 (协议发现阶段 / 路径 A)
#
# 作用：逐段执行 SCNU SSO 五段请求，确认 APK 静态协议在当前
#       服务器上是否仍有效，并收集证据档案（路径 B 所需）。
#
# 安全：
#   - 密码只通过 stdin 传入，不落日志、不落参数、不落环境变量
#   - Cookie 文件使用后自动删除
#   - 打印内容不包含密码和 Cookie 值
#
# 用法（Git Bash）：
#   cd /d/Android-Developments
#   printf '%s\n' '你的学号' '你的密码' | bash scripts/scnu_sso_probe.sh
# =============================================================
set -u

readonly CLIENT_ID="9347e8e342e93da94c8ecf27a9de2599"
readonly APP_ID="96"
readonly AUTH="https://sso.scnu.edu.cn/AccountService/openapi/auth.html"
readonly SSO_LOGIN="https://sso.scnu.edu.cn/AccountService/user/login.html"
readonly CALLBACK="https://jwxt.scnu.edu.cn/sso/oauthLogin"
readonly SYSNAME="%E6%95%99%E5%8A%A1%E4%BF%A1%E6%81%AF%E6%9C%8D%E5%8A%A1%E5%B9%B3%E5%8F%B0" # 教务信息服务平台
# 注意：服务器按原始 URL 解析 redirect_url（与 APK 一致），不能百分号预编码。
readonly CALLBACK_RAW="https://jwxt.scnu.edu.cn/sso/oauthLogin"

JAR="$(mktemp -d 2>/dev/null || echo /tmp)/sso_cookies_$$.txt"
trap 'rm -f "$JAR" 2>/dev/null' EXIT

read -r STUDENT_ID
read -r PASSWORD

echo "==> 第 1 段：初始化授权会话"
curl -sS -c "$JAR" -G -o /dev/null -w "  HTTP %{http_code}  ->  %{redirect_url}\n" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "response_type=code" \
  --data-urlencode "redirect_url=$CALLBACK_RAW" \
  "$AUTH"

echo "==> 第 2 段：提交账号密码"
curl -sS -b "$JAR" -c "$JAR" -o /dev/null -w "  HTTP %{http_code}  ->  %{redirect_url}\n" \
  -X POST "$SSO_LOGIN" \
  --data-urlencode "account=$STUDENT_ID" \
  --data-urlencode "password=$PASSWORD"

echo "==> 第 3 段：确认页面"
curl -sS -b "$JAR" -c "$JAR" -o /dev/null -w "  HTTP %{http_code}  ->  %{redirect_url}\n" \
  "$AUTH?sysname=$SYSNAME"

echo "==> 第 4 段：快速登录（关键：应跳到教务回调）"
curl -sS -b "$JAR" -c "$JAR" -G -o /dev/null -w "  HTTP %{http_code}  ->  %{redirect_url}\n" \
  --data-urlencode "app_id=$APP_ID" \
  --data-urlencode "redirect_url=$CALLBACK_RAW" \
  "https://sso.scnu.edu.cn/AccountService/openapi/fastlogin.html"

echo "==> 第 5 段：跟跳（-L 跟随到教务）"
curl -sS -b "$JAR" -c "$JAR" -G -L -o /tmp/scnu_fastlogin_final.html -w "  最终 HTTP %{http_code}  ->  %{url_effective}\n" \
  --data-urlencode "app_id=$APP_ID" \
  --data-urlencode "redirect_url=$CALLBACK_RAW" \
  "https://sso.scnu.edu.cn/AccountService/openapi/fastlogin.html"

echo ""
echo "==> Cookie 归属分类（证据档案关键产出）"
echo "  文件: $JAR"
awk -F'\t' 'NF>=7 && $6!="" { printf "  [%-8s] domain=%-28s path=%s  %s\n", $1, $6, $7, $5 }' "$JAR" \
  | awk '{print}' | sort

echo ""
echo "==> 判定指引"
echo "  - 第 4 段 redirect_url 若为 jwxt.scnu.edu.cn 开头，说明链路仍通。"
echo "  - 第 5 段最终 URL 若落在 jwxt.scnu.edu.cn，说明教务会话已建立。"
echo "  - domain 含 jwxt.scnu.edu.cn 的 Cookie = 教务会话核心（需持久化）。"
echo "  - domain 为 .scnu.edu.cn 的 Cookie = 父域 Cookie（需实验判定教务是否依赖）。"
echo "  - 第 1 列 hostOnly 若为 TRUE，则该 Cookie 只对设置它的主机有效，不能发给教务。"
rm -f /tmp/scnu_fastlogin_final.html 2>/dev/null
