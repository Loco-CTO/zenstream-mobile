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

## Configuration

Enter the Orchestrator URL when the app starts. Release builds use `version.properties` and require Android signing credentials.

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

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).
