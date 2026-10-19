# uwb-android

UWB 対応の Pixel 2 台で UWB 測距 (接続) ができるかを検証する実験用 Android アプリです。

`androidx.core.uwb` には端末発見やパラメータ交換の仕組みがないため、OOB (Out-of-Band) 通信で UWB のアドレス・チャネル・セッション情報を交換してから測距を開始します。OOB は次の 3 方式を実装しており、方式ごとの接続時間を比較できます。

- BLE (GATT)
- Nearby Connections
- Wi-Fi Aware

## 動作環境

- UWB 対応 Pixel 2 台 (Pixel 6 Pro / 7 Pro / 8 Pro / 9 Pro 系 / Fold 系)
- Android 13 (API 33) 以上

## 使い方

1. 2 台にアプリをインストールする
2. 片方で「親機」、もう片方で「子機」を選ぶ
3. 両方で同じ OOB 方式を選んで開始する (初回は権限を許可する)
4. 測距画面で以下を確認する
   - 状態: OOB 接続中 → パラメータ交換済み → UWB 測距中
   - 測距値: `distance` / `azimuth` / `elevation` / `elapsedRealtimeNanos`
   - 計測時間: OOB 接続確立まで / 交換完了まで / 初回測距まで / 交換完了 → 初回測距
   - イベントログ: OOB の各ステップや交換したパラメータ、エラー

計測時間は各端末がそれぞれの時計で計測するため、親機と子機で値は一致しません。

## ビルドとテスト

```bash
./gradlew assembleDebug
```

```bash
./gradlew installDebug
```

```bash
./gradlew test
```

## 技術スタック

- Kotlin / Jetpack Compose / Material3
- `androidx.core.uwb` (alpha) / Google Play services Nearby
- Android Gradle Plugin 9 系 (Version Catalog: `gradle/libs.versions.toml`)

## ドキュメント

- 設計書: [docs/superpowers/specs/2026-10-18-uwb-oob-connection-test-design.md](docs/superpowers/specs/2026-10-18-uwb-oob-connection-test-design.md)
- 実装計画: [docs/superpowers/plans/2026-10-19-uwb-oob-connection-test.md](docs/superpowers/plans/2026-10-19-uwb-oob-connection-test.md)
