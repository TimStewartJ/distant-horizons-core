# Tellus fork — core

This is the `coreSubProjects` submodule of the Tellus fork of Distant Horizons
(`TimStewartJ/distant-horizons`). `main` is official core `main` at `d354abe8c` (3.2.1-b-dev) plus
the Tellus patch series; `upstream-base` mirrors the official `main`.

Core does not build on its own: it is compiled and its unit tests run by the wrapper's CI
(`.github/workflows/ci.yml` in `TimStewartJ/distant-horizons`, `./gradlew core:test -PmcVer=26.2.0`).
Release tags (`<official version>-tellus-fork.N`, e.g. `3.2.1-b-dev-tellus-fork.4`) are applied to both repositories at the commit pair a
release was built from; the wrapper's release workflow refuses to publish if they disagree.

The patch list, provenance and the procedure for moving to a new official release are documented in
`PATCHES.md` in the wrapper repository.
