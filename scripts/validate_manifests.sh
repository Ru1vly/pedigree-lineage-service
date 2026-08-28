#!/usr/bin/env bash
set -euo pipefail

export PATH="/home/r1/tools/bin:$PATH"

echo "================================================================================"
echo " 🔍 INTEGRATED MANIFEST & HELM CHART VALIDATION SUITE"
echo "================================================================================"

# 1. Lint Helm Chart
echo "[1/5] Running helm lint on helm/pedigree-lineage..."
helm lint helm/pedigree-lineage

# 2. Render Helm Templates across environments
echo "[2/5] Testing Helm template rendering for values-dev, values-prod, values-canary..."
helm template test-dev helm/pedigree-lineage -f helm/pedigree-lineage/values-dev.yaml > /tmp/helm-dev.yaml
helm template test-prod helm/pedigree-lineage -f helm/pedigree-lineage/values-prod.yaml > /tmp/helm-prod.yaml
helm template test-canary helm/pedigree-lineage -f helm/pedigree-lineage/values-canary.yaml > /tmp/helm-canary.yaml
echo "  -> Rendered Dev: $(wc -l < /tmp/helm-dev.yaml) lines"
echo "  -> Rendered Prod: $(wc -l < /tmp/helm-prod.yaml) lines"
echo "  -> Rendered Canary: $(wc -l < /tmp/helm-canary.yaml) lines"

# 3. Assert KEDA ScaledObject Rules in rendered output
echo "[3/5] Asserting KEDA autoscaling rules in rendered Canary manifest..."
grep -q "minReplicaCount: 2" /tmp/helm-canary.yaml || (echo "ERROR: minReplicaCount missing" && exit 1)
grep -q "maxReplicaCount: 12" /tmp/helm-canary.yaml || (echo "ERROR: maxReplicaCount missing" && exit 1)
grep -q 'lagThreshold: "1000"' /tmp/helm-canary.yaml || (echo "ERROR: lagThreshold missing" && exit 1)
grep -q "topic: \"lineage.query.events\"" /tmp/helm-canary.yaml || (echo "ERROR: kafka topic missing" && exit 1)
grep -q "type: kafka" /tmp/helm-canary.yaml || (echo "ERROR: kafka trigger type missing" && exit 1)
echo "  -> KEDA ScaledObject assertions PASSED! (min=2, max=12, lag=1000, topic=lineage.query.events)"

# 4. Assert Argo Rollout Canary Steps
echo "[4/5] Asserting Argo Rollout Canary step configurations..."
grep -q "kind: Rollout" /tmp/helm-canary.yaml || (echo "ERROR: Rollout kind missing" && exit 1)
grep -q "setWeight: 10" /tmp/helm-canary.yaml || (echo "ERROR: setWeight 10 missing" && exit 1)
grep -q "setWeight: 100" /tmp/helm-canary.yaml || (echo "ERROR: setWeight 100 missing" && exit 1)
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
