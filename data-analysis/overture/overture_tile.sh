#!/usr/bin/env bash
# Tile the Overture Munich extract into MVT. Run inside WSL:
#   wsl -e bash data-analysis/overture_tile.sh
#
# Requires tippecanoe:  sudo apt install tippecanoe
set -euo pipefail

BBOX="11.359,48.061,11.723,48.248"   # matches munich-de-rs.mbtiles
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/data/overture"
DST="$ROOT/data/MVT/munich-overture.mbtiles"

command -v tippecanoe >/dev/null || {
  echo "tippecanoe not found. Install with: sudo apt install tippecanoe" >&2
  exit 1
}

ARGS=()
for f in "$SRC"/*.geojsonseq; do
  [ -s "$f" ] || continue
  layer="$(basename "$f" .geojsonseq)"
  ARGS+=(-L "$layer:$f")
done
[ ${#ARGS[@]} -gt 0 ] || { echo "no input in $SRC; run overture_fetch.py first" >&2; exit 1; }

# -x sources: per-feature provenance, pure tag bloat for geometry work.
# --no-line-simplification / --no-tiny-polygon-reduction: keep full ring detail.
# Tippecanoe's defaults flatten buildings to ~5-vertex boxes, destroying exactly
# the multi-vertex ring structure the encodings under test target.
# --no-feature-limit/--no-tile-size-limit: keep every feature; dropping
# features thins the corpus without making it more realistic.
# --no-tile-compression: the MLT converter reads raw protobuf, not gzip.
tippecanoe -o "$DST" --force \
  -Z0 -z14 \
  --clip-bounding-box="$BBOX" \
  --no-feature-limit --no-tile-size-limit \
  --no-tile-compression \
  -x sources \
  --no-line-simplification \
  --no-tiny-polygon-reduction \
  "${ARGS[@]}"

echo "done: $DST"
