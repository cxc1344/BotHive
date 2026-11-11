# Third-party notices

## PRoot

This patch packages Android-native PRoot executables built from the Termux PRoot project. PRoot is licensed under GNU General Public License v2 (GPL-2.0); see `licenses/GPL-2.0.txt`.

- Upstream source: https://github.com/termux/proot
- Source revision represented by the pinned Android binary build: `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`
- Source at that revision: https://github.com/termux/proot/tree/a179d3e8a4e045aaa1fb8cc3284f23509d96d353
- Android binary package and build workflow: https://github.com/ahmed-alnassif/proot
- Pinned release: `v26.10.08-a179d3e`

The APK build downloads the four ABI archives from that release and checks the SHA-256 digest before packaging. The binaries are invoked as separate processes; they are not loaded into the app process as JNI libraries. The archive build workflow uses Android NDK r29 and statically links talloc.

SHA-256 pins:

| Android ABI | Release archive | SHA-256 |
|---|---|---|
| arm64-v8a | proot-aarch64.zip | d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3 |
| armeabi-v7a | proot-arm.zip | 0c382c0512c3d536dc0685f3f6264b326073c071ebdd961e3346799aae8dd5b3 |
| x86_64 | proot-x86_64.zip | ef06aeb47f5bb9c96bb9019fe8efa320ed199151020428b2335af6035c05f166 |
| x86 | proot-x86.zip | 640da6a26dec3e86d3bdf4f3b9d8f6dd0466ba7be0de36b1ca6e508229610f36 |

The PRoot source is available from the upstream repository at the pinned revision. The release/build repository includes its own GPL-3.0 license for its build scripts; see https://github.com/ahmed-alnassif/proot/blob/main/LICENSE.

## Ubuntu Base root filesystem

Ubuntu Base is downloaded at first install, not embedded in this source bundle. The downloader tries the official Ubuntu cdimage server first and falls back to a public mirror (same path layout). The downloaded archive is checked against the SHA-256 listed in the release directory's SHA256SUMS file served **by the same host**; this detects corruption and truncated transfers but is not an authenticity guarantee against a malicious mirror or network intermediary (pinning the expected digests in the app is a possible future hardening). Ubuntu and its packages retain their own license notices inside each Bot's root filesystem.

The archive is unpacked by an in-app tar reader (`TarExtract.kt`, original code in this repository, no third-party tar library).

- Ubuntu release: `24.04.3` (ubuntu-base tarball)
- Official index: https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/
- Ubuntu project: https://ubuntu.com/

## Shizuku

The optional Shizuku channel uses the Shizuku API (`dev.rikka.shizuku:api` and `:provider`), licensed under the Apache License 2.0.

- Project: https://github.com/RikkaApps/Shizuku
- The channel is inert unless the user installs Shizuku separately and grants this app permission inside it.

## Important runtime limitation

PRoot is userspace syscall/path emulation on the Android host kernel. It is not a Linux virtual machine, does not create kernel namespaces or cgroups, and must not be treated as a security boundary between mutually hostile code or Bots.

## Bundled fonts

Four font files under `app/src/main/res/font/` provide the interface typography:

| File | Face | License |
|---|---|---|
| `serif_regular.otf`, `serif_bold.otf` | Noto Serif SC (subset) | SIL Open Font License 1.1 |
| `mono_regular.otf`, `mono_bold.otf` | Latin Modern Mono | GUST Font License |

**Noto Serif SC** — Google/Adobe's CJK serif family. The files here are **subsets**, not the
originals: reduced to GB2312 (6,763 hanzi) plus ASCII, Greek, general punctuation, arrows, math
operators, box drawing and dingbats, which brings each weight from ~11 MB down to ~2.3 MB. Glyph
outlines and metrics are unmodified; only unused glyphs were removed, so the reserved font name
was not changed.

- Upstream: https://github.com/notofonts/noto-cjk
- License: SIL Open Font License 1.1 — https://scripts.sil.org/OFL
- Subsetting performed with fontTools.

**Latin Modern Mono** — from the GUST Latin Modern family (a descendant of Knuth's Computer
Modern), used for code blocks, tool output and tables.

- Upstream: https://www.gust.org.pl/projects/e-foundry/latin-modern
- License: GUST Font License — https://www.gust.org.pl/projects/e-foundry/licenses/gfl.txt
- Fetched unmodified from CTAN (`fonts/lm`).

Coverage note: the serif face carries Chinese, Latin and math symbols in one family, so mixed
copy shares one weight and rhythm. Latin Modern Mono has no CJK glyphs; Chinese inside code
blocks falls back to the platform font, which is acceptable because code is mostly ASCII.
