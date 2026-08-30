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
  - `DatabaseMerger.merge`: マージ失敗を SQLite の**エラーコード**（`SQLiteException.resultCode`。
    メッセージ文字列ではない）から分類し、`CloudDataIncompatibleException`（破損ファイル、クラウド
    DB 自身の（より緩い）スキーマが許していた制約違反、または — `validateSchema` がダウンロードした
    ファイルとアプリのスキーマの不一致を確認できた場合に限り — 外部・レガシースキーマ）にするか、
    そのまま変更しない（一時的／アプリのバグ、または `validateSchema` が確定できなかったスキーマ
    エラー）。詳細は [sync-architecture.ja.md](sync-architecture.ja.md) の「マージ失敗の分類」を参照。
- **Repository 層**: `Result` を受けてビジネスロジック（リトライ等）を適用。
- **ViewModel 層**: `Result` を UI 状態へ変換。
- **UI 層**: `ui/i18n/ErrorMessages.kt` の `userMessage(KeryxException)` は `KeryxException` を
  インライン表示用（購読追加時のエラーテキストなど）のメッセージ `String` にローカライズするだけで、
  通知センターへは流さない。通知センターへのエントリは、Repository 層が `NotificationMessages`
  経由で別途生成する（後述）。

## 通知センター（`domain/NotificationCenter`）

- 通知センター（履歴・手動で消す）を主とする。かつての一時トーストは macOS ネイティブ寄りの
  インライン表現（コピーは操作元の✓、OPML はボタン近くの結果テキスト、購読は一覧出現＋ダイアログ内表示）へ
  置き換えたため、デスクトップにはアプリ内スナックバーが無い。Android だけはプラットフォーム固有の例外で、
  URL コピーの確認を M3 の `Snackbar` で表示するが、これは API 33 未満に限られる — API 33 以降は OS 側が
  既にクリップボードコピーの確認を表示するため、Snackbar を出すとそれと重複してしまう
  （`platform/PlatformOs.kt` の `platformShowsOwnCopyConfirmation` と `ui/home/HomeCommon.kt` の
  `LocalSnackbarHostState` を参照）。Android における Snackbar のもう一つの用途は
  `ui/home/HomeScreen.kt` の `ForegroundAlertSnackbar`（後述）。
- 履歴はセッション中のみ保持（DB 保存なし）。記録するのは「後から見返す価値がある内容」に限る:
  エラー・警告に加え、`INFO` は新バージョンの通知のみ。**新着記事は通知センターには記録しない**
  （`NewArticleNotifier` は OS 通知（トレイ）にのみ流す）——記事一覧と未読バッジという永続的な手段で
  既に把握できるため。手動更新も同様に、一覧・未読バッジの更新で示す。
- ベルアイコンにバッジ（件数）。ベルは `ArticleListPane` のヘッダ行にあり、シングルペイン幅
  （3 ペインが 3 つの別画面になり、そのヘッダがアプリの起動先の画面に存在しない）では
  `FeedListPane` のヘッダ行にも置かれる（正確な規則は `ui-guidelines` スキルを参照。
  両方に同時に出ることはない）。`ArticleDetailPane` には意図的に置かない。
- バックグラウンド更新中の警告は UI コンテキストが無いため通知センターにのみ記録し、
  **OS 通知には出さない**（OS 通知は新着記事専用。上記参照）。そのため Android では
  `ForegroundAlertSnackbar`（`ui/home/HomeScreen.kt`）が、`WARNING`/`ERROR` の発生時点で
  Snackbar によっても通知する: バッジだけでは「ベルのあるペインを既に見ているユーザー」にしか
  届かず、これらのアラートは `runAndroidStartupTasks` や `FeedRefreshWorker` が非同期に積むため。
  `INFO` は対象外（新バージョン通知はアラートではない）。詳細:
  - 提示済みの判定は `core/AppNotification.kt` の `AlertKey`（レベル + メッセージ + アクション）を
    キーにする。通知 id は `NotificationCenter.addCoalescing` が再発のたびに振り直すため使えない
    — 恒久的に失敗し続ける同期が、バックグラウンド試行のたびではなく一度だけ通知されるようにする。
    重複排除と提示済み判定は同じヘルパを通すので、両者が食い違うことはない。
  - collector はウィンドウが実際に OS フォーカスを持っているかで gate する（`LocalWindowInfo`）。
    アプリがバックグラウンドにある間、通知シェードが下りている間、設定ダイアログ（独立ウィンドウ）が
    開いている間はアラートを保留する — 誰も見ていないウィンドウに出しても、Snackbar が見られないまま
    タイムアウトして消費されてしまうため。フォーカスが戻った時点で提示する。
  - Snackbar のアクションは、その通知自身のネクストアクション（下表）を実行する。ベルの行と同じ
    `notificationRowAction` を通る。`ResetCloudData` は専用の確認を経る必要があるためアクションなしで通知する。
  - 同時に複数届いた場合は最新の 1 件のみを通知する（Material 3 は同時に 1 件）。件数はバッジが伝える。
- **ベルに残るすべての通知はネクストアクションを持つ**（`AppNotificationAction`）。行をクリックすると
  そのアクションが実行され、`ResetCloudData` だけは破壊的操作のため行クリックではなく専用の
  インライン確認ボタンを持つ。クリック可能な行は、設定画面の `LinkRow` と同じ見た目
  （primary 色 + hover 時に下線）でそれを示す。

| ネクストアクション | 発生源 | 挙動 |
| --- | --- | --- |
| `OpenUrl(url)` | 新バージョン通知 | リリースページを外部ブラウザで開く |
| `ShowFeedDetail(feedId)` | フィード消失(410) / URL 変更(301/308) | フィード一覧で該当フィードを選択（一覧をクリックしたときと同じ）。シングルペイン幅ではフィード一覧が独立した画面のため、選択ハイライトすら描かれない画面へ戻るのではなく、そのフィードの記事一覧まで進む — `ui/home/HomePaneLayout.kt` の `paneForFeedDetail` を参照 |
| `ShowSettingsTab(tabId)` | 同期エラー（`SchemaVersionException` は `updates`、その他は `cloud_sync`） | 設定ダイアログを該当タブで開く。`cloud_sync` タブは `SyncRepository.lastSyncError` を失敗理由として表示し、`updates` タブは開いた時点で自動的に更新確認を行う |
| `ShowInfoDialog(detail)` | macOS の translocated 警告 | 原因と対処法の説明ダイアログを表示（画面遷移しない） |
| `ResetCloudData` | `CloudDataIncompatibleException` | 専用のインラインボタン → 確認ダイアログ → クラウドDBをタイムスタンプ付き名前で退避してから作り直す（[sync-architecture.ja.md](sync-architecture.ja.md)「クラウドデータのリセット（退避）」参照） |

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
| `CloudDataIncompatibleException`（破損/非互換なクラウドDB／制約違反データ） | ❌（リセットまたは手動同期の成功まで**自動**同期そのものが抑制される — `SyncTrigger.AUTOMATIC` ゲート。[sync-architecture.ja.md](sync-architecture.ja.md)「自動同期の抑制」参照） | ✅ |
| `FeedNotFoundException(isGone=true)` | ❌ | ✅ |

## 定数（`core/Constants.kt`）

`SYNC_MAX_RETRY=3`, `FEED_TIMEOUT_RETRY_COUNT=1`, `SYNC_DEBOUNCE_MS=5000`,
`CONNECTION_TIMEOUT_MS=10000`, `READ_TIMEOUT_SECONDS_DEFAULT=30`, `MAX_REDIRECTS=5`。
