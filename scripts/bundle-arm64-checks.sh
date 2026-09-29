#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root_module=$(basename "$PWD")
stage=build/arm64-checks
mkdir -p "$stage/tests"
for module in "$root_module" sysnative webuicheck; do
    ./kotlin task ":$module:linkLinuxArm64TestDebug"
    cp "build/tasks/_${module}_linkLinuxArm64TestDebug/${module}_test.kexe" "$stage/tests/$module.kexe"
done
cp build/tasks/_webuicheck-linux_linkLinuxArm64Debug/webuicheck-linux.kexe "$stage/webuicheck.kexe"
cp build/tasks/_systemdcheck-linux_linkLinuxArm64Debug/systemdcheck-linux.kexe "$stage/systemdcheck.kexe"
