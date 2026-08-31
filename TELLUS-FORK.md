# Tellus fork — core

This is the `coreSubProjects` submodule of the Tellus fork of Distant Horizons
(`TimStewartJ/distant-horizons`). `main` is official core tag `3.2.0b` plus the Tellus patch
series; `upstream-base` mirrors the official `main`.

Core does not build on its own: it is compiled and its unit tests run by the wrapper's CI
(`.github/workflows/ci.yml` in `TimStewartJ/distant-horizons`, `./gradlew core:test -PmcVer=26.2.0`).
Release tags (`3.2.0-b-tellus-fork.N`) are applied to both repositories at the commit pair a
release was built from; the wrapper's release workflow refuses to publish if they disagree.

The patch list, provenance and the procedure for moving to a new official release are documented in
`PATCHES.md` in the wrapper repository.
