# バックグラウンド更新

[English](background-update.md)

## プラットフォーム方針

| プラットフォーム | 更新 | 実装 |
| --- | --- | --- |
| Windows / macOS / Linux | ✅ 指定間隔で確実に実行 | コルーチンによる周期ループ（現行） |
| Android | ✅ 概ね指定間隔（Doze / App Standby の影響を受ける） | `WorkManager` の `PeriodicWorkRequest`（現行） |
| iOS | ⚠️ OS が実行タイミングを判断 | BGTaskScheduler（予定） |

## デスクトップ実装（`desktopMain/main.kt` + `StartupTasks.kt`）

`main()` でアプリスコープのコルーチンを起動し、`refreshIntervalMinutes` の間隔でループする。以下は
要約で、各周回のエラー処理と、独立した間隔で走るアップデート確認は省略している。`backgroundUpdateLoop`
自体はデスクトップ専用（単純なコルーチンループ。Android 側の対応物は `WorkManager` の
`PeriodicWorkRequest` — 上のプラットフォーム方針の表と後述の「Android 実装」を参照）だが、毎周回
呼び出す3関数 `refreshFeedsAndNotify` / `checkForUpdateAndNotify` / `maybeRebuildFtsIndex` は
プラットフォーム非依存で commonMain の `domain/StartupMaintenanceTasks.kt` にあるため、Android 側の
worker は重複実装せず同じ実装を呼んでいる。

```kotlin
while (true) {
    val minutes = settings.refreshIntervalMinutes
    delay(if (minutes <= 0) 60_000L else minutes * 60_000L)  // 「手動」（minutes <= 0）は 1 分ごとに起床
    if (minutes > 0) {
        refreshFeedsAndNotify()   // 全フィード更新（ETag / Last-Modified 差分取得）→ 新着があり通知が
                                  // 有効なら NewArticleNotifier.notifyIfEnabled(...)
        sync()                    // クラウド同期
    }
    maybeRebuildFtsIndex()        // FTS 全再構築の日次 heal（後述）
}
```

- 設定間隔は毎ループ読み直すため、設定変更は次サイクルから反映される（明示的な再スケジュール不要）。
- 更新中のエラーはクラッシュさせず、通知センターに記録する（`FeedRepository.refreshFeed` 内で処理）。Android ではさらに Snackbar でも通知するが、アプリのウィンドウが実際にフォーカスを持っている間に限る。そのため `FeedRefreshWorker` がバックグラウンドで積んだものは保留され、ユーザーが戻ってきた時点で通知される（見られないままタイムアウトすることがない）。[error-design.ja.md](error-design.ja.md) の「通知センター」を参照。
- 新着通知は同じ `NewArticleNotifier.trayEvents` を入力として、`KeryxTray` 自身の振り分け順にしたがって
  プラットフォームごとに 4 経路で OS へ渡す（`TrayState` は Compose の `application {}` スコープ内でしか
  作れないため、`MutableSharedFlow` で橋渡しする）。macOS の `MacTray` と Windows の `WindowsTray` は
  どちらも `TrayIcon.displayMessage` を呼び、StatusNotifierItem ホストがある Linux は `LinuxTray` の
  `org.freedesktop.Notifications.Notify` を使う。残る唯一のケース——SNI ホストの無い Linux——だけが
  Compose 自体の `Tray()` コンポーザブルにフォールバックし、その `TrayState.sendNotification` を使う。
  詳細は [app-architecture.ja.md](app-architecture.ja.md) の「デスクトップトレイ」を参照。

## Android 実装（`androidMain/background/` + `AndroidStartupTasks.kt`）

`KeryxApplication.onCreate` から `startBackgroundRefresh`（`background/BackgroundRefresh.kt`）を
呼ぶ。これはプロセスが生きている間ずっと `SettingsRepository.localSettings` の
`refreshIntervalMinutes` を監視し、`WorkManager` の一意な周期ジョブ（`"feed_refresh"`）をその値に
同期し続ける — そのため設定変更は再起動なしに即座に反映される。設定値からスケジュールへの写像は
純粋関数 `domain/BackgroundRefreshSchedule.kt` の `backgroundRefreshSchedule`（commonMain に置き、
単体テスト済み — このモジュールには Android 固有クラスをテストする `androidUnitTest` ソースセットが
無いため）: 「手動」（`<= 0`）はジョブを完全にキャンセルし、`WorkManager` 自体の最短間隔
（`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`、15分）を下回る正の値はそれに切り上げる
（無効化はしない）。アプリの UI 自体は15分未満の値を提示しないため、これは手動編集や移行された
`local_settings.json` の場合にのみ関係する。

`background/FeedRefreshWorker.kt`（`CoroutineWorker`。`WorkManager` 自身の `WorkerFactory` が
リフレクションでインスタンス化するため、依存関係はコンストラクタ注入ではなく `doWork()` 内で
`KoinPlatform.getKoin()` から解決する）は、デスクトップの `backgroundUpdateLoop` が毎周回実行する
のとまったく同じ手順を実行する: `refreshFeedsAndNotify` → （`CloudSession.isConnected()` が真なら）
`SyncRepository.sync(SyncTrigger.AUTOMATIC)` → `shouldCheckForUpdate` が true の場合のみ `checkForUpdateAndNotify` → `maybeRebuildFtsIndex`。
Android の `CloudSession` は Dropbox と OneDrive に加え、Play 開発者サービスが利用できる環境でのみ
Google Drive も持つ（[sync-architecture.ja.md](sync-architecture.ja.md) の
「Android での Google Drive」参照）ため、ユーザーがそのいずれとも連携していない場合、あるいは
連携済みでも `autoSyncSuspended` が真の間（直前の `CloudDataIncompatibleException` により、リセットまたは手動同期成功まで
`SyncTrigger.AUTOMATIC` の試行がゲートされる状態。`SyncRepository.sync` 自身の KDoc 参照）は
`sync()` が本当の no-op になる。捕捉した例外（`sync()` 自身の `Result` 型ではなく予期しない失敗）は
`Result.retry()` を返し、リトライは `WorkManager` 自身のバックオフ方針に委ねる。`sync()` が
`Result.Err` を返した場合は別途扱われ、`error-design.md` のオートリトライ表がリトライ可能と定める
分類（`CloudStorageException`）に限り `Result.retry()` とし、リトライ不可と定める恒久的な失敗
（`CloudAuthException`/`SchemaVersionException`/`CloudDataIncompatibleException`）は次回の
定期実行に委ねる。

`MainActivity.onCreate` から `runAndroidStartupTasks`（`AndroidStartupTasks.kt`）を呼ぶ —
デスクトップの `runStartupTasks` に相当するが、macOS 固有の translocation 警告（Android には該当
概念がない）を除く。5つのステップをそれぞれ独立して実行する（`runMaintenanceStep`）ため、1ステップが
例外を投げても（例: `maybeRebuildFtsIndex` が `FtsManager` の `busy_timeout` に達する場合）残りの
ステップをスキップさせない:

1. `cleanUpArticleCacheIfDue`（後述）。
2. 初回クラウド同期——デスクトップの `runStartupTasks` と同じ位置・同じゲート。
3. `refreshFeedsAndNotify`
4. `checkForUpdateAndNotify`
5. `maybeRebuildFtsIndex`

（ステップ3〜5は `FeedRefreshWorker` が実行するのと同じ3関数——後述——から、ステップ2で既にカバー済みの
同期処理を除いたもの。）

これは意図的に `Application.onCreate` ではなく **Activity** 側に置いている:
後者は `WorkManager` が `FeedRefreshWorker` を実行するためにプロセスを起こしたときにも走るため、
バックグラウンド起床のたびに起動時処理一式を実行すると、Worker 自身が直前に行った更新/同期/更新確認/
FTS 処理と重複してしまう。プロセス内ガード（`startupTasksRan`）により、画面回転など Activity だけが
再生成される設定変更で `onCreate` が再度走ってもプロセス内で1回に保たれる。

**ガードはすべてのステップを一通り試行し終えた後にのみセットされ、その前ではない。**
セットアップが未完了、または `FeedRefreshWorker` がメンテナンスロックを保持中という理由で早期に
return した呼び出しは、`FeedRefreshWorker` 自身が実行しない `cleanUpArticleCacheIfDue` を
このプロセスで実行する唯一の機会を消費しない。

新着記事の OS 通知は `domain/OsNotificationSink.kt`（`fun interface`、
`post(message: String, count: Int)`）経由で届く。Android は
`platformModule` でこれを `platform/AndroidNotificationSink.kt`（`NotificationManagerCompat` で
投稿する実装）に束縛しており、デスクトップの `NewArticleNotifier.trayEvents` を購読する経路とは
別系統になっている（理由はそのクラス自身の KDoc を参照: `WorkManager` に起こされたプロセスでは、
更新が終わった時点で `trayEvents` の購読者が既に張られている保証が無い — `trayEvents` は replay 0 で、
購読者がいない間に発行されたものは黙って捨てられるため。デスクトップ自身の束縛は同じ理由で no-op に
なっている）。`AndroidNotificationSink` は投稿のたびに
`NotificationManagerCompat.areNotificationsEnabled()` でガードしており、これ1回で Android 13+ の
`POST_NOTIFICATIONS` ランタイム権限とユーザーによるアプリ/チャンネル単位のブロックの両方をカバーする。
権限自体は `platform/NotificationPermission.kt` の `rememberNotificationPermissionRequester` で
リクエストし、起動時に1回（`App.kt`。ユーザー自身の「通知を有効にする」設定が既に ON の場合）と、
`NotificationsTab` でその設定を ON にしたときの両方で呼ぶ。ユーザーがシステムダイアログを2回目に
拒否（「今後表示しない」）した後は、Android 自身がそれ以降のプログラムからのリクエストに対して
ダイアログを表示しなくなる — 設定のトグル自体は ON のままにしておいてよいが、ユーザーが OS の設定から
直接許可するまで通知は届かない。この場合に「端末の設定を開いてください」と誘導するフローは今回は
作っていない。

投稿する通知の小アイコンは `composeApp/src/androidMain/res/drawable/ic_stat_keryx.xml` —
`design/icons/svg/app_icon_foreground.svg` から手作業で変換した、Keryx ロゴマークのモノクロ・
アルファのみのシルエット VectorDrawable（VectorDrawable には `<rect>`/`<circle>` に相当する要素が
無いため変換が必要だった）で、`:composeApp` 自身の `androidMain/res/`（`works.merc.keryx.app.R` を
生成する通常の AGP リソースディレクトリで、Compose Multiplatform 自身の `composeResources/` とは
別物）に置かれている — `:composeApp` は `:androidApp` のリソースに依存できないため、
ランチャーアイコンと同じ `androidApp/src/main/res/` には置けない。`OsNotificationSink.post` に渡す
`count` パラメータは `NotificationCompat.Builder.setNumber` に転送しており、これが影響するのは
ランチャーアイコンの長押しメニューに出る件数だけで、**アイコン自体に描かれる数字ではない**。
アクティブな通知と独立してアプリアイコンのバッジ数を設定する API は Android に存在しない（iOS の
`setApplicationIconBadgeNumber` に相当するものが無い）ため、デスクトップの `IconBadge.kt`
（`drawUnreadBadge` — 総未読数を Dock/タスクバー/ウィンドウアイコンに直接合成する）とは異なり、
Android は完全に OS 自身の通知ドット（未読数ではなく、通知が現在アクティブかどうかに連動）と、
上記の長押し件数だけに頼っている。これは埋めるべきギャップではなく意図的な非対称である —
アイコンレベルのバッジを維持するためだけに、消せない通知を出し続けることは Android 自身の通知
モデルに反する。ユーザー向けの要約は `external-spec.ja.md` §7 を参照。

アプリ内の「アップデートを確認」（`checkForUpdateAndNotify` と設定の「アップデート」タブ）は
`platform/SelfUpdateCheck.kt` の `selfUpdateCheckSupported` でゲートしている。これは
`core/UpdateDistribution.kt` の `isSelfUpdateCheckSupported` に自アプリのインストール元パッケージ名を
渡した結果を使う（`com.android.vending` / 旧 `com.google.android.feedback` → 無効、それ以外
（`null` を含む）→ 有効。デスクトップの「常に有効」と同じ既定値）。これは Google Play のポリシー要件
ではなく UX 上の判断である — Play が禁じているのはアプリが Play 以外の方法で**自身を置換すること**と
Play 以外からの実行可能コードのダウンロードであり、この機能はどちらも行っていない。理由は、Play は
既にアプリを自動更新しているため、そこに GitHub 版の更新導線をもう一つ並べるとユーザーがどちらを
使えばよいのか混乱するからである。

## アプリ内アップデート

`selfUpdateCheckSupported` が確認そのものを提供している場所では、`domain/UpdateRepository`
（Koin の `single` なので、これ自身も進行中のダウンロードも設定ダイアログを閉じても生き続ける）が
「リンクを出すだけ」からさらに進めて、実際のダウンロードとインストールまでを、1 本の
`StateFlow<UpdateState>` の背後で駆動する: `Idle → Checking → (UpToDate | Available) →
Downloading → Verifying → Ready → Installing`、そして `Checking`/`Downloading`/`Verifying`/
`Installing` から `Failed` へも遷移しうる（`retryFailed` は `Installing` 段階での失敗を
`retryInstall` に分岐させる。これは `UpdateStage` の4つの値 `CHECK`、`DOWNLOAD`、`VERIFY`、
`INSTALL` と対応する——`Failed` に一切到達しない Android のインストール失敗ケースについては
後述）。Updates 設定タブ、デスクトップトレイとアプリメニューの Help メニューが
共有する唯一のアップデートメニュー項目（どちらも `tray/UpdateMenuEntry.kt` の `updateMenuEntry`
が解決する）、通知センターのベル——すべてが同じこの状態を読むので、いま何が起きているかについて
食い違うことはありえない。**どの段階も無人では進まない**: 確認が自動的にダウンロードを始めること
はなく、ダウンロード済みファイルが自動的にインストールされることもない——ダウンロードもインス
トールも、それぞれ個別の明示的なクリックである（Updates タブのボタン、あるいはそのメニュー
項目）。

- **どのファイルを、どうするか。** `UpdateChecker` が GitHub リリースの `assets[]` をパースし、
  `domain/UpdateAsset.kt` の `selectUpdateAsset` がこのビルドのインストール形態に合致するものを
  1 つ選ぶ（GitHub がまだ処理を終えていないアセットや、検証可能な `sha256` digest を持たない
  アセットは絶対に選ばない——詳細は下記「完全性検証」）——`.aab` はどの `UpdateAssetKind` の
  サフィックスにも一致しないため、そもそも候補になることがない。Linux は2つ以上のアーキテクチャで
  配布される唯一のプラットフォームなので、そのアセットサフィックスだけはアーキテクチャ別に選ばれる
  （`platform/PlatformOs.kt` の `hostArchitecture`。デスクトップでは `os.arch` から求める——
  `-linux-x86_64.zip` / `-linux-arm64.zip`）。このプロジェクトが対応するアセットを持たない
  アーキテクチャ（`HostArchitecture.UNKNOWN`）では、`selectUpdateAsset` は実際にそのアセットが
  存在しない場合と同じく「見つからない」を返す——一番近いものを推測することはない。macOS
  （arm64 専用）と Windows（x86_64 専用）のアセット名は `hostArchitecture` に関わらず固定のまま。
  `selectUpdateAsset` にはさらに2つのガードがある——正当な GitHub リリースが返すことはあり得ないが、
  破損または悪意あるレスポンスへの耐性として存在するものだ: `assetNamePattern` は**完全一致**
  （`^Keryx-[A-Za-z0-9._+-]+<suffix>$`）を要求し、`/` や `\` を含む名前を無害化するのではなく
  拒否する——これは `UpdateAsset.name` が `<cacheDir>/updates/<version>/` 配下のパス要素になるため、
  チェックしない名前はパストラバーサルの経路になり得るという意味で本質的な安全策である。また
  `sizeBytes` は `1..MAX_PLAUSIBLE_UPDATE_ASSET_SIZE_BYTES`（1 GiB）の範囲でなければならず、
  ありえない大きさの報告値による、上記の空き容量計算のオーバーフローを防ぐ。
  続いて `domain/UpdateInstallPolicy.kt`
  の `updatePlan` が、そのアセットに対して実際に何をすべきかを、インストール場所
  （`platform/InstallLocation.kt` の `detectInstallLocation()`——macOS の `.app`、Windows/Linux の
  portable ZIP、Windows の MSI インストール、Android のサイドロード、……）と、すでに選択済みの
  アセット（無ければその不在）だけから、ネットワークにもファイルシステムにも一切触れず純粋に決める:
  `SelfReplace`（その場でファイルを置き換えて再起動）、`RunInstaller`（OS 自身のインストーラーへ
  引き渡す）、`OpenReleasePage`（この形態はその場で更新できない——Linux の deb/rpm インストール、
  Linux の Snap インストール（`InstallKind.LINUX_SNAP`。snapd が設定する環境変数 `SNAP` で検出する
  ——`/snap/keryx/<revision>/` マウントは読み取り専用のため、配布経路によらず自己置換は不可能）、
  macOS App Translocation、書き込めないインストール先、このリリースに合致するアセットが無い、
  など）、`NotOffered`（開発実行、または Google Play 経由でインストールされた Android ビルド）。
  `di/AppModule.kt` は `InstallLocation` をちょうど 1 回だけ、`UpdateChecker`/`UpdateRepository`/
  デスクトップの `UpdateInstaller` が共有する Koin `single` として解決する——それぞれが自前の
  コンストラクタ既定値 `detectInstallLocation()` を呼ぶに任せない。`parentWritable` は実際に
  ファイルシステムを探る（一時ファイルを作って消す）ため、3 つの独立したライブプローブが互いに
  食い違いうるし、起動経路で 3 回繰り返すこと自体も無駄になる。この帰結として——プロセス起動時に
  このプローブが読んだ値（とりわけ `translocated`/`parentWritable`）は、そのプロセスの寿命いっぱい
  凍結される。変わるのは再起動をまたいだときだけで、セッション中に変わることはない。この決定の中で
  実行中のプロセス内で正当に変わりうるのは `check()` が見るアセットだけであり、これはまさに
  下記のリリース監視の仕組みが前提にしていることでもある。`UpdateInstaller.canInstall(plan)` は
  これとは別の、より狭い問いにプラットフォームの `actual` が
  実行時に答えるもの——「何をすべきか」ではなく「この実行環境に今それが許されているか」
  （典型的には Android の「提供元不明のアプリ」同意）——であり、ダウンロードを始めるかどうかを
  ゲートする。`check()` は更新が見つかるたびにこれを一度解決し、`AvailableUpdate.installable` に
  畳み込む——Updates タブとトレイはどちらも `plan` 自身の `isInstallable` ではなくこちらを読む。
  `plan` は `SelfReplace`/`RunInstaller` を指していてもプラットフォームが拒否している場合があり、
  両サーフェスとも「ダウンロード」を押して実際に何か起きるかについて `startDownload()` 自身の
  ゲートと食い違ってはならないため。
- **まだアセットが無いリリースは `OpenReleasePage` ではなく「ここではまだリリースされていない」
  として扱う。** リリースワークフロー（`.github/workflows/release.yml`）は `release: published`
  で起動し、各プラットフォームのパッケージはそのあとで添付される: GitHub のリリースは
  `assets[]` が空のまま一瞬公開され（本リポジトリでの実測では数分間）、その後すべてのパッケージが
  揃う。`UpdateChecker` 自身はこれを特別扱いしない——`selectUpdateAsset` が見つけた結果
  （その窓の間は `null`）をそのまま報告するだけ——なので、`domain/UpdateInstallPolicy.kt` の
  `awaitsReleaseAsset(location, asset)` が「ここでこのインストール形態が `OpenReleasePage` に
  落ちる理由が、アセットが無いことだけなのか」を問う唯一の場所になる（`updatePlan` 自身の
  `when (location.kind)` を鏡写しにしているので、`InstallKind` が増えたときは両方が答えを
  持たねばならない）。自己置換／インストーラー駆動の形態で、他に問題が何もない場合
  （translocated でない、書き込み可能、……）にのみ真になる。恒久的な理由でアセットが無い場合
  （Linux の deb/rpm、Linux の Snap、アセットはあるがプラットフォームが拒否している——Android の
  「提供元不明のアプリ」同意など）は偽のままで、この機能が入る前と変わらず即座に通知する。

  `domain/UpdateState.kt` の `nextStateAfterCheck` は、真と判定された場合を `UpdateStatus.UpToDate`
  と同じ分岐に畳み込む——`Available(installable = false)` は一切作られないので、`check()` 自身の
  通知ブロック（`after is UpdateState.Available` でゲートされている）は発火せず、トレイと Updates
  タブは「手動更新が必要」ではなく「最新」として読む。続いて `UpdateRepository` は
  **リリース監視**を開始（または継続）する: `runCheck(quiet = true)` が
  `UPDATE_RELEASE_WATCH_INTERVAL_MS`（30 秒——実測の空白は数分なので、アセットが揃ってすぐ
  これで捕まえられる）ごとに、`UPDATE_RELEASE_WATCH_MAX_ATTEMPTS`（360 回、約 3 時間）まで
  再チェックする。カウンタはバージョンごとではなく単一の値で追跡する——監視の途中でリリースが
  手動で取り消されて作り直される（タグを消して push し直す）こともあるため、`UpdateStatus.UpToDate`
  （取り下げ）と `UpdateStatus.Failed`（一時的なネットワーク障害）はどちらも進行中の監視を
  **継続**させ、終わらせない。監視が終わるのはアセットが実際に現れたとき、または予算を使い切った
  ときだけ（`recordWatchOutcome`——終わるときは理由を問わずカウンタを必ずリセットするので、通常
  スケジュールに戻った後の無関係な `UpToDate` を「まだ監視中」と誤読することはない）。`quiet` が
  これを見えないものにしている要——30 秒ごとに `UpdateState.Checking` が点滅することはなく、
  quiet な再チェック自身の一時的な失敗が `UpdateState.Failed` として表面化することもない。どちらも
  ユーザーが実際に依頼したチェックのためのものである。予算を設けているのは、一部の組み合わせでは
  絶対に解決しないため——例えばプレリリースタグに対する MSI インストール版は、`release.yml` が
  プレリリースでは `packageMsi` を丸ごとスキップするので該当する。その環境は通常の
  `updateCheckIntervalHours` スケジュールへフォールバックする（その環境の視点からは知らせるべき
  インストール可能なものが何も無いままなので、通知はどちらにせよ出ない）。

  `UpdateRepository` 自身の `checkMutex` が `runCheck` へのすべての呼び出しを直列化している——
  quiet なポーリングとユーザーが依頼した `check()` のネットワーク呼び出しが同時に進行することは
  なく、先に始まったチェックが（その時点で古くなった）結果を後から始まったチェックより後に
  適用してしまうことはない。

  `nextStateAfterCheck` はまた、`Ready` なダウンロードを「すでに手元にある同じもの」として
  扱い続ける条件に、バージョンだけでなくアセットの digest 一致も要求するようになった——さもないと、
  同じタグの下でリリースが作り直された場合（手作業でのアップロードやり直しなど）、古い、
  検証済みのファイルをインストーラーに渡してしまい、作り直された方を取りに行かなくなる。
- **ダウンロード。** 1バイトも取得する前に、`hasEnoughFreeSpaceForUpdate`（`UpdateRepository.kt`）が
  キャッシュディレクトリの空き容量を、アセットサイズに `REQUIRED_FREE_SPACE_MULTIPLE`（余裕を見て 3）を
  掛けた値と比較する。これを満たさなければ、ネットワークリクエストを一切行わずに
  `Failed(UpdateException(UpdateStage.DOWNLOAD, "Not enough free disk space"))` へ直行する。
  `data/remote/UpdateDownloader` は手動でリダイレクトを追う（共有 HTTP
  クライアントにはリダイレクトプラグイン自体が入っていない）。小さなホスト allowlist——
  完全一致の `github.com` と `api.github.com`（Releases API 自身が応答するホスト）、および
  先頭ドット必須のサフィックス一致 `.githubusercontent.com`（署名付きアセットのリダイレクト先。
  実例: `release-assets.githubusercontent.com`。先頭ドットにより `evilgithubusercontent.com` は
  決して一致しない）——を毎ホップ再検証し、`https` 以外は不可、`MAX_REDIRECTS` で頭打ちにする。
  ファイルは `<cacheDir>/updates/<version>/` 配下の `.part` パスへストリーミングされ、実サイズと
  SHA-256 の両方がリリース自身の値と一致して初めて最終的な名前へ原子的にリネームされる——
  「最終名が存在する＝検証済み」がパイプライン全体の不変条件になっている。1 リクエストごとの
  タイムアウト上書きが、共有クライアントの（もっと短い）通常のリクエストタイムアウトを置き換える
  — アップデートアセットは 100MB 超になりうるため。リクエストは素の `client.get()` ではなく必ず
  `prepareGet(…).execute { … }` を通す。これは書き方の好みではなく必須で、Ktor は既定で
  `SaveBody` プラグインを入れており、素の `get()` が返る前に**本文全体をメモリに読み切って
  しまう**——実際にこれが原因で、進捗バーが転送中ずっと 0% のまま固まり、終わった瞬間に完了へ
  飛んでいた（おまけに 100MB 超を RAM に載せていた）。Ktor 3.5 の `skipSavingBody()` は
  deprecated な no-op なので、streaming 構文以外に回避手段は無い。進捗は整数パーセントが
  変わるたびにのみ発火するよう間引かれる（`shouldEmitProgress`——固定バイト幅ではなくパーセント
  自体をゲートにしているのは、どの消費側もそれより細かい解像度は表示できず、かつアセットサイズに
  よらず正しくスケールするため——これとは別に、より粗い 5% 刻みへの丸めがトレイメニューのラベル側で
  下流に発生する。下記「表示」参照）、キャンセルは `Failed` ではなく `Available` に戻す——ユーザー起因の
  中断は失敗ではない。中断からの再開は無い: リダイレクト先は約 1 時間で失効する署名付き URL の
  ため、失敗／キャンセルしたダウンロードは再開せず単にやり直す。`check()` は
  `<cacheDir>/updates/` も掃除し、現在の状態が参照しているバージョン以外（進行中の `.part` と
  `Ready` ファイルは保護する）をすべて削除するので、このリポジトリが参照しなくなったバージョンが
  ディスク上に無限に溜まることはない。
- **インストール。** 2 つのプラットフォーム別 `UpdateInstaller` は手法を共有していない:
  - **デスクトップ**（`platform/update/DesktopUpdateInstaller.kt`）:
    - **展開・検証・ステージング・引き渡し。** `platform/update/ArchiveExtractor.kt` 経由で自己置換用の
      ZIP をステージングディレクトリへ展開し（zip slip の拒否、エントリ数とバイト数の上限。シームに
      なっている理由は下記「展開がシームになっている理由」参照）、ヘルスチェックをかけ（実行ファイルが
      存在すること、macOS では `Info.plist` のバージョンとバンドル自身のコード署名も一致すること
      ——`codesign --verify --strict --deep`）、展開結果を現在のインストール先と同じボリュームへ
      移動し（スワップが単なる rename になるように）、detached ヘルパースクリプト
      （`platform/update/UpdateScriptWriter.kt`）を `ProcessLauncher` 経由で起動する。アプリが
      終了するのは、この引き渡しが実際に完了してから——インストーラーが `Launched` を返し、それを
      受けて `UpdateRepository` が `installLaunched` シグナルを流し、`main.kt` がそれを受け取った
      時点のみ。
    - **`Installing` を理由に終了しない理由。** この state はインストール開始の瞬間、まだ展開の最中に
      立つので、これで終了するとスクリプトが書き出される前にプロセスが死ぬ。
    - **スクリプトの形。** OS を問わずスクリプトの形は同じ: このプロセスの PID が終了するのを待ち、
      実行中のインストールを**退避**させ（`mv`。決して先に削除しない）、新しい方を**配置**し、
      それを**検証**し、途中で失敗すれば退避したコピーへ**ロールバック**する——これによりスワップ
      途中のクラッシュがインストール先を空にしてしまうことは無い。
    - **後始末。** この退避が常に素の `mv` であって削除してから移動するのではない以上、
      `DesktopUpdateInstaller` は新しい試行をステージングする直前に `.new` ステージング先と `.old`
      退避先の両方をあらかじめ消す（前回の試行がスクリプト実行前に失敗して残した `.old` が残っている
      と、この `mv` は上書きではなく入れ子になってしまう）。`extracted/` ステージングディレクトリを
      最初に消すのは別の理由による: *展開中に* kill された試行は部分ツリーを残し、どちらの展開器も
      既存の展開先を置き換えずマージするので、これを消さないと再試行したインストールが2つの
      バージョンを1つのバンドルに混ぜてしまう。さらに `cleanUpStaleSelfReplaceArtifacts` が残る
      2つを毎回の起動時に掃除する——これは無条件に安全: この行に到達している時点で、現在の
      `appRoot` はこのプロセス自身が動いている生きたインストールであり、スワップ途中のスクリプトが
      そのような状態を残すことは無いため。
    - **プラットフォーム別の振り分け。** Windows の MSI インストール済みビルドの場合は、代わりに
      PID の終了を待ってから `msiexec /i ... /passive /norestart` を実行するスクリプトを起動する
      （WiX の固定 `upgradeUuid` により、これは新規インストールではなく MajorUpgrade になる）。
      どちらの結果になっても exe パスに最終的に存在する方を再起動する——UAC を拒否した場合や
      アップグレードが失敗した場合でも、何も動いていない状態にはせず、元の動作していたインストール
      を再起動する。Linux の deb/rpm インストールは自己置換の対象に一切ならない（上記の
      `updatePlan` が既に `OpenReleasePage` へ振り分けている）——GUI から `pkexec`/`sudo` を呼び、
      失敗時の回復手段も無いという構成はリスクに見合わないと判断した。Linux の Snap インストールも
      同じ扱いになるが、理由はより単純で、`/snap/keryx/…` マウントが読み取り専用の squashfs
      イメージだからである——アプリ内アップデートが書き込みたくても書き込む先が無い。`release.yml`
      の `package-snap` ジョブは `SNAPCRAFT_STORE_CREDENTIALS` が設定されていれば Snap Store への
      公開を行う（`build.md` 参照）。Store からインストールされた snap は、このアプリ自身の
      アップデート経路とは無関係に、snapd 自身のバックグラウンド自動リフレッシュの恩恵を受ける。
      それでも `LINUX_SNAP` インストールはストアでの公開状況に関わらずすべて変わらず
      `OpenReleasePage` に振り分けられる——GitHub Release の添付ファイル（Store 公開物と同一の
      `.snap`）を `--dangerous` でサイドロードした場合は自動リフレッシュされず、`InstallLocation`
      は実行時にこの二つを区別する手段を持たないためである。
    - **展開がシームになっている理由。** **署名済みの** macOS バンドルはそもそもインプロセスで展開
      できないから: `CodeResources` は同梱 JDK の legal ディレクトリにある43個のシンボリックリンク
      を*リンクとして*封印しており、`java.util.zip` には格納されたリンクと通常ファイルを見分ける
      手段が無い——リンク先を中身に持つ通常ファイルとして書き出してしまい、上記の `codesign` チェック
      が毎回必ず落ちる。そのため macOS は `ditto -x -k`（`DittoArchiveExtractor`）で展開し、その前に
      `ZipExtractor.validate` を通す。`ditto` 自身は上限を持たないので、zip slip・エントリ数・
      展開後サイズのガードをこれで維持する。そしてこれが働く唯一の上限である: `ditto` に対する制約
      は300秒の上限だけで、それを超えると子プロセスを強制終了し、インストールはハングせず終了
      ステータスを理由に含めて失敗する。`validate` はサイズ上限を全エントリの解凍・破棄によって
      確定するので、macOS のインストールは書庫を2回解凍する（190MB級のバンドルで+1〜2秒程度、
      しかもユーザーが自分で開始した経路）。これは意図的である: ローカルヘッダの申告サイズは欠落
      し得るため、セントラルディレクトリから読むのは、細工された書庫に耐えることが目的の上限に
      ついて書庫自身のメタデータを信用することになる。また `ditto` と並走させると、拒否された書庫
      がディスクに何も残さないという性質を手放すことになる。
    - **`ditto` 実行後もまだ確認が必要なもの。** `validate` で確認できないのは格納されたリンクの
      *リンク先*（同じ `java.util.zip` の限界）なので、展開後に `DittoArchiveExtractor.verifyExtractedTree`
      がツリーを走査し、**ファイルシステム経由で**解決して展開先の外に出る symlink を拒否する。
      字句的な解決では不十分で、別の symlink に続く `..` はリンク先ではなくリンク自身に対して
      畳まれてしまう。`ditto` はこのガードではない（リンクを*たどらない*だけで、外を指すリンクを
      *作らない*わけではなく、作ったうえで exit 0 になる）。`ditto` が担うのは `..` を含むエントリ
      *名*を展開先へ正規化することで、これは展開先の*外*に書かれたものに対する唯一の防御である
      （展開先から始まる走査では見えないため）。`codesign` チェックは*脱出*に対する防御線としては
      まったく数えていない — バンドルディレクトリのみを検査するので、バンドルの隣に書かれた
      エントリは一度も見られない（[SECURITY.ja.md](../SECURITY.ja.md) 参照）。これらはすべて、
      書庫が既に通過している SHA-256 digest 照合の上に積み重なる。
    - **Windows / Linux。** アプリイメージに署名が無く、リンクが潰れても無効化されるものが無いため、
      インプロセスの経路（`InProcessArchiveExtractor` → `platform/ZipExtractor.kt`。呼び出し側が
      指定したエントリだけ実行ビットを復元する）のままにしている。それらの `legal/` のリンクは
      アプリ内アップデート後に相対パスを中身に持つファイルへ潰れるが、これは見落としではなく
      受容している: license テキストであり、アプリが実行するパスには決してないためである。
      ステージングの移動にも同じ要件と同じ罠がある: `FileSystemExtras` のボリューム跨ぎフォール
      バックは `NOFOLLOW_LINKS` 付きでコピーする。`Files.copy` も `Files.isDirectory` も既定で
      リンクをたどるため、そうしないとキャッシュとインストール先が別ボリュームにあるインストールで
      リンクがそのまま潰れてしまう。
  - **Android**（`platform/update/AndroidUpdateInstaller.kt`）はダウンロードした APK を
    `PackageInstaller` セッションへストリーム書き込みしてコミットする。以降は OS が引き継ぐ（自前の
    インストール確認を表示し、成功時にはこのプロセス自身を kill してくれるので、こちら側で何かする
    必要は無い）。Install をクリックした時点で `canRequestPackageInstalls()` が false の場合
    （宣言はされているがまだ許可されていない、あるいはダウンロード開始後に取り消された場合）、
    アプリは即座に失敗させる代わりに「提供元不明のアプリ」システム設定画面を開き、state を
    `Ready` のままにしておく——同意が得られた後の再クリックで再試行できるようにするため。
    `REQUEST_INSTALL_PACKAGES` は `github` distribution flavor のマニフェストにのみ宣言されており
    （`androidApp/build.gradle.kts` の `flavorDimensions` — `build.ja.md` 参照）、Google Play へ
    提出する方には含めない——Play のポリシーがこの権限を「他アプリのインストールを主目的とする
    アプリ」に限定していることと、Play が既にアプリ自身を更新してくれることの両方が理由。これを
    駆動するゲートである `canInstallAndroidApkUpdate`（`domain/UpdateInstallPolicy.kt`）は、どの
    flavor でビルドされたかで分岐するのではなく、**マージ済みマニフェスト**の権限を
    `canRequestPackageInstalls()` 経由で読む——`play` flavor の APK が Play を経由せずサイドロード
    される経路（Play Console のテスト配布、`bundletool`、社内配布）が実在し、そこでも正しく拒否
    しなければならないため。セッションをコミットした後の終端的な失敗（特に
    `STATUS_FAILURE_INCOMPATIBLE` ——このインストールと上書き対象の署名鍵の不一致。`build.ja.md`
    の Play App Signing の項を参照）はログには残すが `UpdateRepository.state` へは反映しない——
    コミット時点で既に同期的に `Installing` へ遷移済みであり、後から届く非同期の結果を反映する
    経路が無いため。成功時には OS がプロセスを先に kill するのでこの分岐に到達すること自体が無く、
    この既知の狭い UX 上のギャップは、到達しえないケースのためにレイヤーをまたぐコールバックを
    増やすよりも許容する、という判断である。
- **提示のしかた。** 準備ができてからではなく、`check()` が何かを見つけた時点から提示する:
  唯一のアップデートメニュー項目——デスクトップトレイとアプリメニューバーの Help メニューに
  同一のものが出る。どちらも `tray/UpdateMenuEntry.kt` の `updateMenuEntry` が組み立てる——は、
  `state` が動くのに合わせて
  「アップデート %1$s をダウンロード」「ダウンロード中… N%」（上記「ダウンロード」で述べた同じ進捗を、
  ここではさらに粗く 5% 刻みへ丸めたもの——Linux SNI の D-Bus メニューをレイアウト変更シグナルで
  溢れさせないために必要）、「検証中…」、
  「再起動して %1$s にアップデート」、「アップデートに失敗しました」と切り替わる（`%1$s` は対象
  バージョン——`strings.xml` の `tray_update_download`／`tray_update_restart` 参照）。
  この項目は状態によらず**常に存在する**: `Idle`／`UpToDate` では「更新をチェック」／
  「最新版です」となり、ユーザーが任意のタイミングで確認を要求する手段になる（クリックすると
  `check()` を実行し、インストール可能なアップデートが見つかれば Updates タブを開く——`main.kt` の
  `onUpdateMenuItemClicked` と `tray/TrayActionPolicy.kt` の
  `shouldOpenSettingsAfterUpdateCheck` を参照）。インストール不可なものが見つかった場合は
  「新しいバージョンがあります」となり、代わりにブラウザでリリースページを開く。すでに動作が
  進行中の状態（`Checking`／`Downloading`／`Verifying`／`Installing`）では、項目を消すのではなく
  無効化して表示するので、メニューの形がユーザーの目の前で変わることはない。
  Updates タブ自身の見出し行も同じ
  規則に従う——そのプランでは無効化されたボタンではなく、「ダウンロード」ボタンそのものが
  一切描画されない（`ui-guidelines` の「非表示より無効化を優先する」の例外に当たる: アプリ内
  インストーラが決して扱えないインストール形態は、*一時的に*無効というだけの話ではないため）。
  代わりに、理由を説明する `settings_update_manual_only` の注記文と、手動での代替手段として
  リリースページへのリンク（`LinkRow`。インストール可否に関わらず、リリース情報が判明していれば
  常に表示される）が置かれる。タブ自身の「アップデートを確認」ボタンは全く別の要素で、タブ下部の確認間隔
  コントロールの下に、`plan` に関わらず常に存在する——見出し行のボタンが「縮退した」先ではない。
  通知センターのベルは、意味のある瞬間ごとに 1 行
  出す——`check()` が見つけた時点で「新しいバージョンがあります」、ダウンロードが終わった時点で
  同じ行を消してから「インストール準備ができました」に置き換える（並べて残すことは無い）——専用の
  アクションを新設するのではなく、既存の `ShowSettingsTab("updates")`/`OpenUrl(releaseUrl)` を
  再利用する（[error-design.ja.md](error-design.ja.md) の「通知センター」参照）。ダウンロードの
  失敗そのものは意図的にベルへは出さない——トレイの項目と Updates タブ（自身の「再試行」ボタン
  付き）が既に提示しており、ちょっとしたネットワークの不調のたびに `postNotification` を呼ぶのは
  有用というよりうるさいだけになる。

**完全性検証**は、GitHub Releases API 自身が返す `assets[].digest`（`"sha256:…"`）とダウンロード
したファイル自身の SHA-256 が一致することだけに全面的に依拠している——API が既にこの値を返すため、
この機能のために `release.yml` を変更する必要は無かった。これは転送経路の破損や改竄されたダウンロード
は検出するが、**発行者の侵害は検出しない**: リリースを作成する GitHub アカウント／トークン自体が
侵害されれば、攻撃者がすり替えたアセットとその digest は互いに一致してしまう。この限界と、より
強い保証（例えば minisign/cosign の detached 署名）に何が必要かについては
[SECURITY.ja.md](../SECURITY.ja.md) を参照。

**条件付きリクエスト。** `ReleaseFeedSource` は各エンドポイントの `ETag` をメモリ上にキャッシュし
（`releases/latest` 用と `releases` 一覧用に 1 枠ずつ——`UpdateChecker` インスタンスは自身の
`currentVersion` が選んだ側しか呼ばない、というのは同クラス自身の KDoc の通り）、次回呼び出し時に
`If-None-Match` として送り返す。304 が返れば、空のボディを「何も見つからなかった」と誤解するのでは
なく、キャッシュ済みのパース結果をそのまま再利用する。あえて `local_settings.json` へは永続化して
いない（`FeedFetcher` の validator が `feeds` テーブルへ保存されるのとは対照的——下記「フィード
更新の効率化」参照）: `ReleaseFeedSource` は `UpdateChecker` の Koin `single` の一部としてアプリの
プロセス寿命全体で生き続けるため、インメモリキャッシュだけで実際に重要なケース（定期的なバック
グラウンド再チェックのたびに再取得・再パースするのを避けること）は既にカバーできており、再起動を
挟んだ稀なケースのためだけにリリースペイロードまで再起動をまたいで永続化する複雑さを追加する
価値は無いと判断した。

## フィード更新の効率化

`FeedFetcher` は `If-None-Match`（ETag）/ `If-Modified-Since`（Last-Modified）を送り、304 なら
新着なしとして空を返す。更新後の ETag / Last-Modified は `feeds` テーブルに保存する。

304 応答は `FetchedFeed.notModified` で区別され、`FeedRepository` は保存済みの検証子を書き換えない。
このフラグがないと、304 の空の結果は「検証子を送らなくなったフィード」と区別できず、そのまま書き戻すと
`etag` / `last_modified` が NULL になる。すると次回は条件付きヘッダを送れずサーバが全文を返すため、
1回おきに仕組みが無効化されていた。

更新経路の `feeds` への書き込みはすべて「実際に値が変わったとき」だけに絞ってある。記事一覧クエリは
`feeds` を結合しているので、SQLDelight は `feeds` への書き込みのたびにこれを再実行する。何も変わらない
更新では書き込みも再クエリも発生しない。

`FeedRepository.refreshAll` は各フィードのネットワーク取得を**並行**（同時取得数を
`REFRESH_FETCH_CONCURRENCY` で上限）で行い、その後で各フィードの DB 書き込みをフィード順に
**直列**で適用する。そのため購読数が多くても、更新にかかる時間は「全取得の合計」ではなく
「最も遅い取得」程度で済む。DB 書き込みは単一スレッドのまま（JVM の SQLite ドライバは文ごとに
新しいコネクションを開くため、並行書き込みは競合しうる）で、各フィードの記事は従来どおり
1 フィードずつコミットされるため、更新の進行に合わせてリストに逐次表示される。

## 起動時タスク（`runStartupTasks` / `runAndroidStartupTasks`）

共有の5ステップからなるメンテナンスシーケンス——キャッシュ削除・初回クラウド同期・フィード更新・
アップデート確認・FTS heal——は1か所にまとまっており、commonMain の `domain/StartupMaintenanceTasks.kt`
の `runStartupMaintenance` がそれで、プラットフォームごとに重複実装されているわけではない。デスクトップの
`runStartupTasks`（`desktopMain/StartupTasks.kt`）はその前にデスクトップ固有の2ステップ——macOS の
translocated インストールの警告と、前回のアプリ内アップデートインストールが残した stale な
self-replace 成果物の掃除——を足すだけで、残りは `runStartupMaintenance` を呼ぶ。Android の
`runAndroidStartupTasks`（前述）にはデスクトップ固有のステップに相当するものは無く、代わりに同じ
`runStartupMaintenance` 呼び出しを `startupMaintenanceMutex` / プロセスにつき1回限りのガードで
包むだけである（理由はそのファイル自身の KDoc を参照）:

1. キャッシュ削除（`cleanUpArticleCacheIfDue`。前回から 24 時間以上経過時）。
2. クラウドプロバイダーに接続済みなら初回同期（`SyncRepository.sync(SyncTrigger.AUTOMATIC)`）——
   デスクトップは Dropbox / Google Drive / OneDrive、Android も同じ 3 種（ただし Google Drive は
   Play 開発者サービスが利用できる環境のみ）。起動時であっても前述の
   `SyncTrigger.AUTOMATIC` のゲートを迂回するわけではなく、`autoSyncSuspended` が真の間は、
   この呼び出しでダウンロードもマージもアップロードも行われない。
3. フィード更新とその新着記事通知（`refreshFeedsAndNotify`）。
4. 自動/バックグラウンドのスケジュールでのアップデート確認（`checkForUpdateAndNotify`）。
5. FTS 全再構築（`maybeRebuildFtsIndex`、前回から 24 時間以上 かつ アイドル時のみ。下記）。

この5ステップのシーケンスとは別に、FTS の初回作成・未索引行の増分投入は、`runStartupTasks` /
`runAndroidStartupTasks` に到達するより前に、プロセスにつき一度だけ実行される:

- **デスクトップ。** `FtsManager.ensureIndexed()` が担う: `application {}` の前に `runBlocking`
  でブロックして待つ（最初のウィンドウ表示が遅れるだけで済み、かつ `main.kt` はプロセスにつき
  一度しか走らないので許容できる）。
- **Android。** `KeryxApplication.onCreate` はこれを共有のアプリスコープ `CoroutineScope` 上で
  fire-and-forget で起動する — `Application.onCreate` をブロックすると Android の全コールド
  スタートが遅延してしまうため。完了前の短い間に検索が実行された場合は、失敗するのではなく
  ヒット件数が少なめ（0件を含む）になるだけである。
- **より軽量な版を呼ぶ理由。** ここで呼ぶのは `ensureIndexed()` ではなく、より軽量な
  `FtsManager.ensureIndexedIfTableAbsent()` である: `Application.onCreate` は `FeedRefreshWorker`
  を走らせるための `WorkManager` の起床でも実行される（プラットフォームの最短間隔 15 分なら
  1日最大 ~96 回。「Android での実装」節を参照）ため、`ensureIndexed()` が呼ぶ `indexMissing()`
  の `O(記事数)` スキャンをそのたびに払うわけにはいかない。`ensureIndexedIfTableAbsent()` は
  テーブルが一度作成・バックフィルされた後は `sqlite_master` を1回引くだけの no-op になる。
- 新着記事の索引付けは、`refreshFeedsAndNotify` / 同期でのホットパス `indexMissing()` 呼び出しと、
  下記の日次再構築 heal で通常どおり継続される。

## FTS 全再構築の日次 heal（`maybeRebuildFtsIndex`）

hot path（フィード更新・同期マージ）は `FtsManager.indexMissing()` で新記事だけを増分投入する（全再構築はしない）。
そのため、本文が更新された既存記事の索引の古さを解消するため、全再構築を
**日次アイドル**に降格して実行する。`runStartupTasks`・`backgroundUpdateLoop` の各周回・
`runAndroidStartupTasks`・`FeedRefreshWorker` の各実行はいずれも `maybeRebuildFtsIndex` を呼び、
`local_settings.lastFtsRebuiltAt` の 24h ゲートと `ActivityCenter`（同期・更新が非実行で、更新 → 同期の一連の処理 `refreshCycleRunning` も
非実行。その更新と同期の間の一瞬も含む）の
アイドル判定を満たすときだけ `rebuildIndex()` を実行して `lastFtsRebuiltAt` を記録する。`'rebuild'` は原子的＋
`busy_timeout` 待ちのため、実行中の検索も 0 件にならない。詳細は
[sync-architecture.ja.md](sync-architecture.ja.md) の「FTS5 の扱い」。
