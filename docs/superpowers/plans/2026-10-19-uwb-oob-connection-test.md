# Pixel 2台 UWB 接続検証アプリ 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 親機/子機を選び、OOB（BLE → Nearby Connections → Wi-Fi Aware）でパラメータ交換して UWB 測距し、`RangingPosition` の4値と所要時間を表示する実験アプリを作る。

**Architecture:** OOB 方式ごとの差は `OobTransport` 実装だけに閉じ込め、UWB セッション・時間計測・画面状態は共通層（`ExperimentRunner` / `TimingTracker` / `reduce`）で扱う。UWB API は薄いラッパー `UwbSessions` 越しに使い、共通層を JVM 単体テストできるようにする。

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `androidx.core.uwb:uwb:1.0.0`, `com.google.android.gms:play-services-nearby`, Android BLE (GATT), Wi-Fi Aware, kotlinx-coroutines, JUnit4

**Spec:** `docs/superpowers/specs/2026-10-18-uwb-oob-connection-test-design.md`

## Global Constraints

- パッケージ: `com.ikemura23.uwb`（既存）。単一モジュール `:app`
- `minSdk = 33`（24 から変更）。`compileSdk` / `targetSdk` は既存の 37 のまま
- `androidx.core.uwb:uwb:1.0.0`
- `uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR`、`updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC`
- STS: Static STS、`sessionKeyInfo` は 8 バイト（親機がランダム生成）。`sessionId` も親機がランダム生成
- OOB 交換順: 子機が先に `ControleeInfo` を送り、親機が `ControllerInfo` を返す（Wi-Fi Aware の制約）
- OOB 接続は交換完了後すぐ閉じる
- 時刻は `SystemClock.elapsedRealtimeNanos()`、表示は ms。起点 t0 = 実行時権限が揃って OOB 処理を開始した時点
- 測距値表示: `distance`(m) / `azimuth`(°) / `elevation`(°) は `null` なら「—」、`elapsedRealtimeNanos` は常に数値
- 交換メッセージは Wi-Fi Aware の上限（約255バイト）以内
- 単体テスト実行: `./gradlew :app:testDebugUnitTest`

## Review Focus

1. **相手が見つからない / 2台とも同じ役割・違う OOB を選んだ** → 永久に待たず、OOB 交換を 30 秒でタイムアウトし「失敗」状態＋理由をログに出す（Task 4 のテスト `exchangeTimesOut`）
2. **OOB 中に停止ボタン** → `OobTransport.close()` が必ず呼ばれ、アドバタイズ・publish 等が残らない（Task 4 のテスト `cancelClosesTransport`）
3. **Bluetooth / Wi-Fi が OFF、Wi-Fi Aware 非対応** → クラッシュせず、例外メッセージが「失敗」状態とログに出る（Task 4 のテスト `transportErrorBecomesFailure`、各 transport は前提条件を満たさない場合 `IllegalStateException` を投げる）
4. **測距中に相手が停止 / `RangingResultFailure`** → 状態が「相手切断」/「失敗」に変わり、最後の測距値は残る（Task 5 のテスト `peerDisconnectedKeepsLastPosition`）
5. **権限拒否** → 測距画面に遷移せず起動画面にエラー表示（Task 5 のテスト `missingPermissionsListsDenied`）

---

### Task 1: ビルド設定・モデル・OOB メッセージコーデック

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`（minSdk 33、uwb / coroutines / lifecycle-viewmodel-compose / coroutines-test 追加）
- Create: `app/src/main/java/com/ikemura23/uwb/model/Role.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/model/OobType.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/oob/OobMessages.kt`
- Test: `app/src/test/java/com/ikemura23/uwb/oob/OobCodecTest.kt`
- Delete: `ExampleUnitTest.kt`, `ExampleInstrumentedTest.kt`

**Interfaces:**
- Produces:
  - `enum class Role(val label: String) { CONTROLLER("親機"), CONTROLEE("子機") }`
  - `enum class OobType(val label: String, val implemented: Boolean) { BLE("BLE", true), NEARBY("Nearby Connections", false), WIFI_AWARE("Wi-Fi Aware", false) }`（Task 7/8 で `implemented` を true にする）
  - `class ControleeInfo(val address: ByteArray)`
  - `class ControllerInfo(val address: ByteArray, val channel: Int, val preambleIndex: Int, val sessionId: Int, val sessionKey: ByteArray)`
  - 両クラスとも `equals`/`hashCode` を `contentEquals` ベースで実装（テスト比較用）
  - `object OobCodec { fun encode(info: ControleeInfo): ByteArray; fun encode(info: ControllerInfo): ByteArray; fun decodeControlee(bytes: ByteArray): ControleeInfo; fun decodeController(bytes: ByteArray): ControllerInfo }` — 不正入力は `IllegalArgumentException`

バイト形式（ビッグエンディアン）:
- ControleeInfo: `[0x02][addrLen:u8][addr]`
- ControllerInfo: `[0x01][addrLen:u8][addr][channel:u8][preambleIndex:u8][sessionId:i32][keyLen:u8][key]`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
class OobCodecTest {
    private val controller = ControllerInfo(byteArrayOf(0x12, 0x34), channel = 9, preambleIndex = 11,
        sessionId = 0x7fff_0001, sessionKey = ByteArray(8) { it.toByte() })

    @Test fun controleeRoundTrip() {
        val info = ControleeInfo(byteArrayOf(0x0a, 0x0b))
        assertEquals(info, OobCodec.decodeControlee(OobCodec.encode(info)))
    }
    @Test fun controllerRoundTrip() {
        assertEquals(controller, OobCodec.decodeController(OobCodec.encode(controller)))
    }
    @Test fun controllerFitsWifiAwareLimit() {
        assertTrue(OobCodec.encode(controller).size <= 255)
    }
    @Test(expected = IllegalArgumentException::class) fun wrongTypeRejected() {
        OobCodec.decodeController(OobCodec.encode(ControleeInfo(byteArrayOf(1, 2))))
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedRejected() {
        OobCodec.decodeController(OobCodec.encode(controller).copyOf(5))
    }
}
```

- [ ] **Step 2: 失敗を確認** — `./gradlew :app:testDebugUnitTest --tests '*OobCodecTest'` → コンパイルエラーで FAIL
- [ ] **Step 3: ビルド設定変更とモデル・`OobCodec` を実装**（`java.nio.ByteBuffer` を使用。`BufferUnderflowException` は `IllegalArgumentException` に包む）
- [ ] **Step 4: 成功を確認** — 同コマンドで PASS、`./gradlew :app:assembleDebug` も成功
- [ ] **Step 5: コミット** — `git commit -m "feat: add OOB message codec and models"`

---

### Task 2: 時間計測 `TimingTracker`

**Files:**
- Create: `app/src/main/java/com/ikemura23/uwb/measure/TimingTracker.kt`
- Test: `app/src/test/java/com/ikemura23/uwb/measure/TimingTrackerTest.kt`

**Interfaces:**
- Produces:
  - `data class Timings(val linkMs: Long? = null, val exchangeMs: Long? = null, val firstRangingMs: Long? = null) { val exchangeToRangingMs: Long? }`（後者は両方あるときだけ差分）
  - `class TimingTracker(private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos) { fun start(); fun markLinkEstablished(); fun markExchangeCompleted(); fun markFirstRanging(); val timings: Timings }` — 各 mark は最初の1回だけ記録。`start()` 前の mark は無視

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
class TimingTrackerTest {
    private var now = 0L
    private val tracker = TimingTracker { now }
    private fun at(ms: Long) { now = ms * 1_000_000 }

    @Test fun measuresFromStart() {
        at(1000); tracker.start()
        at(1200); tracker.markLinkEstablished()
        at(1500); tracker.markExchangeCompleted()
        at(1800); tracker.markFirstRanging()
        assertEquals(Timings(200, 500, 800), tracker.timings)
        assertEquals(300L, tracker.timings.exchangeToRangingMs)
    }
    @Test fun onlyFirstRangingCounts() {
        at(0); tracker.start(); at(100); tracker.markFirstRanging(); at(900); tracker.markFirstRanging()
        assertEquals(100L, tracker.timings.firstRangingMs)
    }
    @Test fun marksBeforeStartIgnored() {
        at(50); tracker.markLinkEstablished()
        assertEquals(Timings(), tracker.timings)
    }
}
```

- [ ] **Step 2: 失敗を確認** — `./gradlew :app:testDebugUnitTest --tests '*TimingTrackerTest'` → FAIL
- [ ] **Step 3: 実装**
- [ ] **Step 4: 成功を確認** — PASS
- [ ] **Step 5: コミット** — `git commit -m "feat: add timing tracker"`

---

### Task 3: UWB ラッパーと `RangingParameters` 生成

**Files:**
- Create: `app/src/main/java/com/ikemura23/uwb/uwb/UwbSessions.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/uwb/RangingParams.kt`
- Test: `app/src/test/java/com/ikemura23/uwb/uwb/RangingParamsTest.kt`

**Interfaces:**
- Consumes: `ControllerInfo`, `ControleeInfo`（Task 1）
- Produces:
  - `interface UwbSession { val localAddress: UwbAddress; val complexChannel: UwbComplexChannel? /* 親機のみ非 null */; fun prepare(params: RangingParameters): Flow<RangingResult> }`
  - `interface UwbSessions { suspend fun openController(): UwbSession; suspend fun openControlee(): UwbSession }`
  - `class AndroidUwbSessions(context: Context) : UwbSessions` — `UwbManager.createInstance(context)` の `controllerSessionScope()` / `controleeSessionScope()` を包む。`isAvailable()` が false なら `IllegalStateException("UWB が利用できません")`
  - `object RangingParams { fun newControllerInfo(session: UwbSession, random: Random = Random.Default): ControllerInfo; fun forController(mine: ControllerInfo, peer: ControleeInfo): RangingParameters; fun forControlee(peer: ControllerInfo): RangingParameters }`
    - `newControllerInfo`: address/channel/preambleIndex は session から、`sessionId = random.nextInt()`、`sessionKey = random.nextBytes(8)`
    - 両者とも sessionId・sessionKeyInfo・complexChannel は ControllerInfo の値、`peerDevices` は相手アドレス1件

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
class RangingParamsTest {
    private val ctrl = ControllerInfo(byteArrayOf(1, 2), 9, 11, 42, ByteArray(8) { 7 })
    private val ctle = ControleeInfo(byteArrayOf(3, 4))

    @Test fun controllerParams() {
        val p = RangingParams.forController(ctrl, ctle)
        assertEquals(RangingParameters.CONFIG_UNICAST_DS_TWR, p.uwbConfigType)
        assertEquals(42, p.sessionId)
        assertArrayEquals(ByteArray(8) { 7 }, p.sessionKeyInfo)
        assertEquals(UwbComplexChannel(9, 11), p.complexChannel)
        assertEquals(listOf(UwbDevice.createForAddress(byteArrayOf(3, 4))), p.peerDevices)
        assertEquals(RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC, p.updateRateType)
    }
    @Test fun controleeParamsPointAtController() {
        val p = RangingParams.forControlee(ctrl)
        assertEquals(listOf(UwbDevice.createForAddress(byteArrayOf(1, 2))), p.peerDevices)
        assertEquals(42, p.sessionId)
    }
    @Test fun newControllerInfoUsesSessionAndRandom() {
        val session = object : UwbSession {
            override val localAddress = UwbAddress(byteArrayOf(5, 6))
            override val complexChannel = UwbComplexChannel(9, 10)
            override fun prepare(params: RangingParameters) = emptyFlow<RangingResult>()
        }
        val info = RangingParams.newControllerInfo(session, Random(0))
        assertArrayEquals(byteArrayOf(5, 6), info.address)
        assertEquals(9, info.channel); assertEquals(10, info.preambleIndex)
        assertEquals(8, info.sessionKey.size)
    }
}
```

- [ ] **Step 2: 失敗を確認** — `./gradlew :app:testDebugUnitTest --tests '*RangingParamsTest'` → FAIL
- [ ] **Step 3: 実装**（`AndroidUwbSessions` は単体テスト対象外）
- [ ] **Step 4: 成功を確認** — PASS
- [ ] **Step 5: コミット** — `git commit -m "feat: add UWB session wrapper and ranging params"`

---

### Task 4: `OobTransport` と `ExperimentRunner`（共通の進行役）

**Files:**
- Create: `app/src/main/java/com/ikemura23/uwb/oob/OobTransport.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/experiment/ExperimentRunner.kt`
- Test: `app/src/test/java/com/ikemura23/uwb/experiment/ExperimentRunnerTest.kt`

**Interfaces:**
- Consumes: Task 1〜3 すべて
- Produces:
  - `sealed interface OobEvent { data class Log(val message: String) : OobEvent; data object LinkEstablished : OobEvent }`
  - ```kotlin
    interface OobTransport {
        /** 子機の情報を受け取り、build の結果を送信し終えてから返す */
        suspend fun exchangeAsController(build: (ControleeInfo) -> ControllerInfo): ControleeInfo
        /** 自分の情報を送り、親機の情報を受け取ってから返す */
        suspend fun exchangeAsControlee(mine: ControleeInfo): ControllerInfo
        /** 冪等。広告・接続・セッションを全て解放する */
        fun close()
    }
    ```
    各実装はコンストラクタで `onEvent: (OobEvent) -> Unit` を受け取り、リンク確立時に `LinkEstablished` を必ず1回流す
  - `enum class ExperimentStatus(val label: String) { OOB_CONNECTING("OOB 接続中"), EXCHANGED("パラメータ交換済み"), RANGING("UWB 測距中"), PEER_DISCONNECTED("相手切断"), FAILED("失敗") }`
  - `sealed interface ExperimentEvent { Log(message: String); Status(status: ExperimentStatus, reason: String? = null); TimingsUpdated(timings: Timings); Ranging(result: RangingResult) }`
  - `class ExperimentRunner(role: Role, sessions: UwbSessions, transportFactory: ((OobEvent) -> Unit) -> OobTransport, tracker: TimingTracker, exchangeTimeout: Duration = 30.seconds) { fun run(): Flow<ExperimentEvent> }`

`run()` の手順（`channelFlow`）:
1. `tracker.start()`、`Status(OOB_CONNECTING)`
2. 役割に応じ UWB セッションを開く（親機 `openController` / 子機 `openControlee`）、自アドレスを Log
3. transport を作り、`withTimeout(exchangeTimeout)` 内で交換。`OobEvent.LinkEstablished` → `tracker.markLinkEstablished()`＋`TimingsUpdated`、`OobEvent.Log` → `Log`
4. 交換が返ったら `tracker.markExchangeCompleted()`、交換内容を Log、`Status(EXCHANGED)`、`TimingsUpdated`、`transport.close()`
5. `session.prepare(params).collect`: `RangingResultInitialized` → `Status(RANGING)`、`RangingResultPosition` → 初回のみ `markFirstRanging()`＋`TimingsUpdated`、毎回 `Ranging(result)`、`RangingResultPeerDisconnected` → `Status(PEER_DISCONNECTED)`、`RangingResultFailure` → `Status(FAILED, reason)`。全結果を種別名で Log
6. タイムアウト・例外 → `Status(FAILED, message)`（タイムアウトは `"OOB 交換が 30 秒でタイムアウトしました"`）。キャンセル時も `finally` で `transport.close()`

- [ ] **Step 1: 失敗するテストを書く**（`FakeUwbSessions` と `FakeTransport` をテスト内に定義。`runTest` と `coroutines-test` を使用）

```kotlin
@Test fun controllerHappyPath()       // FakeTransport が LinkEstablished→ControleeInfo を返し、FakeSession が Initialized, Position×2 を流す
    // 期待: Status 列が [OOB_CONNECTING, EXCHANGED, RANGING]、Ranging イベント2件、
    //       最後の TimingsUpdated で linkMs / exchangeMs / firstRangingMs が全て非 null、
    //       FakeSession.prepare に渡った params.peerDevices が ControleeInfo のアドレス、transport.closed == true
@Test fun controleeSendsOwnAddress()  // FakeTransport.receivedControlee のアドレスが FakeSession.localAddress と一致
@Test fun exchangeTimesOut()          // FakeTransport が awaitCancellation()。advanceTimeBy(30_001) 後に
    // Status(FAILED, "OOB 交換が 30 秒でタイムアウトしました") が出て、transport.closed == true
@Test fun cancelClosesTransport()     // 交換中にコレクトしている job を cancel → transport.closed == true
@Test fun transportErrorBecomesFailure() // FakeTransport が IllegalStateException("Bluetooth が OFF です") を投げる
    // → Status(FAILED, "Bluetooth が OFF です")
@Test fun peerDisconnectedStatus()    // FakeSession が RangingResultPeerDisconnected を流す → Status(PEER_DISCONNECTED)
```

- [ ] **Step 2: 失敗を確認** — `./gradlew :app:testDebugUnitTest --tests '*ExperimentRunnerTest'` → FAIL
- [ ] **Step 3: 実装**
- [ ] **Step 4: 成功を確認** — PASS
- [ ] **Step 5: コミット** — `git commit -m "feat: add OOB transport interface and experiment runner"`

---

### Task 5: 画面（起動画面・測距画面）・状態 reducer・権限

**Files:**
- Create: `app/src/main/java/com/ikemura23/uwb/ui/RangingUiState.kt`（状態＋`reduce`＋表示用フォーマット）
- Create: `app/src/main/java/com/ikemura23/uwb/ui/RangingViewModel.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/ui/StartScreen.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/ui/RangingScreen.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/permission/Permissions.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/oob/OobTransportFactory.kt`
- Modify: `app/src/main/java/com/ikemura23/uwb/MainActivity.kt`（Greeting を削除し画面切替に置換）
- Modify: `app/src/main/AndroidManifest.xml`（`UWB_RANGING`、`<uses-feature android:name="android.hardware.uwb" android:required="false"/>`）
- Test: `app/src/test/java/com/ikemura23/uwb/ui/RangingUiStateTest.kt`, `app/src/test/java/com/ikemura23/uwb/permission/PermissionsTest.kt`

**Interfaces:**
- Consumes: `ExperimentRunner`, `ExperimentEvent`, `Timings`, `Role`, `OobType`
- Produces:
  - `data class LogLine(val timeMs: Long, val message: String)`（timeMs は t0 からの経過）
  - `data class RangingUiState(val role: Role, val oob: OobType, val status: ExperimentStatus = OOB_CONNECTING, val reason: String? = null, val position: RangingPosition? = null, val timings: Timings = Timings(), val logs: List<LogLine> = emptyList())`
  - `fun RangingUiState.reduce(event: ExperimentEvent, timeMs: Long): RangingUiState` — `Status`/`Ranging`/`TimingsUpdated` もログ行を追加（計測値をログに残す仕様のため）。`Ranging` は `RangingResultPosition` のときだけ position 更新
  - `fun formatMeters(m: RangingMeasurement?): String`（`"1.23 m"` / `"—"`）、`fun formatDegrees(m: RangingMeasurement?): String`（`"-12.5°"` / `"—"`）、`fun formatMs(ms: Long?): String`（`"350 ms"` / `"—"`）
  - `fun requiredRuntimePermissions(oob: OobType): List<String>` — 常に `UWB_RANGING`。BLE: `BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE`。NEARBY: BLE の3つ＋`NEARBY_WIFI_DEVICES`＋`ACCESS_FINE_LOCATION`（Nearby の最新ドキュメントで要否確認し、不要なら除く）。WIFI_AWARE: `NEARBY_WIFI_DEVICES`
  - `fun missingPermissions(required: List<String>, granted: Map<String, Boolean>): List<String>`
  - `object OobTransportFactory { fun create(context: Context, type: OobType, onEvent: (OobEvent) -> Unit): OobTransport }`（この時点では全分岐 `TODO()` 相当の `IllegalStateException("未実装")`、Task 6〜8 で埋める）
  - `RangingViewModel(app, role, oob)`: init で runner を `viewModelScope` で collect し `StateFlow<RangingUiState>` を公開。`stop()` で job キャンセル
- 画面:
  - 起動画面: UWB 非対応なら「この端末は UWB 非対応です」表示。役割（親機/子機）と OOB（`implemented == false` は disabled）の選択、開始ボタン。開始で `RequestMultiplePermissions` → 不足があれば `"権限が不足しています: ..."` を表示して留まる
  - 測距画面: 役割・OOB・状態（＋reason）、4値、計測4項目（OOB 接続確立まで / 交換完了まで / 初回測距まで / 交換完了→初回測距）、ログ（`LazyColumn`、新しい行が下）、停止ボタン（`stop()` して起動画面へ）
  - 画面遷移はナビゲーションライブラリを使わず `MainActivity` 内の状態で切替

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
class RangingUiStateTest {
    private val base = RangingUiState(Role.CONTROLLER, OobType.BLE)
    private val pos = RangingPosition(RangingMeasurement(1.234f), null, RangingMeasurement(-3f), 99L)
    private val dev = UwbDevice.createForAddress(byteArrayOf(1, 2))

    @Test fun positionUpdatesState() {
        val s = base.reduce(ExperimentEvent.Ranging(RangingResult.RangingResultPosition(dev, pos)), 10)
        assertEquals(pos, s.position)
    }
    @Test fun peerDisconnectedKeepsLastPosition() {
        val s = base.reduce(ExperimentEvent.Ranging(RangingResult.RangingResultPosition(dev, pos)), 10)
            .reduce(ExperimentEvent.Status(ExperimentStatus.PEER_DISCONNECTED), 20)
        assertEquals(ExperimentStatus.PEER_DISCONNECTED, s.status); assertEquals(pos, s.position)
    }
    @Test fun failureKeepsReasonAndLogs() {
        val s = base.reduce(ExperimentEvent.Status(ExperimentStatus.FAILED, "x"), 5)
        assertEquals("x", s.reason); assertEquals(5L, s.logs.last().timeMs)
    }
    @Test fun formatting() {
        assertEquals("1.23 m", formatMeters(RangingMeasurement(1.234f)))
        assertEquals("—", formatMeters(null))
        assertEquals("-12.5°", formatDegrees(RangingMeasurement(-12.5f)))
        assertEquals("—", formatMs(null)); assertEquals("350 ms", formatMs(350))
    }
}
class PermissionsTest {
    @Test fun bleNeedsUwbAndBluetooth() {
        assertEquals(setOf(UWB_RANGING, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE),
            requiredRuntimePermissions(OobType.BLE).toSet())
    }
    @Test fun missingPermissionsListsDenied() {
        assertEquals(listOf("b"), missingPermissions(listOf("a", "b"), mapOf("a" to true, "b" to false)))
    }
}
```

- [ ] **Step 2: 失敗を確認** — `./gradlew :app:testDebugUnitTest --tests '*RangingUiStateTest' --tests '*PermissionsTest'` → FAIL
- [ ] **Step 3: reducer・フォーマット・権限・ViewModel・画面・MainActivity・Manifest を実装**
- [ ] **Step 4: 成功を確認** — テスト PASS、`./gradlew :app:assembleDebug` 成功
- [ ] **Step 5: コミット** — `git commit -m "feat: add start and ranging screens"`

---

### Task 6: BLE OOB（最初の実機検証ポイント）

**Files:**
- Create: `app/src/main/java/com/ikemura23/uwb/oob/ble/BleOobTransport.kt`
- Create: `app/src/main/java/com/ikemura23/uwb/oob/ble/BleUuids.kt`
- Modify: `OobTransportFactory.kt`（BLE 分岐）、`AndroidManifest.xml`（`BLUETOOTH_SCAN` は `android:usesPermissionFlags="neverForLocation"`、`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>`）

**Interfaces:**
- Consumes: `OobTransport`, `OobEvent`, `OobCodec`
- Produces: `class BleOobTransport(context: Context, onEvent: (OobEvent) -> Unit) : OobTransport`
  - `BleUuids.SERVICE`, `BleUuids.CONTROLEE_INFO`（WRITE）, `BleUuids.CONTROLLER_INFO`（READ）— アプリ固有の固定 UUID を生成して定数化

GATT のやり取り:
- 親機: `BluetoothGattServer` に SERVICE を追加 → `BluetoothLeAdvertiser` で SERVICE UUID をアドバタイズ。`onConnectionStateChange(CONNECTED)` で `LinkEstablished`。`CONTROLEE_INFO` への write を decode → `build()` → 結果を保持。`CONTROLLER_INFO` の read 要求に保持値を `sendResponse` した時点で `exchangeAsController` が返る
- 子機: SERVICE UUID の `ScanFilter` でスキャン → 最初の結果に `connectGatt(autoConnect=false, TRANSPORT_LE)` → `LinkEstablished` → `requestMtu(64)` → `discoverServices` → `CONTROLEE_INFO` に write → 完了後 `CONTROLLER_INFO` を read → decode して返す
- コールバックは `suspendCancellableCoroutine` / `CompletableDeferred` でつなぐ。各段階を `OobEvent.Log` で流す
- Bluetooth が OFF / アダプタなしは `IllegalStateException("Bluetooth が OFF です")`
- `close()`: アドバタイズ停止・スキャン停止・GATT サーバー/クライアント close

- [ ] **Step 1: 実装**（BLE は単体テスト対象外。実機で検証）
- [ ] **Step 2: ビルド確認** — `./gradlew :app:testDebugUnitTest :app:assembleDebug` 成功
- [ ] **Step 3: 実機検証** — UWB 対応 Pixel 2台に `./gradlew :app:installDebug`。片方「親機・BLE」、もう片方「子機・BLE」で開始。確認: ① 両端末で状態が「パラメータ交換済み」→「UWB 測距中」に進む ② `distance` が端末を動かすと変わる ③ 計測4項目が表示される ④ 停止で相手側が「相手切断」になる ⑤ 片方だけ開始すると 30 秒後に「失敗」。結果（成否・各計測値）をユーザーに報告
- [ ] **Step 4: コミット** — `git commit -m "feat: add BLE OOB transport"`

---

### Task 7: Nearby Connections OOB

**Files:**
- Create: `app/src/main/java/com/ikemura23/uwb/oob/nearby/NearbyOobTransport.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`（`com.google.android.gms:play-services-nearby` の最新安定版）、`OobTransportFactory.kt`、`OobType.kt`（NEARBY を `implemented = true`）、`AndroidManifest.xml`（`NEARBY_WIFI_DEVICES`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、`ACCESS_FINE_LOCATION`（要否確認））
- Test: `PermissionsTest` に `nearbyPermissions` を追加（確定した権限セットを assert）

**Interfaces:**
- Produces: `class NearbyOobTransport(context: Context, onEvent: (OobEvent) -> Unit) : OobTransport`
  - serviceId `"com.ikemura23.uwb.oob"`、`Strategy.P2P_POINT_TO_POINT`
  - 親機 `startAdvertising`、子機 `startDiscovery` → 発見した最初の endpoint に `requestConnection`。両者 `onConnectionInitiated` で自動 `acceptConnection`、`onConnectionResult` 成功で `LinkEstablished`
  - 子機が `Payload.fromBytes(ControleeInfo)` を送信 → 親機が受信・`build` → 返信。親機は自分の payload の `onPayloadTransferUpdate` が SUCCESS になった時点で返る
  - `close()`: `stopAdvertising` / `stopDiscovery` / `stopAllEndpoints`

- [ ] **Step 1: `nearbyPermissions` テストを追加して FAIL を確認**
- [ ] **Step 2: 実装し、テスト PASS・`assembleDebug` 成功を確認**
- [ ] **Step 3: 実機検証** — Task 6 Step 3 と同じ ①〜⑤ を Nearby Connections で確認し、BLE の計測値と並べてユーザーに報告
- [ ] **Step 4: コミット** — `git commit -m "feat: add Nearby Connections OOB transport"`

---

### Task 8: Wi-Fi Aware OOB

**Files:**
- Create: `app/src/main/java/com/ikemura23/uwb/oob/wifiaware/WifiAwareOobTransport.kt`
- Modify: `OobTransportFactory.kt`、`OobType.kt`（WIFI_AWARE を `implemented = true`）、`AndroidManifest.xml`（`NEARBY_WIFI_DEVICES` に `android:usesPermissionFlags="neverForLocation"`、`<uses-feature android:name="android.hardware.wifi.aware" android:required="false"/>`）
- Test: `PermissionsTest` に `wifiAwarePermissions` を追加（`setOf(UWB_RANGING, NEARBY_WIFI_DEVICES)`）

**Interfaces:**
- Produces: `class WifiAwareOobTransport(context: Context, onEvent: (OobEvent) -> Unit) : OobTransport`
  - `WifiAwareManager.isAvailable()` が false なら `IllegalStateException("Wi-Fi Aware が利用できません")`
  - `attach` → 親機 `publish`、子機 `subscribe`（service name `"uwb-oob"`）
  - 子機: `onServiceDiscovered(peerHandle)` で `LinkEstablished` → `sendMessage(peer, id, ControleeInfo)` → 親機からのメッセージ受信で返る
  - 親機: `onMessageReceived(peer, bytes)` で `LinkEstablished` → decode → `build` → `sendMessage(peer, ...)` → `onMessageSendSucceeded` で返る。`onMessageSendFailed` は例外
  - NDP（データパス）は張らない
  - `close()`: discovery session と `WifiAwareSession` を close

- [ ] **Step 1: `wifiAwarePermissions` テストを追加して FAIL を確認**
- [ ] **Step 2: 実装し、テスト PASS・`assembleDebug` 成功を確認**
- [ ] **Step 3: 実機検証** — Task 6 Step 3 と同じ ①〜⑤ を Wi-Fi Aware で確認し、3方式の計測値を並べてユーザーに報告
- [ ] **Step 4: コミット** — `git commit -m "feat: add Wi-Fi Aware OOB transport"`
