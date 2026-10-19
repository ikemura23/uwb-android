# uwb-android

UWB (Ultra-Wideband) を扱う Android アプリです。

> 現在は Android Studio の Jetpack Compose テンプレートから作成した初期状態で、UWB 関連の機能はまだ実装されていません。

## 技術スタック

- Kotlin / Jetpack Compose / Material3
- Android Gradle Plugin 9 系 (Version Catalog: `gradle/libs.versions.toml`)
- minSdk 24 / targetSdk 37

## ビルドと実行

Android Studio でプロジェクトを開いて実行するか、コマンドラインで以下を実行します。

```bash
./gradlew assembleDebug
```

```bash
./gradlew installDebug
```

## テスト

```bash
./gradlew test
```

```bash
./gradlew connectedAndroidTest
```

`connectedAndroidTest` には接続済みの端末またはエミュレータが必要です。
