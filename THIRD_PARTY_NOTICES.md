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

## Alpine Linux minirootfs

Alpine minirootfs is downloaded at first install from the official Alpine CDN, not embedded in this source bundle. The downloaded archive is checked against the SHA-256 sidecar served alongside it. Alpine and its packages retain their own license notices inside each Bot's root filesystem.

- Alpine release: `3.24.4`
- Official index: https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/
- Alpine project: https://www.alpinelinux.org/

## Important runtime limitation

PRoot is userspace syscall/path emulation on the Android host kernel. It is not a Linux virtual machine, does not create kernel namespaces or cgroups, and must not be treated as a security boundary between mutually hostile code or Bots.
