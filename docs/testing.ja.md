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
ViewModel 層（Home/Settings/Setup/NotificationCenter）、ArticleWebViewHtml（extractLinks/wrapArticleHtml）、AppFont（Linux の UI フォント用 Pango フォント記述のパース）、FTS（FtsManager/FtsSearch。
`indexMissing` の増分投入・非破壊、`rebuildIndex` がテーブル存在を前提とすること、同期アップロードが
`VACUUM INTO` スナップショットで `articles_fts` を除外し `user_version` を保全することを含む）、
Linux の SNI トレイ（`TrayPixmapTest`＝ビッグエンディアン ARGB32 / RGBA エンコーダーとアルファ保全、
`TrayMenuModelTest`＝dbusmenu レイアウト、`TrayMenuRevisionTest`＝revision / `AboutToShow` /
イベントディスパッチ、`DBusSignatureTest`＝export した D-Bus シグネチャ）などを網羅する。
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
Linux の SNI トレイでは `SniConnection`（接続・バス名取得・export・登録・再登録・close）が
実セッションバスと稼働中の `org.kde.StatusNotifierWatcher` を必要とするため CI では不可。同様に
`NewIcon`/`NewToolTip`/`LayoutUpdated` の実配送（*発火の判断* はカバー済み）、`NameOwnerChanged` からの
再登録経路、ホスト起点の `Activate`/`Event` が dbus-java のワーカースレッド経由で届くこと、
`LinuxNotifier.notify` の実デーモンへの配送、`LinuxTray` コンポーザブルの結線もテスト不可。
パネル上で実際に透過して見えるかは本質的に目視確認になる。

## 手動確認（UI）

`./gradlew :composeApp:run` で起動して 3 ペイン UI・テーマ切替・フィード追加・検索を目視確認する。
フィード/フォルダーの並び替え機能は自動テストできないため、以下を目視確認する:

- フォルダーをドラッグして順序を入れ替え、アプリを再起動しても順序が保持されること。
- フォルダー内のフィードをドラッグして順序を入れ替え、再起動後も順序が保持されること。
- 「フォルダーなし」グループ内でのフィード並び替え。
- フィードを別フォルダーの任意の位置（横線の位置）にドロップし、フォルダー移動と位置決めが同時に
  反映されること。
- 「フォルダーへ移動」ダイアログでの移動が、移動先グループの末尾に追加されること。

並行フィード更新（並行取得＋直列書き込み、`FeedRepository.refreshAll`）の中核となる並行動作
（取得のオーバーラップとフィードごとの書き込み完全性）は `refreshAllFetchesFeedsConcurrentlyAndAppliesEveryWrite` が、
編集の巻き戻しが起きないことは `refreshAllDoesNotRevertConcurrentUnsubscribe` /
`refreshAllDoesNotRevertConcurrentReorder` が自動テストで担保している。目視が必要な UI / エンドツーエンドの
部分のみ、複数フィードを購読した状態で以下を目視確認する:

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
- トレイアイコンのアセットは描画のされ方で決まる。macOS と SNI ホストのある Linux は白グリフ + 黒フチ
  （`tray_icon_outlined.png`。透過が効き 22px 以上で描かれる前提）。Windows の通知領域と Linux の AWT
  フォールバックはフルカラー（`tray_icon.png`。Windows は 16px でティントもせず、AWT フォールバックは
  アイコンの背後に不透明な白い箱を描くため）。未読 > 0 でどちらにも赤ドットが乗ること。各プラット
  フォームで確認する:
  - （Windows）**ライト**タスクバーでもダークでもはっきり読めること。outlined が白飛びするのは
    ライトテーマなので、そちらが確認の要。
  - （Linux・SNI ホスト無し）AWT が描く白い箱の中でフルカラーのグリフが判別できること。
  - （macOS / SNI ホストのある Linux）従来どおり outlined で、明暗どちらの背景でも判別できること。

Linux の SNI トレイは自動テスト不可のため、KDE Plasma セッションで以下を確認する（間違えやすい順）。

- 22px / 24px、明パネル・暗パネル、2x スケーリングで**白い四角の中に描画されない**こと。
  不適切なサイズが選ばれる場合は `SNI_ICON_SIZES` を絞る。
- `./gradlew :composeApp:createDistributable` でパッケージし、
  `build/compose/binaries/main/app/Keryx/bin/Keryx` を起動して確認する。jlink モジュール欠落
  （`jdk.security.auth`）はここでしか露見せず、`run` では検出できない。
- 左クリックでウィンドウがトグルすること（`ItemIsMenu = false` 依存。メニューが開くならプロパティが誤り）。
- 右クリックでメニューが正しい文言で出て、ウィンドウをトグルした後にメニューを開き直さずに
  表示/非表示ラベルが反転すること（`AboutToShow` + `LayoutUpdated` の確認）。
- 未読ドットが即時に出入りすること（`NewIcon` がホストに届いている）。
- `systemctl --user restart plasma-plasmashell` の後、Keryx を再起動せずアイコンが復帰すること。
- バックグラウンド更新でアプリアイコン付きのデスクトップ通知が出ること。
- AppIndicator 拡張なしの GNOME で AWT 経路に静かにフォールバックすること（クラッシュ・スタックトレース
  無し）。`DBUS_SESSION_BUS_ADDRESS` 無しの起動でハングも例外も起きないこと。
- Plasma Wayland セッションでも同じ挙動であること。
- アイコン上でホイールを回しても `journalctl --user -f` にエラーが出ないこと。
- メニューから終了した後、`busctl --user list | grep StatusNotifierItem` に残骸が無いこと。
- `GetGroupProperties`/`AboutToShowGroup`/`EventGroup` が正しく動くこと（`ai` / `a(isvu)` 入力の
  デシリアライズは dbus-java 任せで、`DBusSignatureTest` は宣言シグネチャしか保証しない）。おかしい場合は
  引数を `IntArray` / `Array<DBusMenuEventEntry>` に変える。
