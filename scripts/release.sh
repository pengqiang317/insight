#!/usr/bin/env bash
# 改完 insight/build.gradle.kts 的 version 和 Insight.kt 的 VERSION 后，在仓库根目录执行：
#   ./scripts/release.sh
# 脚本会同步文档里的依赖版本，询问升级说明，提交到 main 并打同名 tag。
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "当前目录不是 git 仓库。"
  exit 1
fi

if [[ "$(git branch --show-current)" != "main" ]]; then
  echo "请在 main 分支上发布。当前分支是 $(git branch --show-current)。"
  exit 1
fi

eval "$(python3 - <<'PY'
import re
import subprocess
import sys
from pathlib import Path

semver = re.compile(r"^\d+\.\d+\.\d+$")

def read_version(label, text, pattern):
    match = re.search(pattern, text, re.M)
    if not match or not semver.match(match.group(1)):
        print(f"{label} 里没有形如 0.1.1 的版本号。", file=sys.stderr)
        sys.exit(1)
    return match.group(1)

gradle = Path("insight/build.gradle.kts").read_text(encoding="utf-8")
insight = Path("insight/src/main/kotlin/com/bigtree/insight/Insight.kt").read_text(encoding="utf-8")
gradle_version = read_version(
    "insight/build.gradle.kts",
    gradle,
    r'^version = "(\d+\.\d+\.\d+)"',
)
insight_version = read_version(
    "Insight.kt",
    insight,
    r'const val VERSION = "(\d+\.\d+\.\d+)"',
)
if gradle_version != insight_version:
    print(
        f"版本不一致：build.gradle.kts 是 {gradle_version}，Insight.VERSION 是 {insight_version}。",
        file=sys.stderr,
    )
    sys.exit(1)

head = subprocess.check_output(
    ["git", "show", "HEAD:insight/src/main/kotlin/com/bigtree/insight/Insight.kt"],
    text=True,
)
head_version = read_version("已提交的 Insight.kt", head, r'const val VERSION = "(\d+\.\d+\.\d+)"')
if head_version == insight_version:
    print(f"版本号仍是已发布的 {insight_version}，请先改版本再运行。", file=sys.stderr)
    sys.exit(1)

sample = Path("sample/build.gradle.kts").read_text(encoding="utf-8")
coordinate = f"com.github.pengqiang317:insight:{insight_version}"
if coordinate in sample:
    print(
        f"sample 不能提前依赖 {coordinate}。请保持上一个已发布版本 {head_version}，等 JitPack 编完再改。",
        file=sys.stderr,
    )
    sys.exit(1)

print(f"VERSION={insight_version}")
print(f"PREVIOUS={head_version}")
PY
)"

if git rev-parse -q --verify "refs/tags/${VERSION}" >/dev/null; then
  echo "本地已有 tag ${VERSION}，不会覆盖。"
  exit 1
fi

if gh api "repos/pengqiang317/insight/git/refs/tags/${VERSION}" >/dev/null 2>&1; then
  echo "远程已有 tag ${VERSION}，不会覆盖。"
  exit 1
fi

remote_main="$(gh api repos/pengqiang317/insight/commits/main --jq .sha)"
local_head="$(git rev-parse HEAD)"
if [[ "$remote_main" != "$local_head" ]]; then
  echo "远程 main 是 ${remote_main}，本地 HEAD 是 ${local_head}。请先对齐后再发布。"
  exit 1
fi

python3 - "$VERSION" "$PREVIOUS" <<'PY'
import sys
from pathlib import Path

new, old = sys.argv[1], sys.argv[2]
coordinate_old = f"com.github.pengqiang317:insight:{old}"
coordinate_new = f"com.github.pengqiang317:insight:{new}"

readme = Path("README.md")
readme_text = readme.read_text(encoding="utf-8")
readme_text = readme_text.replace(f"tag `{old}`", f"tag `{new}`")
readme_text = readme_text.replace(coordinate_old, coordinate_new)
readme.write_text(readme_text, encoding="utf-8")

usage = Path("使用说明.md")
lines = []
for line in usage.read_text(encoding="utf-8").splitlines(keepends=True):
    if "改回" in line or "当前依赖的是已发布" in line:
        lines.append(line)
        continue
    line = line.replace(f"当前发布版本是 `{old}`", f"当前发布版本是 `{new}`")
    line = line.replace(f"Insight {old}", f"Insight {new}")
    line = line.replace(coordinate_old, coordinate_new)
    lines.append(line)
usage.write_text("".join(lines), encoding="utf-8")
PY

git add -A

while IFS= read -r path; do
  case "$path" in
    local.properties|*/local.properties|.env|*/.env|*.jks|*.keystore|*/.DS_Store)
      echo "拒绝提交 ${path}"
      exit 1
      ;;
  esac
done < <(git diff --cached --name-only)

if [[ -z "$(git diff --cached --name-only)" ]]; then
  echo "没有可以提交的改动。"
  exit 1
fi

echo "将发布 ${VERSION}，上一个版本是 ${PREVIOUS}。示例依赖保持 ${PREVIOUS}。"
echo "本次提交包含："
git diff --cached --name-only
echo
read -r -p "请输入版本升级说明：" notes </dev/tty
if [[ -z "${notes// }" ]]; then
  echo "升级说明不能为空。"
  exit 1
fi

GIT_AUTHOR_NAME="pengqiang317" \
GIT_AUTHOR_EMAIL="145394490+pengqiang317@users.noreply.github.com" \
GIT_COMMITTER_NAME="pengqiang317" \
GIT_COMMITTER_EMAIL="145394490+pengqiang317@users.noreply.github.com" \
git commit -m "$(cat <<EOF
发布 ${VERSION}

${notes}
EOF
)"

git tag "$VERSION"

python3 - "$VERSION" <<'PY'
import base64
import json
import subprocess
import sys
import urllib.error
import urllib.request

version = sys.argv[1]
token = subprocess.check_output(["gh", "auth", "token"], text=True).strip()

def api(method, path, payload=None, ok=(200, 201)):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(
        "https://api.github.com" + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "User-Agent": "insight-release",
        },
    )
    try:
        with urllib.request.urlopen(request) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        print(f"GitHub API {method} {path} 失败：{error.code} {detail}", file=sys.stderr)
        sys.exit(1)

def git(*args, text=True):
    return subprocess.check_output(["git", *args], text=text)

parent = git("rev-parse", "HEAD^").strip()
parent_tree = git("rev-parse", "HEAD^^{tree}").strip()
expect_tree = git("rev-parse", "HEAD^{tree}").strip()
expect_commit = git("rev-parse", "HEAD").strip()
raw = git("cat-file", "-p", "HEAD", text=False)
message = raw.split(b"\n\n", 1)[1].decode("utf-8")
author_name, author_email, author_date, committer_name, committer_email, committer_date = git(
    "log", "-1", "--format=%an%n%ae%n%aI%n%cn%n%ce%n%cI"
).splitlines()

diff = git("diff-tree", "-r", "-z", "--raw", "--find-renames", "HEAD^", "HEAD", text=False)
records = diff.split(b"\0")
entries = []
index = 0
while index < len(records) and records[index]:
    meta = records[index].decode()
    index += 1
    status = meta.rsplit(" ", 1)[-1]
    if status.startswith(("R", "C")):
        old_path = records[index].decode()
        index += 1
        path = records[index].decode()
        index += 1
        entries.append(("D", old_path))
        entries.append(("M", path))
    else:
        path = records[index].decode()
        index += 1
        kind = "D" if status.startswith("D") else "M"
        entries.append((kind, path))

tree = []
for kind, path in entries:
    if kind == "D":
        tree.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
        continue
    listed = git("ls-tree", "HEAD", "--", path).strip()
    mode, _, blob_sha, _ = listed.split(maxsplit=3)
    content = git("cat-file", "blob", blob_sha, text=False)
    blob = api("POST", "/repos/pengqiang317/insight/git/blobs", {
        "content": base64.b64encode(content).decode(),
        "encoding": "base64",
    })
    if blob["sha"] != blob_sha:
        print(f"{path} 的 blob 与本地不一致：{blob['sha']} != {blob_sha}", file=sys.stderr)
        sys.exit(1)
    tree.append({"path": path, "mode": mode, "type": "blob", "sha": blob_sha})

created_tree = api("POST", "/repos/pengqiang317/insight/git/trees", {
    "base_tree": parent_tree,
    "tree": tree,
})
if created_tree["sha"] != expect_tree:
    print(f"tree 与本地不一致：{created_tree['sha']} != {expect_tree}", file=sys.stderr)
    sys.exit(1)

person = lambda name, email, date: {"name": name, "email": email, "date": date}
commit = api("POST", "/repos/pengqiang317/insight/git/commits", {
    "message": message,
    "tree": created_tree["sha"],
    "parents": [parent],
    "author": person(author_name, author_email, author_date),
    "committer": person(committer_name, committer_email, committer_date),
})
if commit["sha"] != expect_commit:
    print(f"提交与本地不一致：{commit['sha']} != {expect_commit}", file=sys.stderr)
    print("远程 main 尚未更新。", file=sys.stderr)
    sys.exit(1)

ref = api("PATCH", "/repos/pengqiang317/insight/git/refs/heads/main", {
    "sha": commit["sha"],
    "force": False,
})
if ref["object"]["sha"] != expect_commit:
    print("更新 main 后的提交不一致。", file=sys.stderr)
    sys.exit(1)

api("POST", "/repos/pengqiang317/insight/git/refs", {
    "ref": f"refs/tags/{version}",
    "sha": expect_commit,
})
print(f"已推送 main 和 tag {version}：{expect_commit}")
PY

echo "JitPack：https://jitpack.io/#pengqiang317/insight/${VERSION}"
echo "等到 insight-${VERSION}.aar 出现后，再把 sample 的依赖改成 com.github.pengqiang317:insight:${VERSION}。"
