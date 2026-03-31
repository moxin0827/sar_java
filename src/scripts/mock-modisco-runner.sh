#!/usr/bin/env bash
set -euo pipefail

SRC_DIR="${1:-}"
OUT_DIR="${2:-}"

if [[ -z "$SRC_DIR" || -z "$OUT_DIR" ]]; then
  echo "Usage: mock-modisco-runner.sh <SRC_DIR> <OUT_DIR>"
  exit 2
fi

mkdir -p "$OUT_DIR"

cat > "$OUT_DIR/model.uml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<uml:Model xmi:version="2.1"
  xmlns:xmi="http://www.omg.org/XMI"
  xmlns:uml="http://www.eclipse.org/uml2/5.0.0/UML"
  name="GeneratedModel">
  <packagedElement xmi:type="uml:Package" xmi:id="pkg1" name="com.example"/>
</uml:Model>
EOF

cp "$OUT_DIR/model.uml" "$OUT_DIR/class.uml"
cp "$OUT_DIR/model.uml" "$OUT_DIR/package.uml"

echo "OK: wrote model.uml/class.uml/package.uml"
