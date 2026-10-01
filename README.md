# PigCloud Mobile

Android and iOS clients for PigCloud with native downloads, media saving and biometric unlock.

## Commands

- Requires Node.js 22+; Android also needs JDK 21 and the Android SDK; iOS needs Xcode on macOS.
- Install dependencies: `npm ci`
- Android debug build: `npx cap sync android && cd android && ./gradlew assembleDebug`
- Open the iOS project: `npx cap sync ios && npx cap open ios`
- Official builds require PigTech signing keys; use a debug build for local verification.

## References

- [App availability](https://pigcloud.de/)
- [Capacitor](https://capacitorjs.com/)
- [Report an issue](https://github.com/pigtech-de/pigcloud-issues/issues)
- [Source license](LICENSE)
- [App terms](https://pigtech.de/terms/)
