# テスト方針

[English](testing.md)

## 構成

- `commonTest/` — 純粋ロジックと Ktor `MockEngine` を使うテスト（パーサ、フェッチャ、URL リゾルバ、
  OPML、Dropbox ストレージ/認証、ローカル設定）。デスクトップターゲット上で動くため、`expect` 宣言は
  desktop の `actual` に解決される（`FileIO` / `AppDirs` を一時ディレクトリで利用可能）。
- `desktopTest/` — 実際の SQLDelight ドライバ（`JdbcSqliteDriver`）が必要なテスト（スキーマ、記事 upsert、
  ATTACH マージ）。ヘルパーは `DbTestSupport.kt`（`inMemoryDb()`, `fileDb()`, `insertFeed()`）。
  同ディレクトリには、実際に Composable をレンダリングして検証する Compose UI テスト
  （`androidx.compose.ui.test.runDesktopComposeUiTest`、JUnit4 ルール不要）も置く
  （例: `ArticleListPaneTest.kt`）。実 Skia/AWT レンダラが必要なため `commonTest` ではなく
  `desktopTest` に置く。

新しいテストは対象コードと同じ相対パスに置く。

## 規約

- フレームワーク: `kotlin.test`（`@Test`, `assertEquals`, `assertIs`, `assertTrue`, `assertFailsWith`）。
  コルーチン: `kotlinx.coroutines.test.runTest`。
- HTTP: Ktor `MockEngine` + `respond(...)`。クライアントは本番 DI と同じ設定
  （`followRedirects=false`, `expectSuccess=false`, フェッチャは `install(HttpTimeout)`）で組む。
- 時刻は `Clock { fixedMillis }`、スケジューリングは `SyncScheduler {}` でフェイクする。
- マージは `platform/DatabaseMerger.merge(...)` を 2 つの `fileDb()` に対して呼んで検証する
  （生コネクションでマージする前に SQLDelight ドライバを close する）。
- `androidx.lifecycle.ViewModel`（`viewModelScope` は `Dispatchers.Main.immediate` 依存）のテストは
  `@BeforeTest` で `Dispatchers.setMain(StandardTestDispatcher())`、`@AfterTest` で `Dispatchers.resetMain()`
  を呼ぶ（`HomeViewModelTest.kt` が最初の導入例）。`StateFlow` が `SharingStarted.WhileSubscribed(...)`
  の場合、テスト側で明示的に `collect` して購読を開始しないと値が更新されない点に注意。
- `SyncRepository` のような `CloudStorage` を直接扱うクラスは、HTTP 層をモックする代わりに
  `CloudStorage` インターフェースを手製フェイク（インメモリ Map + rev 管理）で差し替えて検証する
  （`SyncRepositoryTest.kt` 参照）。`DropboxStorage`/`DropboxAuthManager` 自体のテストは従来どおり
  Ktor `MockEngine` で HTTP 層をモックする。
- `runTest`（仮想時間）と Ktor `MockEngine` の `HttpTimeout` や実ソケット I/O を組み合わせると、
  タイムアウトが誤検知されて flaky になることがある。該当するテストは `kotlinx.coroutines.runBlocking`
  （または実時間ポーリング）に切り替える（`FeedFetcherTest.kt`, `FeedRepositoryTest.kt`,
  `OAuthLoopbackServerTest.kt` 等の実績あり）。

## `Result<T>` のテスト方針

成功（`Result.Ok`）と失敗（`Result.Err`）の両分岐をテストし、失敗時は具体的な `KeryxException`
サブタイプを検証する（`FeedTimeoutException`, `FeedNotFoundException(isGone=…)`,
`SyncConflictException`, `CloudAuthException`, `FeedDiscoveryException` など）。

## 実行

```bash
./gradlew :composeApp:desktopTest
```

スイートはパーサ、フェッチャのリダイレクト/304/404/410/タイムアウト/ディスカバリ、
OPML、Dropbox ストレージ/認証、PKCE、OAuth ループバックサーバ、マージ（後勝ち・OR マージ・衝突ガード・
FK ガード）、スキーマ、ローカル設定、記事 upsert、URL リゾルバ、日時パーサ、Result、Repository 層
（Article/Feed/Tag/Settings）、CloudSession、NotificationCenter、IdGenerator、SyncRepository、
ViewModel 層（Home/Settings/Setup/NotificationCenter）、ArticleWebViewHtml（extractLinks/wrapArticleHtml）、FTS（FtsManager/FtsSearch。
`indexMissing` の増分投入・非破壊、`rebuildIndex` がテーブル存在を前提とすること、同期アップロードが
`VACUUM INTO` スナップショットで `articles_fts` を除外し `user_version` を保全することを含む）などを網羅する。
`SchemaTest` / `SyncMergerTest` / `SyncRepositoryTest` の失敗は DB スキーマ・
マージ SQL・同期オーケストレーションの退行を意味するので特に注意する。

既知の未カバー範囲: `SettingsViewModel.exportOpml`/`importOpml`（`FilePicker` にテスト用シームが無い
ネイティブダイアログ）、`OAuthConnectFlow.connect()` のブラウザー起動〜コールバック待受〜
コード交換部分（`BrowserOpener`/`OAuthLoopbackServer` の実I/Oに依存し、シームなしにはモック不可。
App Key 空チェックで即エラーになる分岐のみ `OAuthConnectFlowTest` でカバー済み）、
`DatabaseDriverFactory.desktop.kt`（`AppDirs.appDataDir()` を直接参照しておりテスト用の
ディレクトリ差し替えができない）、`FeedDragAndDrop.desktop.kt`（`DragAndDropTransferable` が
ライブラリ内部型でありテストコードから中身を取り出せない上、`draggedFeedId()`/`draggedFolderId()`/
`positionYInRoot()` は実際の AWT `DropTargetDragEvent`/`DropTargetDropEvent` が無いと呼び出せずシームが
無い）。同じ理由で、フィード/フォルダーの並び替え・移動（ドラッグ&ドロップ）の実際のジェスチャー自体
（`FeedListPane.kt` の `FeedRow`/`FolderGroupHeader`/`NoFolderHeader` の `dragAndDropSource`/
`dragAndDropTarget`）もテスト不可。並び替えの計算ロジック自体（`ReorderUtil.reorderIds`）と、それを使う
`FeedRepository.moveFeed`/`FolderRepository.reorderFolders` の DB 反映は通常どおりテストする。

## 手動確認（UI）

`./gradlew :composeApp:run` で起動して 3 ペイン UI・テーマ切替・フィード追加・検索を目視確認する。
フィード/フォルダーの並び替え機能は自動テストできないため、以下を目視確認する:

- フォルダーをドラッグして順序を入れ替え、アプリを再起動しても順序が保持されること。
- フォルダー内のフィードをドラッグして順序を入れ替え、再起動後も順序が保持されること。
- 「フォルダーなし」グループ内でのフィード並び替え。
- フィードを別フォルダーの任意の位置（横線の位置）にドロップし、フォルダー移動と位置決めが同時に
  反映されること。
- 「フォルダーへ移動」ダイアログでの移動が、移動先グループの末尾に追加されること。

並行フィード更新（並行取得＋直列書き込み、`FeedRepository.refreshAll`）の並行動作は自動テスト
できないため、複数フィードを購読した状態で以下を目視確認する:

- 多数のフィードで「すべて更新」した際、記事が最後に一括ではなくフィード単位で逐次表示され、
  最終的なリスト順序が安定していること。
- フィードエラー / 301・308 の URL 変更 / 410 Gone の各通知が従来どおり発行され、未取得の
  ファビコンが更新後に補完されること。

ダイアログのサイズ自動調整（`DialogWindow` の OS ウィンドウ挙動）は自動テストできないため、以下を
目視確認する:

- フィード追加で、複数のフィードリンクを持つ HTML ページの URL を入力 → 確認すると、**ダイアログが
  広がって候補一覧（チェックボックス）が表示される**こと。URL を編集し直すと縮むこと。入力→候補の
  遷移で震え・ちらつきが無いこと。
- 候補一覧の「すべて選択／すべて解除」トグルと選択件数表示、選択 0 件で「購読する」が無効・選択後に
  有効、購読ボタンが候補リストの下に常に見えること。ボタンのラベルが確認↔購読するで切り替わり、
  Enter で送信できること。
- 単一フィードの URL 入力時にタイトルと「記事 N 件」が表示され購読できること。
- macOS のマージ済みタイトルバー／信号機の余白、Windows/Linux の装飾高さが、広がった状態でも崩れないこと。

Dock/タスクバーのアイコン（`Taskbar` / Cocoa activation policy のネイティブ経路）は自動テストできないため、
以下を目視確認する。macOS は `./gradlew :composeApp:createDistributable` の `Keryx.app` と
`./gradlew :composeApp:run` の両方で確認する:

- 起動時、Dock にブランド Keryx アイコン（未読があればバッジ）が表示されること。
- （macOS）トレイに収納 → トレイトグルで復元したとき、Dock アイコンがブランドのまま（ターミナル/JVM 風
  アイコンに変わらない）こと。復元時に既定アイコンが一瞬ちらつく/残る場合は、
  `main.kt` の `applyBrandedDockIcon` 再適用前に短い `delay` を挟む。
- （macOS）実行中に再起動（二重起動アクティベーション）で復元したとき、Dock アイコンがブランドのままの
  こと。
- 未読 > 0 の状態で hide/restore を繰り返してもバッジが保たれること。
- Windows/Linux でタスクバーのアイコン/未読オーバーレイに退行が無いこと。
