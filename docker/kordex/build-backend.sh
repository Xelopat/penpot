#!/usr/bin/env bash
set -euo pipefail

revision="${1:-000000000000}"
[[ "$revision" =~ ^[0-9a-f]{12,40}$ ]] || { echo 'Invalid build revision'; exit 1; }

cd /workspace/common
clojure -T:build compile
clojure -M:dev:test --focus common-tests.types.shape-interactions-test \
  > /workspace/kordex-backend-common-tests.log 2>&1 || {
  cat /workspace/kordex-backend-common-tests.log
  exit 1
}
cat /workspace/kordex-backend-common-tests.log

cd /workspace/backend
rm -rf target
mkdir -p target/classes
# Report exactly the official version: the frontend and the other services
# compare it, and only the shared schema differs from the official build.
unzip -p /workspace/official-penpot.jar version.txt > target/classes/version.txt
test -s target/classes/version.txt || { echo 'Official version not found'; exit 1; }
cp ../CHANGES.md target/classes/changelog.md
clojure -T:build jar

# The jar is not AOT compiled: load the shared schema from it and check
# that the changes the fork frontend sends are accepted before publishing.
java -cp target/penpot.jar clojure.main /workspace/docker/kordex/check-schema.clj
java -cp target/penpot.jar clojure.main -e \
  "(println :kordex-backend-version (slurp (clojure.java.io/resource \"version.txt\")))"
