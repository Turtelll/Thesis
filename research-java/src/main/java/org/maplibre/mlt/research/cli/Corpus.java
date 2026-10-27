package org.maplibre.mlt.research.cli;

import org.maplibre.mlt.research.extraction.ExtractionWriter.ManifestEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Reads an extraction's manifest the way every measurement walks it: grouped by tile, optionally limited. */
final class Corpus {

    private Corpus() {
    }

    /** The manifest entries reordered so every tile's layers are adjacent, tiles in first-seen order. */
    static List<ManifestEntry> groupedByTile(List<ManifestEntry> manifest) {
        final var byTile = new LinkedHashMap<String, List<ManifestEntry>>();
        for (final var entry : manifest) {
            byTile.computeIfAbsent(tileKey(entry), k -> new ArrayList<>()).add(entry);
        }
        return byTile.values().stream().flatMap(List::stream).toList();
    }

    /** The first {@code limit} tiles (all of them when {@code limit <= 0}); for quick development runs. */
    static List<ManifestEntry> tileLimited(List<ManifestEntry> manifest, int limit) {
        if (limit <= 0) {
            return manifest;
        }
        final var tiles = new LinkedHashSet<String>();
        final var kept = new ArrayList<ManifestEntry>();
        for (final var entry : manifest) {
            tiles.add(tileKey(entry));
            if (tiles.size() > limit) {
                break;
            }
            kept.add(entry);
        }
        return kept;
    }

    static String tileKey(ManifestEntry entry) {
        return entry.z() + "/" + entry.x() + "/" + entry.y();
    }
}
