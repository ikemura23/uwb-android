# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

UWB (Ultra-Wideband) を扱う Android アプリ (`com.ikemura23.uwb`)。現状は Android Studio の Compose テンプレートから生成した直後の状態で、UWB 関連の実装・依存関係・パーミッションはまだ入っていない。

- 単一モジュール構成 (`:app`)
- UI は Jetpack Compose + Material3 のみ (XML レイアウトなし)。エントリポイントは `MainActivity`、テーマは `ui/theme/` の `UwbandroidTheme`
- minSdk 24 / targetSdk・compileSdk 37 / Java 11

## コマンド

```bash
./gradlew assembleDebug            # デバッグビルド
./gradlew installDebug             # 接続端末へインストール
./gradlew lint                     # Android Lint
./gradlew test                     # ユニットテスト (app/src/test)
./gradlew connectedAndroidTest     # 計装テスト (要端末/エミュレータ, app/src/androidTest)
```

単一テストの実行:

```bash
./gradlew :app:testDebugUnitTest --tests "com.ikemura23.uwb.ExampleUnitTest.addition_isCorrect"
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ikemura23.uwb.ExampleInstrumentedTest
```

## ビルド構成の注意点

- **AGP 9 系** (`gradle/libs.versions.toml` の `agp`) を使用。AGP 9 は Kotlin サポートを内蔵しているため `org.jetbrains.kotlin.android` プラグインは適用していない (適用されているのは `com.android.application` と Compose Compiler プラグインのみ)。古い AGP 前提の設定例をそのまま持ち込まないこと。
- `compileSdk { version = release(37) }` など AGP 9 の新 DSL を使っている。
- release ビルドの最適化は従来の `isMinifyEnabled` / `proguardFiles` ではなく `optimization { enable = true; packageScope = ... }` で設定し、keep ルールは `app/src/main/keepRules/rules.keep` に置く。
- 依存関係・プラグインのバージョンは Version Catalog (`gradle/libs.versions.toml`) で一元管理。Compose ライブラリは BOM 経由でバージョン指定なし。
- リポジトリは `settings.gradle.kts` で集中管理 (`FAIL_ON_PROJECT_REPOS`)。モジュール側に `repositories {}` を書かない。
