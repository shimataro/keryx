# バックグラウンド更新

[English](background-update.md)

## プラットフォーム方針

| プラットフォーム | 更新 | 実装 |
| --- | --- | --- |
| Windows / macOS / Linux | ✅ 指定間隔で確実に実行 | コルーチンによる周期ループ（現行） |
| Android | ✅ 概ね指定間隔 | WorkManager（予定） |
| iOS | ⚠️ OS が実行タイミングを判断 | BGTaskScheduler（予定） |

## デスクトップ実装（`desktopMain/main.kt`）

`main()` でアプリスコープのコルーチンを起動し、`refreshIntervalMinutes` の間隔でループする。

```kotlin
while (true) {
    val minutes = settings.refreshIntervalMinutes
    if (minutes <= 0) { delay(60_000); continue }   // 「手動のみ」は 1 分ごとに設定変更を確認
    delay(minutes * 60_000)
    refreshAll()                                     // 全フィード更新（ETag / Last-Modified 差分取得）
    if (newCount > 0 && notificationEnabled) tray.notify(newArticles(newCount))
    sync()                                           // クラウド同期
}
```

- 設定間隔は毎ループ読み直すため、設定変更は次サイクルから反映される（明示的な再スケジュール不要）。
- 更新中のエラーはクラッシュさせず、通知センターに記録する（`FeedRepository.refreshFeed` 内で処理）。
- 新着通知はトレイの `TrayState.sendNotification` で発行する（`TrayState` は Compose の
  `application {}` スコープ内でしか作れないため、`MutableSharedFlow` で橋渡しする）。

## フィード更新の効率化

`FeedFetcher` は `If-None-Match`（ETag）/ `If-Modified-Since`（Last-Modified）を送り、304 なら
新着なしとして空を返す。更新後の ETag / Last-Modified は `feeds` テーブルに保存する。

## 起動時タスク（`runStartupTasks`）

1. キャッシュ削除（前回から 24 時間以上経過時）。
2. Dropbox 接続済みなら初回同期。
3. FTS 全再構築（`maybeRebuildFtsIndex`、前回から 24 時間以上 かつ アイドル時のみ。下記）。
4. （FTS の初回作成・未索引行の増分投入は `application {}` 前に `FtsManager.ensureIndexed()` で実施済み。）

## FTS 全再構築の日次 heal（`maybeRebuildFtsIndex`）

hot path（フィード更新・同期マージ）は `FtsManager.indexMissing()` で新記事だけを増分投入する（全再構築はしない）。
そのため、本文が更新された既存記事の索引の古さと、キャッシュ削除で残る索引エントリを解消するため、全再構築を
**日次アイドル**に降格して実行する。`runStartupTasks` と `backgroundUpdateLoop` の各周回から
`maybeRebuildFtsIndex` を呼び、`local_settings.lastFtsRebuiltAt` の 24h ゲートと `ActivityCenter`（同期・更新が非実行）の
アイドル判定を満たすときだけ `rebuildIndex()` を実行して `lastFtsRebuiltAt` を記録する。`'rebuild'` は原子的＋
`busy_timeout` 待ちのため、実行中の検索も 0 件にならない。詳細は
[sync-architecture.ja.md](sync-architecture.ja.md) の「FTS5 の扱い」。
