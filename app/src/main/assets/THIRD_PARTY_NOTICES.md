# Third-party notices

## pyatv 0.18.0

Source: https://github.com/postlund/pyatv

Revision inspected: `b277a4c8222ecdcbaab8a24e3e713ca44765adb4`.

Copyright (c) 2020 Pierre Ståhl. MIT License. Full license is included in
`tests/reference/pyatv-LICENSE.txt` and the application assets.

Companion message layouts, HAP key derivation labels and RTI archive structures
in the Kotlin protocol adapter are informed by pyatv. The Mac probe imports
pyatv. `tests/reference/pyatv_fake_companion.py` is an unmodified copy of its MIT
licensed test fake, used only on loopback during development. Generated protocol
vectors contain public synthetic test keys and text, never the user's credentials.
Python, pyatv and the fake server are not packaged into the APK.

## Bouncy Castle Java 1.83

Source: https://www.bouncycastle.org/ and https://github.com/bcgit/bc-java

License: Bouncy Castle License (MIT style), full upstream text included in
`app/src/main/assets/bouncycastle-LICENSE.html`.

Used for SRP-6a arithmetic, SHA-512/HKDF, Ed25519, X25519 and ChaCha20-Poly1305.

## AndroidX, Kotlin and Gradle

AndroidX/Jetpack Compose, Kotlin and Gradle are Apache-2.0 licensed projects.
The full Apache License 2.0 is included in `app/src/main/assets/Apache-2.0.txt`.
The Gradle wrapper uses an official generated wrapper JAR; distribution checksum
is pinned. JUnit 4.13.2 (EPL 1.0) is a test dependency only.

## Researched but not included

- https://github.com/jqssun/android-bt-remote — GPL v3 LICENSE.
- https://github.com/iFeel-is-a-mouse/KeysJoy — Apache 2.0 LICENSE.

No code from these Bluetooth projects is included. Bluetooth HID is not enabled
in this version and no Bluetooth permissions are requested.
