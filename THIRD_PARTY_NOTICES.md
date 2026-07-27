# Third-party notices

MCLauncher's interface, storage, instance management, account flow, download orchestration, launch planning, controls and JNI coordination are project code.

The standalone APK build uses third-party components including:

- MojoLauncher Android launch-engine portions — LGPL-3.0; built from the exact commit in `vendor/engine-lock.json`.
- dnbootstrap/Android GLFW — its upstream GLFW licence and component notices.
- Android OpenJDK runtime packages for Java 8, 17, 21 and 25 — their upstream OpenJDK/GPL-with-Classpath-Exception terms and included notices.
- Patched LWJGL artifacts and native classifiers — their upstream licences and source records from the pinned substitution manifest.
- MobileGlues — LGPL-2.1; official release binary, exact source commit, release hash and licence are pinned in `vendor/engine-lock.json`.
- Java Native Access (JNA) Android dispatch library — Apache-2.0 or LGPL-2.1-or-later; the official AAR, version and hash are pinned in `vendor/engine-lock.json`.
- GL4ES and/or OpenLTW plus any packaged graphics dependencies — their respective upstream licences.
- OpenAL Soft and other engine-native dependencies — their respective upstream licences.
- AndroidX, Jetpack Compose, Kotlin, kotlinx, Apache Commons Compress and XZ for Java — their respective upstream terms.

The build workflow embeds the available engine, GLFW and source-reference notices into `assets/bundled_engine/common/licenses` and records component sources and hashes in `bundle-manifest.json`.

Minecraft itself, Mojang assets, account credentials and paid content are not redistributed in this source package or generated engine payload.

Anyone redistributing a generated APK must preserve the bundled notices and satisfy all applicable source, relinking and attribution obligations. An original launcher UI does not remove those third-party obligations.
