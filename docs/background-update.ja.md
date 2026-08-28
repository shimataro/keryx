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
                                  // 有効なら NewArticleNotifier.notifyBackground(newArticles(newCount))
        sync()                    // クラウド同期
    }
    maybeRebuildFtsIndex()        // FTS 全再構築の日次 heal（後述）
}
```

- 設定間隔は毎ループ読み直すため、設定変更は次サイクルから反映される（明示的な再スケジュール不要）。
- 更新中のエラーはクラッシュさせず、通知センターに記録する（`FeedRepository.refreshFeed` 内で処理）。
- 新着通知は同じ `NewArticleNotifier.trayEvents` を入力として、プラットフォームごとに 3 経路で OS へ渡す
  （`TrayState` は Compose の `application {}` スコープ内でしか作れないため、`MutableSharedFlow` で
  橋渡しする）。macOS は `TrayIcon.displayMessage`、StatusNotifierItem ホストがある Linux は
  `org.freedesktop.Notifications.Notify`、Windows（および SNI ホストの無い Linux）は
  `TrayState.sendNotification`。詳細は [app-architecture.ja.md](app-architecture.ja.md) の
  「デスクトップトレイ」を参照。

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
Android の `CloudSession` は現状 Dropbox/OneDrive のみプロバイダーを持つ（Google Drive 非対応の理由は
[sync-architecture.ja.md](sync-architecture.ja.md) の「Android で Google Drive が未対応な理由」参照）
ため、ユーザーがそのどちらとも連携していない場合、あるいは連携済みでも `autoSyncSuspended` が
真の間（直前の `CloudDataIncompatibleException` により、リセットまたは手動同期成功まで
`SyncTrigger.AUTOMATIC` の試行がゲートされる状態。`SyncRepository.sync` 自身の KDoc 参照）は
`sync()` が本当の no-op になる。捕捉した例外（`sync()` 自身の `Result` 型ではなく予期しない失敗）は
`Result.retry()` を返し、リトライは `WorkManager` 自身のバックオフ方針に委ねる。`sync()` が
`Result.Err` を返した場合は別途扱われ、`error-design.md` のオートリトライ表がリトライ可能と定める
分類（`CloudStorageException`）に限り `Result.retry()` とし、リトライ不可と定める恒久的な失敗
（`CloudAuthException`/`SchemaVersionException`/`CloudDataIncompatibleException`）は次回の
定期実行に委ねる。

`MainActivity.onCreate` から `runAndroidStartupTasks`（`AndroidStartupTasks.kt`）を呼ぶ —
デスクトップの `runStartupTasks` に相当するが、macOS 固有の translocation 警告（Android には該当
概念がない）を除く。`cleanUpArticleCacheIfDue`（後述）を実行し、続けてデスクトップの
`runStartupTasks` と同じ位置・同じゲートで初回クラウド同期を行ってから、`FeedRefreshWorker` と
同じ3関数を実行する。これは意図的に `Application.onCreate` ではなく **Activity** 側に置いている:
後者は `WorkManager` が `FeedRefreshWorker` を実行するためにプロセスを起こしたときにも走るため、
バックグラウンド起床のたびに起動時処理一式を実行すると、Worker 自身が直前に行った更新/同期/更新確認/
FTS 処理と重複してしまう。プロセス内ガード（`startupTasksRan`）により、画面回転など Activity だけが
再生成される設定変更で `onCreate` が再度走ってもプロセス内で1回に保たれる。5つのステップはそれぞれ
独立して実行される（`runMaintenanceStep`）ため、1ステップが例外を投げても
（例: `maybeRebuildFtsIndex` が `FtsManager` の `busy_timeout` に達する場合）残りのステップをスキップ
させない。ガードはすべてのステップを一通り試行し終えた後にのみセットされ、その前ではない:
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

`runStartupTasks` 自体はデスクトップ専用のオーケストレーション（`desktopMain/StartupTasks.kt`）—
macOS の translocated インストールの警告（デスクトップ固有の関心事）はこの中で行っている — だが、
キャッシュ削除・フィード更新通知・アップデート通知・FTS 再構築（下記のステップ1・3）は commonMain の
`domain/StartupMaintenanceTasks.kt` にあるプラットフォーム非依存の関数に委譲する。Android の
`runAndroidStartupTasks`（前述）は同じステップ1・3の関数を直接呼び、ステップ2もデスクトップと同じ
やり方で自ら実行する — どちらも `StartupMaintenanceTasks` の関数を経由せず、
`CloudSession.isConnected()` でガードしたうえで `SyncRepository.sync()` を直接呼ぶ:

1. キャッシュ削除（`cleanUpArticleCacheIfDue`。前回から 24 時間以上経過時）。
2. クラウドプロバイダーに接続済みなら初回同期（`SyncRepository.sync(SyncTrigger.AUTOMATIC)`）——
   デスクトップは Dropbox / Google Drive / OneDrive、Android は Dropbox / OneDrive。
3. FTS 全再構築（`maybeRebuildFtsIndex`、前回から 24 時間以上 かつ アイドル時のみ。下記）。
4. FTS の初回作成・未索引行の増分投入は、デスクトップでは `FtsManager.ensureIndexed()` が担う:
   `application {}` の前に `runBlocking` でブロックして待つ（最初のウィンドウ表示が遅れるだけで
   済み、かつ `main.kt` はプロセスにつき一度しか走らないので許容できる）。`KeryxApplication.onCreate`
   はこれを共有のアプリスコープ `CoroutineScope` 上で fire-and-forget で起動する —
   `Application.onCreate` をブロックすると Android の全コールドスタートが遅延してしまうため。
   完了前の短い間に検索が実行された場合は、失敗するのではなくヒット件数が少なめ（0件を含む）に
   なるだけである。ただしここで呼ぶのは `ensureIndexed()` ではなく、より軽量な
   `FtsManager.ensureIndexedIfTableAbsent()` である: `Application.onCreate` は `FeedRefreshWorker` を
   走らせるための `WorkManager` の起床でも実行される（プラットフォームの最短間隔 15 分なら
   1 日最大 ~96 回。「Android での実装」節を参照）ため、`ensureIndexed()` が呼ぶ `indexMissing()` の
   `O(記事数)` スキャンをそのたびに払うわけにはいかない。`ensureIndexedIfTableAbsent()` はテーブルが
   一度作成・バックフィルされた後は `sqlite_master` を 1 回引くだけの no-op になる。新着記事の
   索引付けは、ステップ3の `refreshFeedsAndNotify` / 同期でのホットパス `indexMissing()` 呼び出しと、
   下記の日次再構築 heal で通常どおり継続される。

## FTS 全再構築の日次 heal（`maybeRebuildFtsIndex`）

hot path（フィード更新・同期マージ）は `FtsManager.indexMissing()` で新記事だけを増分投入する（全再構築はしない）。
そのため、本文が更新された既存記事の索引の古さを解消するため、全再構築を
**日次アイドル**に降格して実行する。`runStartupTasks`・`backgroundUpdateLoop` の各周回・
`runAndroidStartupTasks`・`FeedRefreshWorker` の各実行はいずれも `maybeRebuildFtsIndex` を呼び、
`local_settings.lastFtsRebuiltAt` の 24h ゲートと `ActivityCenter`（同期・更新が非実行）の
アイドル判定を満たすときだけ `rebuildIndex()` を実行して `lastFtsRebuiltAt` を記録する。`'rebuild'` は原子的＋
`busy_timeout` 待ちのため、実行中の検索も 0 件にならない。詳細は
[sync-architecture.ja.md](sync-architecture.ja.md) の「FTS5 の扱い」。
