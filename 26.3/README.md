# Surgeon

Grafts previously-built chunk-section meshes (solid + cutout geometry) back
onto revisited terrain instead of re-meshing it from scratch. Caching is
keyed by a hash of the section's block states and light levels (plus a
2-block halo). When you rejoin the same world/area, Sodium skips the
per-block model/fluid baking for any section whose cached mesh is still
valid and loads the raw vertex buffer from disk instead.

## Global, deduplicated, compressed storage

Many sections share byte-identical geometry - flat stone layers, superflat
worlds, repeated strip-mine tunnels, the same structure generated in
different worlds. Rather than storing one full copy per section, the cache
splits each entry into:

- a tiny **pointer** file per section, namespaced per world/server/
  dimension (content-validity hash, occlusion data, and a reference to
  where its vertex data lives), and
- a shared **blob** store, keyed by the SHA-256 of the (uncompressed)
  vertex bytes, deflate-compressed on disk. This store is **global** -
  shared across every world and server you've ever visited, not just the
  current one - because a stone block's mesh looks the same regardless of
  which save or dimension it's in.

A blob being evicted while some world's pointer still references it is
always safe: that pointer just becomes a miss on next read, which rebuilds
and rewrites it. Eviction is therefore plain least-recently-used on the
blob store directly (bumped on every read hit and on a write that finds the
content already present) - no cross-world reference counting needed.

## What's cached, what isn't

A section is only cached when it has:
- no block entities (chests, signs, etc. - `BuiltSectionInfo` flags)
- no animated sprites (water, lava, fire, etc.)
- no translucent geometry (glass, ice - translucent face order depends on
  Sodium's dynamic sort-triggering system, not just block content)

All three are comparatively cheap to recompute live anyway; the expensive
part this cache targets is the per-block model/fluid quad baking for the
solid/cutout passes. In practice this covers most underground stone, plain
terrain and structures - oceans, caves with lava, and rooms full of glass
fall back to a normal (uncached) rebuild every time.

## Correctness model

Every read is gated on a content hash computed fresh from the live world:
block states + block/sky light in the section and a 2-block halo around it
(the same halo Sodium itself reads for ambient occlusion and face culling),
plus everything else that's baked into vertex bytes without showing up in
block state or light - biome tint (sampled via `BiomeColors` at the
section's center), the active resource pack set, and the handful of Sodium
quality toggles that change fluid geometry (`hiddenFluidCulling`,
`improvedFluidShaping`) or gate AO shading (`useAmbientOcclusion`). A wrong
or stale pointer can only ever produce a hash mismatch (a miss), never a
wrong mesh being served - see the class docs on `MeshCacheManager` and
`SectionContentHashing` for the full reasoning, including the one risk that
*does* affect correctness (Sodium widening its internal neighbor-block
radius, or adding a new content-affecting quality toggle, in a future
version without this mod being updated to match).

The one place a collision *would* be a real correctness bug is the blob
store's content-addressing itself (two different vertex buffers wrongly
treated as the same blob) - that's why blob identity uses SHA-256
(truncated to 128 bits), not the fast non-cryptographic hash used for cache
validity. See `BlobHasher`'s class doc.

Per-world/server/dimension pointer namespacing is handled by directory
layout purely to keep different saves' files from colliding on disk; it is
*not* what makes resource-pack or quality-setting changes safe - that's the
hash, per above. An earlier version of this mod claimed namespacing covered
resource-pack invalidation; it didn't actually do so, which was a real gap
until the hash was extended to cover it.

(Checked and ruled out: a separate tri-level "Smooth Lighting" quality
setting, as a hypothetical gap beyond the plain AO on/off gate already
covered - it doesn't exist in this Minecraft version. Vanilla's own AO
option is a plain boolean here (`LevelSlice.ambientOcclusion`), and
Sodium's `SmoothLightPipeline` has no further internal quality branching -
`useAmbientOcclusion()` is the whole story on this axis.)

## Settings

Lives as a page inside Sodium's own video settings screen (via Sodium's
`sodium:config_api_user` entrypoint, the same mechanism Sodium Extra and
Reese's Sodium Options use) - look for "Surgeon" there. Backed by
`config/surgeon.properties`:
- `enabled` - on/off
- `max_cache_mb` - soft disk budget for the shared, global blob store;
  least-recently-used blobs are evicted first once a session's write count
  crosses the check interval. Per-world pointer files are not bounded by
  this (they're tiny - a few hundred bytes each) and are left alone.

## Known limitations

- Sections with block entities, animated sprites, or translucent geometry
  are never cached.
- Camera-position-dependent vertex *ordering* within a cached mesh (an
  internal Sodium draw-batching optimization, not content) won't match the
  current camera on the session where it's loaded from cache; this never
  affects what's drawn, only how optimally segments are grouped for the
  first draw call after load.
- A cache miss during a section rebuild costs one extra `LevelSlice`
  prepare+copy pass (for hashing) on top of the real build; a cache hit
  saves the entire per-block meshing pass in exchange for that same
  prepare+copy pass plus a disk read (now also an inflate pass, cheap
  relative to the mesh build it's replacing).
- Per-world pointer files accumulate unbounded over time (proportional to
  how much distinct terrain you've ever visited across all your worlds) -
  not a practical concern at typical play volumes, but not actively
  cleaned up either in this version.
- Renamed twice (Sodium Mesh Cache 0.1.0 -> Chunk Surgeon 0.2.0 -> Surgeon
  0.3.0) - old cache files under earlier config folder names are simply
  orphaned, not migrated; harmless, just reclaim the disk space manually if
  it matters.
