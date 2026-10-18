# Pixel 2台 UWB 接続検証アプリ 設計書

日付: 2026-10-18

## 目的・背景

Pixel 端末2台で UWB の接続（測距）ができるかを確認する実験用アプリ。
`androidx.core.uwb` には端末発見・パラメータ交換の仕組みがなく、アプリ側が OOB（Out-of-Band）で
UWB アドレス・チャネル等を交換する必要がある。その OOB を 3 方式実装し、それぞれで UWB 測距が始められるかを検証する。

参考資料:
- https://developer.android.com/develop/connectivity/uwb
- https://developer.android.com/jetpack/androidx/releases/core-uwb
- https://github.com/android/connectivity-samples/tree/main/UwbRanging （Nearby Connections を OOB に使用。2025/07 アーカイブ済み）
- https://developer.android.com/reference/kotlin/androidx/core/uwb/RangingPosition
- https://developer.android.com/develop/connectivity/wifi/wifi-aware

## 成功の基準（ユーザー決定）

1. OOB によるパラメータ交換が成功したことが画面で分かる
2. UWB 測距の値（`RangingResult.RangingResultPosition` の `RangingPosition`）が取得・表示される

## ゴール

- 起動画面で **親機 / 子機** を選ぶ（ユーザー決定）
- OOB を 3 方式実装する（ユーザー決定）。実装順:
  1. **BLE**（最初に実装）
  2. **Nearby Connections**
  3. **Wi-Fi Aware**
- 測距画面に `RangingPosition` の 4 値を表示する（ユーザー決定）:
  `distance`, `azimuth`, `elevation`, `elapsedRealtimeNanos`

## 非ゴール

- 成功率の集計や複数回試行の自動化（時間は 1 回の試行ごとに表示すれば十分）
- 1 対多（複数 Controlee）
- バックグラウンド測距
- 本番レベルのセキュリティ（OOB の暗号化・認証、Provisioned STS）
- 見た目の作り込み、ファイル共有等サンプルにある付加機能
- Pixel 以外の端末対応

## アンチゴール（動いても失敗とみなすもの）

- OOB 交換が成功したのか失敗したのか、どこで止まったのかが画面から分からない
- 3 方式で UWB 側のコードがバラバラになり、OOB 方式による差が比較できない

## 技術構成（ユーザー決定）

- Kotlin / Jetpack Compose（既存プロジェクト `com.ikemura23.uwb` をそのまま使う）
- `androidx.core.uwb:uwb:1.0.0`（安定版）

## 役割

| 画面上の名称 | UWB 上の役割 | OOB 上の役割（*） |
|---|---|---|
| 親機 | Controller（`controllerSessionScope()`） | 待ち受け側（BLE: GATT サーバー＋アドバタイズ / Nearby: advertise / Wi-Fi Aware: publish） |
| 子機 | Controlee（`controleeSessionScope()`） | 探す側（BLE: スキャン＋GATT クライアント / Nearby: discover / Wi-Fi Aware: subscribe） |

（*）OOB 上の役割の割り当ては Claude の判断。

## 画面構成

1. **起動画面**: 「親機」「子機」の選択と、OOB 方式（BLE / Nearby Connections / Wi-Fi Aware）の選択、開始ボタン。
   - OOB 方式を起動画面で選ぶのは Claude の判断（2 台で同じ方式を選ぶ運用）。未実装の方式は選択不可（disabled）にする。
   - 端末が UWB 非対応（`PackageManager.FEATURE_UWB` なし）の場合はその旨を表示。
2. **測距画面**:
   - 上部: 役割・OOB 方式・状態（例: 権限確認中 → OOB 接続中 → パラメータ交換済み → UWB 測距中 / 失敗）
   - 中央: `distance`(m), `azimuth`(°), `elevation`(°), `elapsedRealtimeNanos` の最新値（`RangingResultPosition.position`）。`distance`/`azimuth`/`elevation` は `RangingMeasurement?` なので `.value` を表示し、`null` は「—」。`elapsedRealtimeNanos` は非 null の `Long`
   - 下部: タイムスタンプ付きのイベントログ（OOB の各ステップ、交換したパラメータの内容、UWB の RangingResult 種別（`Initialized` / `Position` / `PeerDisconnected` / `Failure` とその理由）、エラー）。スクロール可能。
   - 計測時間（下記「時間計測」）。未到達の項目は「—」
   - 停止ボタン（セッション終了して起動画面に戻る）

## 処理フロー

UWB 側は OOB 方式に依存しない共通コード。

```
親機(Controller)                              子機(Controlee)
controllerSessionScope() 作成                  controleeSessionScope() 作成
 → localAddress, uwbComplexChannel 取得         → localAddress 取得
sessionId, STS キーを生成
        ──── OOB 接続確立 ────
        ←─── ControleeInfo {address} ────
        ──── ControllerInfo {address, channel(channel, preambleIndex),
                              sessionId, sessionKey} ────→
prepareSession(params) を collect              prepareSession(params) を collect
        ──── UWB 測距 ────
```

- `RangingParameters`:
  - `uwbConfigType = CONFIG_UNICAST_DS_TWR`
  - `sessionKeyInfo` = 8 バイトの Static STS キー（親機がランダム生成）。Provisioned STS は使わない（Claude の判断。非ゴールのセキュリティに該当し、Android 14 未満でも動くため）
  - `complexChannel` = 親機の `uwbComplexChannel`
  - `peerDevices` = 相手の `UwbDevice.createForAddress(address)`
  - `updateRateType = RANGING_UPDATE_RATE_AUTOMATIC`
- OOB の接続は交換完了後すぐに閉じる（Claude の判断。測距中の挙動を OOB 方式に依存させないため）。相手の停止は UWB の `RangingResultPeerDisconnected` で検知する。
- UWB アドレスはセッションスコープごとに変わるため、測距を再開する際は OOB 交換からやり直す。

## OOB 抽象化

OOB 方式ごとに次のインターフェースを実装する（形は Claude の判断、詳細は実装者に任せる）。

```kotlin
interface OobTransport {
    // 親機: 子機の情報を受け取り、自分の情報を返す
    suspend fun exchangeAsController(build: (ControleeInfo) -> ControllerInfo): ControleeInfo
    // 子機: 自分の情報を送り、親機の情報を受け取る
    suspend fun exchangeAsControlee(mine: ControleeInfo): ControllerInfo
    fun close()
}
```

- 交換メッセージは 1 つのバイト列フォーマット（`ControllerInfo` / `ControleeInfo` の簡易シリアライズ）を 3 方式で共有する。Wi-Fi Aware のメッセージ上限（約 255 バイト）に収まるサイズにする。
- 進捗ログは各実装からイベントとして流し、測距画面のログに出す。

### 1. BLE

- 親機: アプリ固有 Service UUID でアドバタイズ、GATT サーバーを立てる。
- 子機: Service UUID でスキャン → 接続 → 自分の `ControleeInfo` を characteristic に write → 親機からの `ControllerInfo` を notify（または read）で受け取る。
- GATT の細部（characteristic 構成、MTU、notify/read の選択）は実装者に任せる。

### 2. Nearby Connections

- `play-services-nearby` を追加。親機が advertise、子機が discover → 接続 → BYTES ペイロードで交換。
- Strategy は `P2P_POINT_TO_POINT`（Claude の判断）。

### 3. Wi-Fi Aware

- 親機が publish、子機が subscribe。データパス（NDP）は張らず、Discovery セッションの `sendMessage` でメッセージ交換のみ行う（Claude の判断。交換データが小さいため）。
- publisher は subscriber からメッセージを受けるまで `PeerHandle` を得られないため、必ず子機（subscriber）が先に `ControleeInfo` を送る順序にする。
- `WifiAwareManager.isAvailable()` が false の場合はエラー表示。

## 時間計測

- 時刻は `SystemClock.elapsedRealtimeNanos()` を使い、ms で表示する。
- 起点 t0 = 開始ボタン押下後、実行時権限が揃い OOB 処理を開始した時点（権限ダイアログの操作時間を含めないため。Claude の判断）。
- 表示する区間（各端末で自分の時計で計測する）:
  | 項目 | 区間 |
  |---|---|
  | OOB 接続確立まで（Claude の判断で追加） | t0 → OOB のリンク確立（BLE: GATT 接続 / Nearby: 接続確立 / Wi-Fi Aware: 相手の発見） |
  | 交換完了まで（ユーザー要求） | t0 → 相手の情報を受け取り、自分の情報も送信完了した時点 |
  | 初回測距まで（ユーザー要求） | t0 → 最初の `RangingResultPosition` 受信 |
  | 交換完了 → 初回測距（Claude の判断で追加） | UWB 部分のみの所要時間 |
- 計測ポイントは OOB 実装ごとに書かず、共通層（`OobTransport` が流すイベント＋UWB 層）で記録する。各実装は「リンク確立」「交換完了」のイベントを必ず流す。
- 2 台を同時に開始するとは限らないため、親機・子機で値は一致しない。両端末の値を見比べる前提とする。
- 計測値はイベントログにも出す（後からスクリーンショットで比較できるように）。

## 権限・設定

- `minSdk` を 24 → 33 に上げる（Claude の判断。UWB は Android 12+、Wi-Fi Aware の `NEARBY_WIFI_DEVICES` は 13+。Pixel UWB 対応機は全て Android 13 以上に更新可能）
- 権限: `UWB_RANGING`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`（Nearby Connections 用。API 33+ で要否が変わっているので最新ドキュメントで確認）, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` 等。必要なものは実装時に確定する。
- 実行時権限は開始ボタン押下時にまとめてリクエストし、拒否されたら測距画面ではなく起動画面でエラー表示。

## 検証方法

- 実機: UWB 対応 Pixel 2台（Pixel 6 Pro / 7 Pro / 8 Pro / 9 Pro 系 / Fold 系）。片方を親機、もう片方を子機で起動し、同じ OOB 方式を選んで開始。成功基準 1・2 を目視確認。
- 単体テスト: 交換メッセージのシリアライズ/デシリアライズ。その他はテスト最小限（実験プロジェクトのため）。

## 実装者に任せること

- パッケージ構成、ViewModel 等のアーキテクチャ
- 各 OOB のタイムアウト値、リトライ有無
- UI の細部
