<div align="center">
  <a href="./app/src/main/res/mipmap-nodpi/zenstream_logo.png">
    <img src="./app/src/main/res/mipmap-nodpi/zenstream_logo.png" alt="Logo" width="120" height="120">
  </a>
  <h3 align="center">ZenStream Mobile</h3>
  <p align="center">
    An Android client for <a href="https://github.com/Loco-CTO/zenstream-mobile">ZenStream</a>.
    <br />
    <br />
    <a href="https://github.com/Loco-CTO/zenstream-mobile/issues">Submit Issues</a>
    ·
    <a href="https://github.com/Loco-CTO/zenstream-mobile/releases">Releases</a>
  </p>
</div>

<div align="center">

[![GitHub Forks](https://img.shields.io/github/forks/Loco-CTO/zenstream-mobile.svg?style=for-the-badge)](https://github.com/Loco-CTO/zenstream-mobile)
[![GitHub Stars](https://img.shields.io/github/stars/Loco-CTO/zenstream-mobile.svg?style=for-the-badge)](https://github.com/Loco-CTO/zenstream-mobile)
[![License](https://img.shields.io/github/license/Loco-CTO/zenstream-mobile.svg?style=for-the-badge)](https://github.com/Loco-CTO/zenstream-mobile/blob/main/LICENSE)
[![Github Watchers](https://img.shields.io/github/watchers/Loco-CTO/zenstream-mobile.svg?style=for-the-badge)](https://github.com/Loco-CTO/zenstream-mobile)

</div>

## How it fits together

ZenStream has one Orchestrator backend and two clients:

- [Web client](https://github.com/Loco-CTO/zenstream)
- [Android client](https://github.com/Loco-CTO/zenstream-mobile)
- [Orchestrator](https://github.com/Loco-CTO/zenstream-orchestrator)

## Configuration

Enter the Orchestrator URL when the app starts.

- `version.properties` contains the semantic version used for builds.
- `ANDROID_RELEASE_STORE_FILE`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and `ANDROID_RELEASE_KEY_PASSWORD` configure signed release builds.

Do not commit keystore files or signing credentials.

## Development

Requires Android Studio, JDK 21, and Android SDK 37. Open the project in Android Studio or build the debug APK with:

```sh
./gradlew assembleDebug
```

## Deployment

Install a local debug build on a connected device or emulator with:

```sh
./gradlew installDebug
```

Signed release APKs are published through [GitHub Releases](https://github.com/Loco-CTO/zenstream-mobile/releases).

## Checks

```sh
./gradlew spotlessCheck lint test assembleDebug
```

## Troubleshooting

- For an Android emulator connecting to an Orchestrator running on the host, use `http://10.0.2.2:<port>` and ensure the server is bound to an address the emulator can reach.
- For a physical device, use the host computer's LAN address instead of `127.0.0.1`, and allow the port through the firewall.
- If a release build fails, verify the four `ANDROID_RELEASE_*` signing settings and the keystore path.

## Releases

The latest signed APK is available from [GitHub Releases](https://github.com/Loco-CTO/zenstream-mobile/releases/latest). Download the asset named `zenstream-mobile-vX.Y.Z.apk`.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).
