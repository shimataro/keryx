# エラー設計

[English](error-design.md)

## 設計方針

- **例外は「予期しないエラー」、`Result` 型は「予期されるエラー」**という使い分け。
- ネットワーク・同期エラーは頻繁に起きうるため、ユーザーへの通知は控えめ（通知センター + インライン表現）。
- UI 層へは ViewModel の `StateFlow` / `mutableStateOf` 経由で伝達する。

## 例外 vs Result 型

| ケース | 扱い |
| --- | --- |
| ネットワークエラー・タイムアウト | Result 型 |
| 同期の競合・リトライ失敗 | Result 型 |
| フィード URL が無効 | Result 型 |
| DB アクセス失敗・プログラムのバグ | 例外 |

## エラー型（`core/KeryxException.kt`, `core/Result.kt`）

```kotlin
sealed interface Result<out T> {
    data class Ok<out T>(val value: T) : Result<T>
    data class Err(val exception: KeryxException) : Result<Nothing>
}

sealed class KeryxException(message: String) : Exception(message)
```

主なサブクラス: `FeedFetchException(statusCode)`, `FeedParseException`, `FeedDiscoveryException(candidates)`,
`FeedTimeoutException`, `FeedNotFoundException(isGone)`, `CloudAuthException`, `CloudStorageException`,
`SyncConflictException`, `SchemaVersionException(localVersion, cloudVersion)`, `CloudDataIncompatibleException`, `InvalidFeedUrlException`。

補助拡張: `isOk` / `isErr` / `valueOrNull` / `errorOrNull` / `fold` / `onOk` / `onErr` / `map`。

## 層ごとの処理

- **DataSource 層**: Ktor / SQLite の例外を `KeryxException` サブクラスへ変換する。上位に生の例外を漏らさない。
  - `FeedFetcher`: 304 / 301・308（恒久リダイレクト、URL 更新）/ 302・303・307（一時）/ 410 / 404 /
    4xx / タイムアウト（定数回リトライ）を判別。HTML ページなら `FeedDiscovery` で候補を探し
    `FeedDiscoveryException` を返す。最大 5 回のリダイレクトループガードあり。
  - `DropboxStorage`: 401/403 → `CloudAuthException`、409（upload）→ `SyncConflictException`、
    409 `path/not_found`（get_metadata）→ 存在しない、を判別。
- **Repository 層**: `Result` を受けてビジネスロジック（リトライ等）を適用。
- **ViewModel 層**: `Result` を UI 状態へ変換。
- **UI 層**: `ui/i18n/ErrorMessages.kt` の `userMessage(KeryxException)` は `KeryxException` を
  インライン表示用（購読追加時のエラーテキストなど）のメッセージ `String` にローカライズするだけで、
  通知センターへは流さない。通知センターへのエントリは、Repository 層が `NotificationMessages`
  経由で別途生成する（後述）。

## 通知センター（`domain/NotificationCenter`）

- 通知センター（履歴・手動で消す）を主とする。かつての一時トーストは macOS ネイティブ寄りの
  インライン表現（コピーは操作元の✓、OPML はボタン近くの結果テキスト、購読は一覧出現＋ダイアログ内表示）へ
  置き換えたため、アプリ内スナックバーは廃止した。
- 履歴はセッション中のみ保持（DB 保存なし）。記録するのは「後から見返す価値がある内容」に限る:
  エラー・警告に加え、`INFO` は新バージョンの通知のみ。**新着記事は通知センターには記録しない**
  （`NewArticleNotifier` は OS 通知（トレイ）にのみ流す）——記事一覧と未読バッジという永続的な手段で
  既に把握できるため。手動更新も同様に、一覧・未読バッジの更新で示す。
- ベルアイコンにバッジ（件数）。バックグラウンド更新中の警告は UI コンテキストが無いため
  通知センターにのみ記録する。
- **ベルに残るすべての通知はネクストアクションを持つ**（`AppNotificationAction`）。行をクリックすると
  そのアクションが実行され、`ResetCloudData` だけは破壊的操作のため行クリックではなく専用の
  インライン確認ボタンを持つ。クリック可能な行は、設定画面の `LinkRow` と同じ見た目
  （primary 色 + hover 時に下線）でそれを示す。

| ネクストアクション | 発生源 | 挙動 |
| --- | --- | --- |
| `OpenUrl(url)` | 新バージョン通知 | リリースページを外部ブラウザで開く |
| `ShowFeedDetail(feedId)` | フィード消失(410) / URL 変更(301/308) | フィード一覧で該当フィードを選択（一覧をクリックしたときと同じ） |
| `ShowSettingsTab(tabId)` | 同期エラー（`SchemaVersionException` は `updates`、その他は `cloud_sync`） | 設定ダイアログを該当タブで開く。`cloud_sync` タブは `SyncRepository.lastSyncError` を失敗理由として表示し、`updates` タブは開いた時点で自動的に更新確認を行う |
| `ShowInfoDialog(detail)` | macOS の translocated 警告 | 原因と対処法の説明ダイアログを表示（画面遷移しない） |
| `ResetCloudData` | `CloudDataIncompatibleException` | 専用のインラインボタン → 確認ダイアログ → クラウドデータのリセット |

`AppNotification(id, level: INFO|WARNING|ERROR, message, timestampMillis, action)`。
Repository から通知を出す際、文言は `NotificationMessages`（`getString` ベース、テストでは Fake）で
ローカライズする（ベタ書き禁止）。

## エラーの重大度と通知先（抜粋）

| エラー | 自動リトライ | 通知センター |
| --- | --- | --- |
| `FeedTimeoutException` / `FeedFetchException` | ✅ | ✅ |
| `FeedParseException` | ❌ | ✅ |
| `CloudStorageException` | ✅ | ✅ |
| `SyncConflictException` | ✅（内部） | ❌ |
| `CloudAuthException` / `SchemaVersionException` | ❌ | ✅ |
| `CloudDataIncompatibleException`（破損/非互換なクラウドDB） | ❌ | ✅ |
| `FeedNotFoundException(isGone=true)` | ❌ | ✅ |

## 定数（`core/Constants.kt`）

`SYNC_MAX_RETRY=3`, `FEED_TIMEOUT_RETRY_COUNT=1`, `SYNC_DEBOUNCE_MS=5000`,
`CONNECTION_TIMEOUT_MS=10000`, `READ_TIMEOUT_SECONDS_DEFAULT=30`, `MAX_REDIRECTS=5`。
