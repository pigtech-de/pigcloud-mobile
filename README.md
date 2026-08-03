# PigCloud Mobile

The [PigCloud](https://pigcloud.de) mobile app: a
[Capacitor](https://capacitorjs.com/) wrapper around the PigCloud web app with
native bridges for downloads, media saving, and biometric unlock. Android
first; not yet released to an app store.

## Build

Requires Node 22+, JDK 21, and the Android SDK.

```bash
npm ci
npx cap sync android
cd android
./gradlew assembleDebug
```

Release bundles (`bundleRelease`) are signed with PigTech's upload key and
cannot be reproduced bit-for-bit from this repo; a debug build is the way to
run the app yourself.

## Source, issues, and license

This repository is a source mirror published for review. It does not accept
pull requests; bug reports and feature requests go to
[pigcloud-issues](https://github.com/pigtech-de/pigcloud-issues/issues).

The source is available under the
[PolyForm Internal Use License 1.0.0](LICENSE): you may read, audit, and build
it for your own internal or personal use. Any other use, including
redistribution, needs written permission from PigTech. The official app, once
released, is provided under the
[PigCloud Terms of Service](https://pigtech.de/terms/).
