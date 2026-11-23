#!/usr/bin/env bash
# 渐进式集成测试：按模块依赖顺序逐步扩大测试范围，每一步都把前面已接入的模块一起重跑。
# 用法：在项目根目录执行  bash scripts/integration_steps.sh
# 需要：Docker Desktop 已启动（首次运行会下载 Maven 镜像和依赖，约几分钟）。
# 结果：屏幕上打印汇总表，同时保存到 test-results/ 目录（每一步的原始报告也在里面）。
set -u
cd "$(dirname "$0")/.."
OUT=test-results/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
IMAGE=maven:3.9-eclipse-temurin-21

S1='InputsTest'
S2="$S1,BlackBoxTest#passwordLengthBoundaries+changePasswordErrors,QualityRequirementTest#registrationRequiresPrivacyConsent+loginLocksAfterRepeatedFailures+crossOriginWriteRejected,ApiFlowTest#registrationAndAdminAccess+idLoginAndSecurityQuestions"
S3="$S2,BlackBoxTest#profileEquivalenceClasses+duplicateNickname"
S4="$S3,BlackBoxTest#titleLengthBoundaries,QualityRequirementTest#guestCanBrowseButNotWrite+plazaFiltersInDatabase+closeIsUndoableDeleteIsFinal"
S5="$S4,MatchServiceTest,WhiteBoxMatchTest,AiAssistTest#synonymsMatch,BlackBoxTest#recommendPermissions"
S6="$S5,BlackBoxTest#duplicateInvitation+invitationTransitions,QualityRequirementTest#rejectWithReason+inviteRequiresContact,ApiFlowTest#invitationRevealsContactsOnlyAfterAcceptance"
S7="$S6,BlackBoxTest#disableAndEnable+adminCannotSeeContacts,ApiFlowTest#moderationViewsAndPrivateMessages,AiAssistTest,QualityRequirementTest#aiDraftAndModerationNote+accountDeletionErasesPersonalData,DemoDataTest"

NAMES=("1 公共层 common（输入校验）"
       "2 +账号 auth（注册、登录、锁定、同源校验）"
       "3 +个人资料 profile"
       "4 +需求 need（发布、广场、关闭/删除）"
       "5 +匹配 match（评分、同义词、推荐权限）"
       "6 +邀请 invitation（发送、状态转换、联系方式开放）"
       "7 +管理端与智能辅助 admin/ai（停用、隐私、违规、注销、演示数据）"
       "8 全量回归（mvn test，全部测试类）")
SETS=("$S1" "$S2" "$S3" "$S4" "$S5" "$S6" "$S7" "")

summary="$OUT/summary.md"
{
  echo "# 渐进式集成测试结果"
  echo
  echo "- 运行时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo "- 运行环境：$(uname -sm)；Docker 镜像 ${IMAGE}；测试数据库 H2（MySQL 兼容模式）"
  echo "- 代码版本：$(git rev-parse --short HEAD 2>/dev/null)（$(git describe --tags 2>/dev/null)）"
  echo
  echo "| 步骤 | 本步接入 | 用例数 | 通过 | 失败 | 错误 | 耗时(s) |"
  echo "|---|---|---|---|---|---|---|"
} > "$summary"

for i in "${!NAMES[@]}"; do
  rm -rf backend/target/surefire-reports
  echo ">>> 第 ${NAMES[$i]} 步"
  if [ -n "${SETS[$i]}" ]; then
    ARGS=(-Dtest="${SETS[$i]}" -Dsurefire.failIfNoSpecifiedTests=false)
  else
    ARGS=()
  fi
  docker run --rm -v "$PWD/backend":/app -v campus-buddy-m2:/root/.m2 -w /app "$IMAGE" \
    mvn -B -q ${ARGS[@]+"${ARGS[@]}"} test > "$OUT/step$((i+1)).log" 2>&1
  mkdir -p "$OUT/step$((i+1))-reports"
  cp -r backend/target/surefire-reports/. "$OUT/step$((i+1))-reports/" 2>/dev/null
  python3 - "$OUT/step$((i+1))-reports" "${NAMES[$i]}" >> "$summary" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
d, name = sys.argv[1], sys.argv[2]
t = f = e = s = 0; tm = 0.0; bad = []
for p in glob.glob(d + '/TEST-*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests', 0)); f += int(r.get('failures', 0)); e += int(r.get('errors', 0)); s += int(r.get('skipped', 0)); tm += float(r.get('time', 0))
    for c in r.iter('testcase'):
        if c.find('failure') is not None or c.find('error') is not None:
            bad.append(c.get('classname').split('.')[-1] + '#' + c.get('name'))
step, desc = name.split(' ', 1)
print(f"| {step} | {desc} | {t} | {t - f - e - s} | {f} | {e} | {tm:.1f} |")
for b in bad: print(f"|  | ✗ 未通过：{b} |  |  |  |  |  |")
PY
  tail -n 1 "$summary"
done

echo
cat "$summary"
echo
echo "结果已保存到 ${OUT} ，把 summary.md 的内容发给 Claude 即可。"
