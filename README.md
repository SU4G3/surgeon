# Surgeon

A [Sodium](https://modrinth.com/mod/sodium) addon for Fabric that grafts
previously-built chunk-section meshes back onto revisited terrain instead of
re-meshing it from scratch, with a globally deduplicated, compressed on-disk
store for the result.

Sodium already rebuilds a chunk section's mesh (the per-block model/fluid
quad baking that turns 4096 blocks into a GPU vertex buffer) every time that
section needs it - on world join, on reload, whenever you fly back into a
previously-unloaded area. That work is pure function of the section's block
states, light levels, and a handful of other inputs (see
[Correctness model](#correctness-model)) - if none of those changed since
last time, the result is identical. Surgeon caches that result to disk, keyed
by a hash of those inputs, and serves it back directly instead of letting
Sodium redo the work.

Two builds live in this repo because Sodium's internal APIs that this mod
hooks into changed between Minecraft 26.2 and 26.3 (see
[`ChunkBuildOutput`'s constructor gained a field](#mc-version-differences)):

- [`26.3/`](26.3) - Minecraft 26.3, Sodium 0.9.2+mc26.3
- [`26.2/`](26.2) - Minecraft 26.2, Sodium 0.9.2+mc26.2

Both are otherwise the same code.

## Does this actually matter, and where

Honestly: it's a narrow, situational optimization, not a universal FPS
multiplier. It saves **CPU time spent building meshes**, not GPU time, so it
does nothing for a GPU-bound setup. It has not been benchmarked in an actual
running game client (that requires a display/GPU this was developed without
access to) - everything here is architectural reasoning about what the cache
does and doesn't touch, not a measured number.

**Helps:**
- Restarting the client and rejoining the same world/server - every section
  gets rebuilt from scratch on join either way; this lets most of them load
  from disk instead.
- Flying far away and coming back in the same session - sections outside
  render distance get fully unloaded and rebuilt on return.
- Lower-thread-count CPUs, where chunk meshing competes with everything else
  for cycles - saved work there is more likely to show up as less stutter.
- Repetitive terrain (superflat worlds, stone layers, strip-mine tunnels) -
  the deduplicated blob store means these also cost less disk space, not
  just less CPU.

**Doesn't help:**
- The first time you ever see a given section - always a miss, nothing to
  serve yet.
- Any section with a block entity (chest, sign, ...), an animated sprite
  (water, lava, fire, ...), or translucent geometry (glass, ice) - none of
  these are cached at all (see [What's cached](#whats-cached-what-isnt)).
  That's a lot of what's visually interesting about most builds and most
  natural terrain near water.
- A GPU-bound game, or a CPU fast enough that chunk meshing was never the
  bottleneck to begin with.

## How it works

1. Before Sodium hands a section's rebuild off to a worker thread, a mixin
   intercepts it (`RenderSectionManager.submitSectionTask`) and computes a
   validity hash from the live world data for that section plus a 2-block
   halo around it (the same halo Sodium itself reads for ambient
   occlusion/face culling).
2. If a cache entry exists with a matching hash, its stored mesh data is
   used to synthesize a real `ChunkBuildOutput` directly - the same type a
   worker thread would have produced - and the actual per-block meshing
   pass is skipped entirely.
3. If not (or the section is ineligible for caching), Sodium builds it
   normally, and a second mixin (`ChunkBuilderMeshingTask.execute`)
   writes the result to the cache afterward, if eligible.

## Deduplicated, compressed storage

Many sections share byte-identical geometry - flat stone layers, superflat
worlds, repeated strip-mine tunnels, the same structure generated in
different worlds. Rather than storing one full copy per section, each cache
entry is split in two:

- a tiny **pointer** file per section, namespaced per world/server/
  dimension (the validity hash, occlusion data, and a reference to where
  its vertex data lives), and
- a shared **blob** store, keyed by the SHA-256 of the (uncompressed)
  vertex bytes and deflate-compressed on disk. This store is **global** -
  shared across every world and server you've visited, not just the
  current one, because a stone block's mesh looks the same regardless of
  which save or dimension it's in.

A blob being evicted while some world's pointer still references it is
always safe: that pointer just becomes a miss on next read, which rebuilds
and rewrites it. Eviction is plain least-recently-used on the blob store
directly (bumped on every read hit and on a write that finds the content
already present) - no cross-world reference counting needed. The soft disk
budget is configurable (default 2 GiB).

## What's cached, what isn't

A section is only cached when it has:
- no block entities (chests, signs, etc.)
- no animated sprites (water, lava, fire, etc.)
- no translucent geometry (glass, ice - translucent face order depends on
  Sodium's dynamic sort-triggering system, not just block content)

All three are comparatively cheap to recompute live anyway; the expensive
part this cache targets is the per-block model/fluid quad baking for the
solid/cutout passes.

## Correctness model

Every read is gated on a content hash computed fresh from the live world:
block states + block/sky light in the section and its 2-block halo, plus
everything else that's baked into vertex bytes without showing up in block
state or light:

- **Biome tint** - grass/foliage/water color is resolved per-biome and
  baked directly into vertex color at mesh-build time, sampled via
  `BiomeColors` at the section's center (vanilla's own biome color
  resolvers already average over a blend radius, so one sample per section
  is a faithful, cheap proxy).
- **Active resource packs** - a different pack can change a block's baked
  model or its biome colormap textures without touching block state or
  light at all. Folded in as the sorted, joined pack id list, recomputed on
  every check (cheap enough not to need a reload-event hook).
- **Content-affecting Sodium quality settings** - `useAmbientOcclusion`
  (gates whether AO shading is baked into vertex colors at all),
  `hiddenFluidCulling` and `improvedFluidShaping` (change which fluid faces
  get built and how). Other quality toggles were deliberately left out -
  things like `useBlockFaceCulling` only affect which already-invisible
  faces get optimized away, never what's visible.

A wrong or stale pointer can only ever produce a hash mismatch (a miss),
never a wrong mesh being served - with one exception: the blob store's own
content-addressing (two different vertex buffers wrongly treated as the
same blob) would be a real bug, which is why blob identity uses SHA-256
(truncated to 128 bits) rather than the fast, non-cryptographic hash used
for cache validity.

This list was arrived at by actually reading Sodium's mesh-build pipeline
source, not by guessing - an earlier version of this mod shipped with the
resource-pack gap unfixed (the hash didn't cover it, despite a comment
claiming namespacing handled it, which it never did) until that got caught
and fixed. Checked and ruled out: a separate tri-level "Smooth Lighting"
quality setting beyond the plain AO on/off gate - it doesn't exist in this
Minecraft version (vanilla's AO option is a plain boolean here, and
Sodium's smooth-light pipeline has no further internal quality branching).

If Sodium ever widens its internal neighbor-block radius (currently 2, not
part of its public API) or adds a new content-affecting quality toggle in a
future version without this mod being updated to match, that *would* be a
real correctness regression - a stale mesh could be served. Everything else
about this hash being "too wide" (extra inputs it didn't strictly need) can
only ever cost cache hit rate, never correctness.

## Settings

Lives as a page inside Sodium's own video settings screen (via Sodium's
`sodium:config_api_user` entrypoint, the same mechanism Sodium Extra and
Reese's Sodium Options use) - look for "Surgeon" there.

- **Enabled** - on/off.
- **Max Disk Usage (MB)** - soft disk budget for the shared, global blob
  store; least-recently-used entries are evicted first once exceeded.
- **Clear Cache** - opens a confirmation dialog, then wipes every cached
  mesh (pointers and blobs, across all worlds and servers) and resets this
  session's hit/miss counters. Always safe - the next rebuild of any
  section just repopulates it.

Backed by `config/surgeon.properties` if you'd rather edit it directly.

## MC version differences

Sodium's internal `ChunkBuildOutput` class gained a `chunkCaptureTime` field
between 26.2 and 26.3 (used elsewhere by Sodium for a piston/transient-block
flicker fix). Every other class this mod touches
(`BuiltSectionInfo`, `RenderSection`, `LevelSlice`, `NativeBuffer`,
`RenderSectionFlags`, `DefaultTerrainRenderPasses`, the `submitSectionTask`
signature, the public config API) is byte-identical between the two
versions, verified by diffing Sodium's own source at both release tags
rather than assumed. The 26.2 build's `CachedMeshReconstructor` and
`MixinRenderSectionManager` simply omit the extra constructor argument.

## Building

Each version directory is a self-contained Fabric Loom project:

```sh
cd 26.3   # or 26.2
./gradlew build
```

The built jar lands in `build/libs/`. Requires Sodium (matching the
`sodium_version` in that directory's `gradle.properties`) installed
alongside it at runtime.

## License

GPL-3.0-or-later - see [LICENSE](LICENSE).
