#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root regardless of the caller's working directory, so the relative
# paths below resolve the same way in CI as they do by hand.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# This script used to begin with `export PATH="/home/r1/tools/bin:$PATH"` - one developer's
# absolute home directory. That is the only reason it ever found helm, and it is why the script
# could not run in CI or on anyone else's machine. Tools are located on the caller's PATH, with
# an opt-in override for anyone who keeps them somewhere unusual.
if [[ -n "${HELM_TOOLS_BIN:-}" ]]; then
    export PATH="${HELM_TOOLS_BIN}:$PATH"
fi

missing=()
for tool in helm python3; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
done
if (( ${#missing[@]} > 0 )); then
    echo "ERROR: required tool(s) not on PATH: ${missing[*]}" >&2
    echo "Install them, or set HELM_TOOLS_BIN to the directory that contains them:" >&2
    echo "  HELM_TOOLS_BIN=/path/to/bin $0" >&2
    exit 127
fi

# Render into a private temp directory that is cleaned up, rather than fixed /tmp paths two
# concurrent runs would overwrite for each other.
RENDER_DIR="$(mktemp -d)"
trap 'rm -rf "$RENDER_DIR"' EXIT

echo "================================================================================"
echo " 🔍 INTEGRATED MANIFEST & HELM CHART VALIDATION SUITE"
echo "================================================================================"
echo "  helm: $(command -v helm) ($(helm version --short 2>/dev/null || echo 'version unknown'))"

# 1. Lint Helm Chart
echo "[1/5] Running helm lint on helm/pedigree-lineage..."
helm lint helm/pedigree-lineage

# 2. Render Helm Templates across environments
echo "[2/5] Testing Helm template rendering for values-dev, values-prod, values-canary..."
helm template test-dev helm/pedigree-lineage -f helm/pedigree-lineage/values-dev.yaml > "$RENDER_DIR/helm-dev.yaml"
helm template test-prod helm/pedigree-lineage -f helm/pedigree-lineage/values-prod.yaml > "$RENDER_DIR/helm-prod.yaml"
helm template test-canary helm/pedigree-lineage -f helm/pedigree-lineage/values-canary.yaml > "$RENDER_DIR/helm-canary.yaml"
echo "  -> Rendered Dev: $(wc -l < "$RENDER_DIR/helm-dev.yaml") lines"
echo "  -> Rendered Prod: $(wc -l < "$RENDER_DIR/helm-prod.yaml") lines"
echo "  -> Rendered Canary: $(wc -l < "$RENDER_DIR/helm-canary.yaml") lines"

# 3. Assert KEDA ScaledObject Rules in rendered output
echo "[3/5] Asserting KEDA autoscaling rules in rendered Canary manifest..."
grep -q "minReplicaCount: 2" "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: minReplicaCount missing" && exit 1)
grep -q "maxReplicaCount: 12" "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: maxReplicaCount missing" && exit 1)
grep -q 'lagThreshold: "1000"' "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: lagThreshold missing" && exit 1)
grep -q "topic: \"lineage.query.events\"" "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: kafka topic missing" && exit 1)
grep -q "type: kafka" "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: kafka trigger type missing" && exit 1)
echo "  -> KEDA ScaledObject assertions PASSED! (min=2, max=12, lag=1000, topic=lineage.query.events)"

# 4. Assert Argo Rollout Canary Steps
echo "[4/5] Asserting Argo Rollout Canary step configurations..."
grep -q "kind: Rollout" "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: Rollout kind missing" && exit 1)
grep -q "setWeight: 10" "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: setWeight 10 missing" && exit 1)
grep -q "setWeight: 100" "$RENDER_DIR/helm-canary.yaml" || (echo "ERROR: setWeight 100 missing" && exit 1)
echo "  -> Argo Rollout Canary assertions PASSED!"

# 5. YAML Syntax Verification
echo "[5/5] Checking YAML syntax of ArgoCD and Flux manifests..."
python3 -c "
import yaml, glob
files = glob.glob('argocd/*.yaml') + glob.glob('flux/*.yaml')
for path in files:
    with open(path) as f:
        docs = list(yaml.safe_load_all(f))
        assert len(docs) > 0
print(f'  -> Successfully validated {len(files)} ArgoCD & Flux manifest files!')
"

echo "================================================================================"
echo " ✅ ALL MANIFESTS & HELM CHARTS VALIDATED SUCCESSFULLY!"
echo "================================================================================"
