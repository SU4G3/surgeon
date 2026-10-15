# Security Policy

## Supported Versions

Surgeon is a hobby project with no long-term support branches. Only the
latest release for each supported Minecraft version is maintained.

| Version                         | Supported          |
| -------------------------------- | ------------------- |
| Latest release (mc26.3 / mc26.2) | :white_check_mark:  |
| Anything older                   | :x:                 |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for a security vulnerability.

Use GitHub's private vulnerability reporting instead: go to the
[Security tab](../../security/advisories) of this repository and click
**"Report a vulnerability"**. This opens a private advisory draft that only
you and the maintainer can see, and lets us discuss and fix the issue
before anything is public.

Please include:
- What the issue is and why it's a security concern (not just a bug).
- Steps to reproduce it.
- The mod version and Minecraft/Sodium version you're on.
- Any relevant log output or stack trace.

## What counts as a vulnerability here

Surgeon is a client-side Fabric mod with no network component and no
server-side code. In scope, roughly:
- Anything that lets a malicious world, resource pack, or server make this
  mod read/write files outside its own cache directory (path traversal in
  the cache storage code, for example).
- Anything that lets cache data from one world/server leak into another in
  a way that's more than a wasted cache miss (see the correctness model in
  the [README](README.md) for what's already a deliberate non-issue by
  design).
- Denial of service against the client itself (e.g. an attacker-controlled
  input causing unbounded memory/disk use).

Out of scope: general gameplay bugs, visual glitches, or anything that
only affects your own single-player experience with no attacker involved -
please file those as a normal [issue](../../issues) instead.

## Response

This is maintained in spare time, so there's no guaranteed SLA. Reports
will be acknowledged and triaged as soon as reasonably possible, and a fix
released once confirmed.
