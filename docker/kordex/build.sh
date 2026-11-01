#!/usr/bin/env bash
set -euo pipefail

cd /workspace/common
clojure -T:build compile
revision="${2:-000000000000}"
[[ "$revision" =~ ^[0-9a-f]{12,40}$ ]] || { echo 'Invalid build revision'; exit 1; }
app_version="kordex-2.18.5-2-g${revision:0:12}"
clojure -M -e "(require '[app.common.version :as v]) (assert (= \"2.18.5\" (:base (v/parse \"$app_version\"))))"
clojure -M:dev:test --focus common-tests.types.shape-interactions-test \
  > /workspace/kordex-common-tests.log 2>&1 || {
  cat /workspace/kordex-common-tests.log
  exit 1
}
cat /workspace/kordex-common-tests.log
cljfmt check src/app/common/types/shape/interactions.cljc \
  test/common_tests/types/shape_interactions_test.cljc

cd /workspace/frontend
cljfmt check src/app/plugins/parser.cljs src/app/plugins/format.cljs src/app/plugins/file.cljs \
  test/frontend_tests/plugins/parser_test.cljs \
  test/frontend_tests/plugins/format_test.cljs test/frontend_tests/plugins/file_test.cljs

# Build the upstream frontend, including its renderer and bundled plugins.
# Keep the original build script as the source of truth for build order.
bash ./scripts/build "$app_version"
clj-kondo --config-dir ../.clj-kondo --lint ../common/src src/ --fail-level error

# The frontend build above already generated the WASM artifacts.
clojure -M:dev:shadow-cljs compile test > /workspace/kordex-test-build.log 2>&1 || {
  cat /workspace/kordex-test-build.log
  exit 1
}
node target/tests/test.js --focus frontend-tests.plugins.parser-test \
  > /workspace/kordex-parser-tests.log 2>&1 || {
  cat /workspace/kordex-parser-tests.log
  exit 1
}
cat /workspace/kordex-parser-tests.log
node target/tests/test.js --focus frontend-tests.plugins.format-test \
  > /workspace/kordex-format-tests.log 2>&1 || {
  cat /workspace/kordex-format-tests.log
  exit 1
}
cat /workspace/kordex-format-tests.log
node target/tests/test.js --focus frontend-tests.plugins.file-test \
  > /workspace/kordex-file-tests.log 2>&1 || {
  cat /workspace/kordex-file-tests.log
  exit 1
}
cat /workspace/kordex-file-tests.log

# A new index changes inline-script hashes; keep nginx's CSP in sync.
python3 - <<'PY'
import base64
import hashlib
from html.parser import HTMLParser
from pathlib import Path


class InlineScripts(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.active = False
        self.parts = []
        self.hashes = []

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            self.active = not any(key == "src" for key, _ in attrs)
            self.parts = []

    def handle_data(self, data):
        if self.active:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == "script" and self.active:
            content = "".join(self.parts).encode("utf-8")
            digest = base64.b64encode(hashlib.sha256(content).digest()).decode()
            self.hashes.append("'sha256-" + digest + "'")
            self.active = False


parser = InlineScripts()
parser.feed(Path("target/dist/index.html").read_text(encoding="utf-8"))
if not parser.hashes:
    raise SystemExit("No inline scripts found; review the upstream index format")
Path("/workspace/kordex-csp-script-hashes.txt").write_text(
    " ".join(parser.hashes) + "\n", encoding="utf-8"
)
PY
