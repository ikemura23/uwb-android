# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

UWB 対応 Pixel 2 台で UWB 測距ができるかを検証する実験用 Android アプリ (`com.ikemura23.uwb`)。`androidx.core.uwb` には端末発見・パラメータ交換の仕組みがないため、OOB (Out-of-Band) で UWB アドレス・チャネル・セッション情報を交換してから測距を始める。OOB は BLE / Nearby Connections / Wi-Fi Aware の 3 方式を実装し、方式ごとの接続時間を比較できるようにしている。

- 設計書: `docs/superpowers/specs/2026-10-18-uwb-oob-connection-test-design.md` (成功基準・非ゴール・ユーザー決定事項はここが正)
- 実装計画: `docs/superpowers/plans/2026-10-19-uwb-oob-connection-test.md`
- 単一モジュール (`:app`)、Jetpack Compose + Material3、`androidx.core.uwb:uwb` (alpha)、`play-services-nearby`
- minSdk 33 / targetSdk・compileSdk 37 / Java 11

## コマンド

```bash
./gradlew assembleDebug            # デバッグビルド
./gradlew installDebug             # 接続端末へインストール
./gradlew lint                     # Android Lint
./gradlew test                     # ユニットテスト (app/src/test)
```

単一テストの実行:

```bash
./gradlew :app:testDebugUnitTest --tests "com.ikemura23.uwb.oob.OobCodecTest"
```

UWB/BLE/Wi-Fi Aware の実動作は実機 2 台 (片方を親機、もう片方を子機にして同じ OOB 方式を選ぶ) でしか確認できない。

## アーキテクチャ

処理の流れ: `StartScreen` (役割・OOB 方式の選択と実行時権限のリクエスト) → `RangingViewModel` → `ExperimentRunner.run()` の `Flow<ExperimentEvent>` → `RangingUiState.reduce()` → `RangingScreen`。

- **役割**: 親機 = UWB Controller かつ OOB の待ち受け側 (BLE: GATT サーバー+アドバタイズ / Nearby: advertise / Wi-Fi Aware: publish)。子機 = UWB Controlee かつ探す側。
- **`ExperimentRunner`** (`experiment/`) が OOB 方式に依存しない共通の制御を持つ: UWB セッションを開く → `OobTransport` でパラメータ交換 (30 秒タイムアウト) → OOB を閉じる → `prepareSession` で測距。OOB は交換完了後すぐ閉じる設計で、相手の停止は UWB の `RangingResultPeerDisconnected` で検知する。UWB アドレスはセッションごとに変わるので、再測距は OOB 交換からやり直す (ViewModel は開始ごとに `runId` キーで作り直している)。
- **`OobTransport`** (`oob/`) が OOB 方式ごとの差し替え点。新方式を追加する場合は `OobTransport` を実装し、`OobType` と `OobTransportFactory`、`permission/Permissions.kt` の必要権限を更新する。実装側の契約:
  - `onEvent` はどのスレッドから呼んでもよい (Runner が Channel 経由で単一コルーチンに集約する)
  - `OobEvent.LinkEstablished` をリンク確立時に必ず 1 回流す (時間計測に使う)
  - `exchangeAsController` は自分の情報を**送り終えてから**返す。`close()` は冪等
- **交換メッセージ** は `OobCodec` (`oob/OobMessages.kt`) の独自バイナリ形式を 3 方式で共有する。Wi-Fi Aware のメッセージ上限 (約 255 バイト) に収める必要がある。Wi-Fi Aware は publisher が受信するまで `PeerHandle` を得られないため、必ず子機から先に送る順序になっている。
- **`RangingParams`** (`uwb/`): `CONFIG_UNICAST_DS_TWR` + 親機がランダム生成する 8 バイトの Static STS キー。
- **時間計測** (`measure/TimingTracker`) は t0 = UWB セッションを開いて OOB を開始する直前 (権限ダイアログの時間を含めない)。計測ポイントは OOB 実装ではなく共通層 (Runner) で記録する。
- テスト容易性のため UWB は `UwbSessions`/`UwbSession` インターフェースで抽象化されており、`ExperimentRunnerTest` は fake を使う。

## 権限まわりの注意

- 実行時権限は OOB 方式ごとに異なる (`requiredRuntimePermissions`)。minSdk 33 なので `ACCESS_FINE_LOCATION` は不要。
- Nearby Connections は Android 17 (API 37) 以降 `ACCESS_LOCAL_NETWORK` が追加で必要 (Manifest でも `minSdkVersion="37"` で宣言)。

## ビルド構成の注意点

- **AGP 9 系** を使用。AGP 9 は Kotlin サポートを内蔵しているため `org.jetbrains.kotlin.android` プラグインは適用していない。古い AGP 前提の設定例をそのまま持ち込まないこと。
- `compileSdk { version = release(37) }` など AGP 9 の新 DSL を使っている。
- release ビルドの最適化は `optimization { enable = true; packageScope = ... }` で設定し、keep ルールは `app/src/main/keepRules/rules.keep` に置く。
- 依存関係は Version Catalog (`gradle/libs.versions.toml`) で一元管理。Compose は BOM 経由。リポジトリは `settings.gradle.kts` で集中管理 (`FAIL_ON_PROJECT_REPOS`)。
