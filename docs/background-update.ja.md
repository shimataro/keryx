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
    delay(if (minutes <= 0) 60_000L else minutes * 60_000L)  // 「手動のみ」（minutes <= 0）は 1 分ごとに起床
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
無いため）: 「手動のみ」（`<= 0`）はジョブを完全にキャンセルし、`WorkManager` 自体の最短間隔
（`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`、15分）を下回る正の値はそれに切り上げる
（無効化はしない）。アプリの UI 自体は15分未満の値を提示しないため、これは手動編集や移行された
`local_settings.json` の場合にのみ関係する。

`background/FeedRefreshWorker.kt`（`CoroutineWorker`。`WorkManager` 自身の `WorkerFactory` が
リフレクションでインスタンス化するため、依存関係はコンストラクタ注入ではなく `doWork()` 内で
`KoinPlatform.getKoin()` から解決する）は、デスクトップの `backgroundUpdateLoop` が毎周回呼ぶのと
まったく同じ3つの commonMain 関数 `refreshFeedsAndNotify` / `checkForUpdateAndNotify` /
`maybeRebuildFtsIndex` を実行する。`SyncRepository.sync()` は呼ばない — クラウド同期はフェーズ4の
作業であり、Android の `CloudSession(providers = emptyMap())` ではどのみち no-op になって起床を
無駄にするだけだからである。捕捉した例外は `Result.retry()` を返し、リトライは `WorkManager` 自身の
バックオフ方針に委ねる。

`MainActivity.onCreate` から `runAndroidStartupTasks`（`AndroidStartupTasks.kt`）を呼ぶ —
デスクトップの `runStartupTasks` に相当するが、macOS 固有の translocation 警告と初回クラウド同期
（これもフェーズ4）を除く。`cleanUpArticleCacheIfDue`（後述）を実行してから、`FeedRefreshWorker` と
同じ3関数を実行する。これは意図的に `Application.onCreate` ではなく **Activity** 側に置いている:
後者は `WorkManager` が `FeedRefreshWorker` を実行するためにプロセスを起こしたときにも走るため、
バックグラウンド起床のたびに起動時処理一式を実行すると、Worker 自身が直前に行った更新/更新確認/FTS
処理と重複してしまう。プロセス内ガード（`startupTasksRan`）により、画面回転など Activity だけが
再生成される設定変更で `onCreate` が再度走ってもプロセス内で1回に保たれる。

新着記事の OS 通知は `domain/OsNotificationSink.kt`（`fun interface`）経由で届く。Android は
`platformModule` でこれを `platform/AndroidNotificationSink.kt`（`NotificationManagerCompat` で
投稿する実装）に束縛しており、デスクトップの `NewArticleNotifier.trayEvents` を購読する経路とは
別系統になっている（理由はそのクラス自身の KDoc を参照: `WorkManager` に起こされたプロセスでは、
更新が終わった時点で `trayEvents` の購読者が既に張られている保証が無い — `trayEvents` は replay 0 で、
購読者がいない間に発行されたものは黙って捨てられるため）。`AndroidNotificationSink` は投稿のたびに
`NotificationManagerCompat.areNotificationsEnabled()` でガードしており、これ1回で Android 13+ の
`POST_NOTIFICATIONS` ランタイム権限とユーザーによるアプリ/チャンネル単位のブロックの両方をカバーする。
権限自体は `platform/NotificationPermission.kt` の `rememberNotificationPermissionRequester` で
リクエストし、起動時に1回（`App.kt`。ユーザー自身の「通知を有効にする」設定が既に ON の場合）と、
`NotificationsTab` でその設定を ON にしたときの両方で呼ぶ。ユーザーがシステムダイアログを2回目に
拒否（「今後表示しない」）した後は、Android 自身がそれ以降のプログラムからのリクエストに対して
ダイアログを表示しなくなる — 設定のトグル自体は ON のままにしておいてよいが、ユーザーが OS の設定から
直接許可するまで通知は届かない。この場合に「端末の設定を開いてください」と誘導するフローは今回は
作っていない。

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
macOS の translocated インストールの警告（デスクトップ固有の関心事）も、ステップ2（初回同期）を
`SyncRepository` 経由で直接実行するのもこの中で行っている — だが、キャッシュ削除・フィード更新通知・
アップデート通知・FTS 再構築（下記のステップ1・3）は commonMain の `domain/StartupMaintenanceTasks.kt`
にあるプラットフォーム非依存の関数に委譲する。Android の `runAndroidStartupTasks`（前述）は同じ
ステップ1・3の関数を直接呼び、ステップ2は（フェーズ4の作業のため）省いている:

1. キャッシュ削除（`cleanUpArticleCacheIfDue`。前回から 24 時間以上経過時）。
2. Dropbox 接続済みなら初回同期（今のところデスクトップのみ）。
3. FTS 全再構築（`maybeRebuildFtsIndex`、前回から 24 時間以上 かつ アイドル時のみ。下記）。
4. FTS の初回作成・未索引行の増分投入は `FtsManager.ensureIndexed()` が担う: デスクトップでは
   `application {}` の前に `runBlocking` でブロックして待つ（最初のウィンドウ表示が遅れるだけなので
   許容できる）。`KeryxApplication.onCreate` はこれを共有のアプリスコープ `CoroutineScope` 上で
   fire-and-forget で起動する — `Application.onCreate` をブロックすると Android の全コールドスタートが
   遅延してしまうため。完了前の短い間に検索が実行された場合は、失敗するのではなくヒット件数が
   少なめ（0件を含む）になるだけである。

## FTS 全再構築の日次 heal（`maybeRebuildFtsIndex`）

hot path（フィード更新・同期マージ）は `FtsManager.indexMissing()` で新記事だけを増分投入する（全再構築はしない）。
そのため、本文が更新された既存記事の索引の古さを解消するため、全再構築を
**日次アイドル**に降格して実行する。`runStartupTasks`・`backgroundUpdateLoop` の各周回・
`runAndroidStartupTasks`・`FeedRefreshWorker` の各実行はいずれも `maybeRebuildFtsIndex` を呼び、
`local_settings.lastFtsRebuiltAt` の 24h ゲートと `ActivityCenter`（同期・更新が非実行）の
アイドル判定を満たすときだけ `rebuildIndex()` を実行して `lastFtsRebuiltAt` を記録する。`'rebuild'` は原子的＋
`busy_timeout` 待ちのため、実行中の検索も 0 件にならない。詳細は
[sync-architecture.ja.md](sync-architecture.ja.md) の「FTS5 の扱い」。
