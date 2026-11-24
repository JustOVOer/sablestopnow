# Third-Party Notices

This project is MIT-licensed. The following third-party components are
included in binary form and remain under their own licenses.

## Manifold

- Project: Manifold — https://github.com/elalish/manifold
- Copyright: The Manifold Authors
- License: Apache License 2.0 (`LICENSES/Apache-2.0.txt`)
- Files: `sablestopnow/native/win-x86_64/manifold.dll`,
  `sablestopnow/native/win-x86_64/manifoldc.dll`
- Source of the Windows binaries: official `manifold3d` Java binding jar
  (https://github.com/CommonWealthRobotics/manifold3d-java), version 3.5.1.
- Notes: the native libraries are unmodified. The JNA wrapper
  `com.ovo.sablestopnow.client.manifold.ManifoldBridge` is original MIT code.

Manifold's Windows build may include the following optional dependencies:

- oneTBB — Apache License 2.0 (`LICENSES/Apache-2.0.txt`)
- Clipper2 — Boost Software License 1.0 (`LICENSES/BSL-1.0.txt`)

## JNA

- Project: Java Native Access — https://github.com/java-native-access/jna
- License: Apache License 2.0 / LGPL 2.1 dual license
- The project already used JNA before this change; see `lib/jna-5.14.0.jar`.
