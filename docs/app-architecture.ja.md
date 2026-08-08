# アプリアーキテクチャ

[English](app-architecture.md)

## 設計方針

- レイヤードアーキテクチャ（UI → ViewModel → Repository → DataSource）
- Koin で依存性注入、androidx.lifecycle ViewModel で状態管理
- SQLDelight でローカル DB を型安全に管理
- 同期処理は Repository 層に閉じ込め、UI 層は同期の存在を意識しない
- プラットフォーム固有コードは `commonMain` の `expect` + `desktopMain` の `actual` に集約

## ディレクトリー構成

```text
composeApp/src/
  commonMain/kotlin/works/merc/keryx/app/
    core/      Constants, Result, KeryxException, ArticleFilter, AppNotification, Clock, DateTimeParser, CloudStorageAvailability(expect)
    data/local/   DatabaseDriverFactory(expect), FtsManager, FtsSearch, LocalSettings(Store)
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, CloudSession, NotificationCenter, MergeSql, IdGenerator, CloudConnectFlow
    di/           AppModule（+ expect platformModule）
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot（すべて expect）
    ui/           theme/, navigation/, setup/, home/（3ペイン + 検索 + 通知センター）, article/, settings/, i18n/
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq（7 テーブル）
  commonMain/composeResources/  values/strings.xml, drawable/
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt（起動時/バックグラウンドタスク関数群）+ 各 expect の actual（DatabaseDriverFactory, AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, Pkce, PlatformModule）+ OAuthConnectFlow, OAuthRedirectTransport（CustomUri/Loopback）, OAuthUriParser, LaunchArg, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage 実装（Keyring/File/SecurityCliTokenStorage）, DesktopOs（isMacOs/isWindows/isLinux）, DesktopLookAndFeel（Swing L&F: Linux は FlatLaf）
    tray/      KeryxTray（プラットフォーム分岐）, MacTray, LinuxTray + StatusNotifierItem/dbusmenu の D-Bus オブジェクト
  commonTest/ + desktopTest/
```

パッケージルートは `works.merc.keryx.app`（`keryx.merc.works` の逆順 DNS）。

## レイヤーの責務

| 層 | 責務 | 主な技術 |
| --- | --- | --- |
| UI | 画面描画・入力受け取り | Compose |
| ViewModel | UI 状態保持・イベントを Repository に委譲 | androidx.lifecycle + Koin |
| Repository | ビジネスロジック・同期・競合解決 | Kotlin クラス |
| DataSource | DB / HTTP / ファイル IO | SQLDelight / Ktor / dart:io 相当（java.io） |

## 主要クラス

### DatabaseDriverFactory（expect / actual）

`commonMain` に `expect class DatabaseDriverFactory { fun create(): SqlDriver }`。desktop の `actual` は
`JdbcSqliteDriver` を生成し、`PRAGMA user_version` を見て `KeryxDatabase.Schema` の create / migrate を
自前で駆動する（SQLDelight の JVM ドライバはスキーマバージョンを自動追跡しないため）。

### FtsManager / FtsSearch

`articles_fts`（FTS5 trigram, `content='articles'`）を生 SQL で管理する。SQLDelight のスキーマには含めない。
**ライブ DB では決して DROP しない**（アップロードからの除外はスナップショットのコピー側で行う。`DatabaseSnapshot`）。
`ensureIndexed()`（起動時、初回作成 + 未索引行の増分投入）、`indexMissing()`（hot path＝フィード更新・同期マージ後の
増分投入）、`rebuildIndex()`（日次アイドルの全再構築 heal のみ）を持つ。検索は `FtsSearch` が `MATCH` クエリを
実行し、記事 ID をランク順に返す。

### DatabaseMerger（expect / actual）— 同期マージの要

ATTACH DATABASE マージは**専用の JDBC コネクション 1 本**で行う。SQLDelight の JVM ドライバは
ファイル DB に対してステートメントごとに新しいコネクションを開くため、`ATTACH` が後続のマージ文に
見えない。`DatabaseMerger` が attach → バージョン確認 → マージ（トランザクション）→ detach を
1 コネクションで完結させる。

### CloudSession / SyncRepository

`CloudSession` が現在の `CloudStorage`（Dropbox / Google Drive）を提供し、アクセストークンの自動リフレッシュを担う。
`SyncRepository` はダウンロード → マージ（`DatabaseMerger`）→ 新記事の増分索引（`indexMissing`）→
`VACUUM INTO` スナップショット生成（`DatabaseSnapshot`、コピー側で `articles_fts` を除外）→ アップロード
（rev チェック）、のフローとデバウンス（`SyncScheduler`）を実装する。ライブ DB の FTS は触らない。

### Provider / DI（Koin）

`appModule`（commonMain）にリポジトリ・サービス・ViewModel を登録。`platformModule`（desktop）に
HttpClient・TokenStorage・CloudSession・CloudConnectFlow を登録。ViewModel は単一ウィンドウの
デスクトップアプリのためアプリスコープの `single` として登録し、`koinInject()` で取得する。

### 記事リーダー（ネイティブ WebView）

`ui/home/ArticleDetailPane.kt` のリーダーは記事 HTML をネイティブ WebView
（`io.github.kdroidfilter.webview`。ヘビーウェイトな AWT `SwingPanel` が実 OS のブラウザビュー
— Windows は Edge WebView2、macOS は WebKit、Linux は WebKitGTK — をラップしたもの）で描画して
おり、Compose が描画するテクスチャではない。ペインの生存期間中は `if` の下に置かず常時
無条件でコンポーズする — Compose Desktop の `SwingInteropContainer` はヘビーウェイトな
コンポーネントが追加・削除・移動されるたびに、このペインだけでなく**ウインドウ全体**を
再検証＋再描画するため（調査の詳細は [known-issues.md](known-issues.ja.md) 参照）。その帰結として、
描画すべき記事が無い状態（「記事未選択」「本文なし」）は Compose の `Text` ではなく、同じ
WebView **内部**の HTML として描画する（`ui/article/ArticleWebViewHtml.kt` の
`articlePlaceholderHtml`／`articleNoContentHtml`。実記事用の `wrapArticleHtml` と同じ
`<style>` ブロックを共有し、どの状態でも同じテーマ色で塗られる）。リーダー上部のツールバーも
同様に常時表示し、未選択時はボタンを非表示にせず無効化する — これによりツールバーの Compose
構造（ひいてはリーダーの計測済みバウンズ）が状態間で常に同一に保たれる。

### デスクトップトレイ（プラットフォーム分岐）

`tray/KeryxTray.kt` が 3 実装のいずれかを選ぶ。

| プラットフォーム | 実装 | 理由 |
| --- | --- | --- |
| macOS | `MacTray`（生の AWT `TrayIcon`） | Compose の `Tray()` は `TrayIcon.setPopupMenu()` を使い、macOS では左右どちらのクリックでもメニューが開いてしまうため。 |
| Linux（SNI ホストあり） | `LinuxTray`（D-Bus StatusNotifierItem） | AWT は X11 で透過トレイアイコンを描画できないため（下記）。 |
| Windows / Linux（SNI ホスト無し） | Compose `Tray()` | そのままで問題ない。 |

**Linux で SNI が必要な理由**: `sun.awt.X11.XTrayIconPeer.IconCanvas.paint()` はアイコン描画の *前* に
24x24 のキャンバス全面をコンポーネント背景色で塗り潰し、さらに `sun.awt.X11.XSystemTrayPeer` は
トレイマネージャーの `_NET_SYSTEM_TRAY_VISUAL` を読まないため XEmbed ウィンドウにアルファチャンネルが
存在しない。したがって PNG の中身に関わらず AWT のトレイアイコンは必ず不透明（白）の四角の中に描画される。
SNI ならパネルへ生の ARGB ピクセルを渡せる。

専用のセッションバス接続（`SniConnection`、`withShared(false)`、well-known 名
`org.kde.StatusNotifierItem-<pid>-1`）に 2 つのオブジェクトを export する。

- `/StatusNotifierItem` — `SniStatusNotifierItem`（`org.kde.StatusNotifierItem`）。`IconPixmap` は
  バッジ付きグリフをビッグエンディアン ARGB32（`TrayPixmap.kt`）で複数サイズ提供する。`ItemIsMenu = false`
  にすることで、左クリックがメニューではなく `Activate` に届く。
- `/StatusNotifierItem/menu` — `SniDBusMenu`（`com.canonical.dbusmenu`。表示/非表示 + 終了）。
  ラベル変更時に revision を上げて `LayoutUpdated` を発火し、`AboutToShow` は現在のラベルと
  `GetLayout` が最後に返した内容を比較するため、シグナルが落ちても復旧する。

アイコンのアセットも同じ分岐に従う。透過が効いて 22px 以上で合成される 2 経路は outlined
（`tray_icon_outlined.png`）、Windows の通知領域と Linux の AWT フォールバックはフルカラー
（`tray_icon.png`）。後者はアイコンが小さく、ティントもされず、不透明な箱の上に描かれるため。

どちらの export オブジェクトも接続を保持せず、シグナル発火はコールバックとして注入するため、バス無しで
単体テストできる。デスクトップ通知は同じ接続上の `org.freedesktop.Notifications`（`LinuxNotifier`）で
AWT のバルーンを置き換える。その `image-data` ヒントは SNI ピクスマップのビッグエンディアン ARGB32 とは
異なり **RGBA** である点に注意。

検出は `main.kt` の `application {}` より前に行う（セッションバスが無応答でも起動が止まらないよう
タイムアウト付き）。セッションバスや `StatusNotifierWatcher` が無ければ `null` となり、AWT
フォールバックが選ばれる。起動 *後* にウォッチャーが現れた場合は再起動まで AWT 経路のままだが、
一度確立した後のウォッチャー再起動は `NameOwnerChanged` で復帰する。

これら一式は `expect`/`actual` ではなく `desktopMain` に置く。`main.kt` からしか到達せず、ViewModel や
Repository は触れず、Linux のパネルプロトコルにモバイル側の対応物が無いため。

### ネイティブファイルダイアログ（プラットフォーム分岐）

`platform/FilePicker.desktop.kt` の `defaultFilePickerBackend` は 2 つの実装のどちらかを選ぶ。
`NativeMenu.desktop.kt` の `defaultPopupHandle` と同じ Linux は Swing・他は AWT という分岐である。

| プラットフォーム | 実装 | 理由 |
| --- | --- | --- |
| macOS / Windows | `AwtFilePickerBackend`（`java.awt.FileDialog`） | AWT が実際のネイティブパネル（`NSSavePanel` / `GetOpenFileName`）に写像し、ネイティブの上書き確認も含めて提供する。 |
| Linux | `SwingFilePickerBackend`（`javax.swing.JFileChooser`） | `sun.awt.X11.XToolkit.createFileDialog()` は `GtkFileDialogPeer` を選ぶが、そのネイティブ GTK コールバックは、記事リーダーの WebView がプロセス内で WebKitGTK を 2 つ目の GTK コンシューマにした状態だと NULL の `JNU_GetEnv` の返り値を逆参照し、JVM をクラッシュさせる SIGSEGV になる（`known-issues.md` 参照）。`JFileChooser` は純粋な Swing でそのコードには一切到達せず、アプリの他の Linux Swing 画面と同じく FlatLaf にも追従する。 |

ダイアログの親ウィンドウは呼び出し元から渡すのではなく、デスクトップ版 `actual` の**内部**で解決する
（`KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow`、表示中の `Frame` へのフォール
バック付き）。`LocalNativeWindow` は常にメインウィンドウにしか解決されず、modeless な設定ウインドウ
から開いたダイアログの親としては不適切なため。`JFileChooser` にはネイティブな上書き確認が無い（AWT
バックエンドは OS からタダで得られる）ため、`SwingFilePickerBackend` はそれを明示的に復元している —
なぜクラッシュ修正に留めずその挙動まで復元したのかは `known-issues.md` を参照。

**将来課題**: 同じ `FilePickerBackend` の継ぎ目に `org.freedesktop.portal.FileChooser`（XDG デスクトップ
ポータル）バックエンドを追加できる。SNI トレイや AppMenu で既に使っている dbus-java 接続経由で、
KDE/GNOME 純正のダイアログ（かつサンドボックスに適合した挙動）が得られる — クラッシュ前の Linux の
`FileDialog` は GTK 経由で既にネイティブの上書き確認を持っていたので、ポータル経由のダイアログも
恐らく同様であり、`SwingFilePickerBackend` の明示的な `resolveSavePath`/`JOptionPane` によるフォール
バックは不要になる見込みである。ポータルバックエンドが無い環境では `JFileChooser` にフォールバック
する。検出は `KeryxTray` が SNI と AWT を選び分けるのと同じく、起動時にセッションバス上の
`org.freedesktop.portal.Desktop` の有無を確認する方式になる。

## ドメインモデルの方針

SQLDelight の生成クラス（`Feeds` / `Articles` / …）をそのまま各層で使う。列名は snake_case のまま
プロパティになる（例: `feed.site_url`）。真偽値・タイムスタンプは `Long`（0/1・Unix ミリ秒）で保持し、
表示時に kotlinx-datetime で変換する。別途ドメインモデルクラスは定義しない。

唯一の例外が `domain/ArticleRepository.kt` の **`ArticleListRow`**（記事一覧が描画する8列:
`id` / `feed_id` / `title` / `url` / `published_at` / `created_at` / `is_read` / `is_starred`）。
モデリングのためではなくコストのために存在する — `Articles` 全体は `content` / `summary` /
`search_text`、つまり記事本文を2重に持つため、一覧で `*` を選ぶと1回の emission が全記事の
テキスト量に比例してしまい、しかもこのクエリは `articles` と `feeds` への書き込みのたびに再実行される。
`articles.sq` の一覧クエリはこの8列だけを射影し `::ArticleListRow` でマップする。本文は選択された
記事について `getArticleById` で読むので、`_selectedArticle` は `Articles` のままである。
この読み込みは UI スレッド外で行い、最後の選択だけを反映する（latest-wins）— `content` を含む行を
JVM ドライバがステートメントごとに開く接続で読むため、マージやリフレッシュの書き込みロック下では
`busy_timeout` を UI スレッドで使い切りうるし、矢印キー長押しなら毎秒30回それが起きる。
`HomeViewModel` は同期的な `selectionCursorId` を併せて保持しており、キーボード操作は
最後に完了した読み込みではなくユーザーの実際の現在位置から進む。
射影を絞ると SQLDelight はクエリごとに別の型を生成するため、この手書きの共通型が
`watchArticles` の5分岐を単一の戻り値型に保っている。パラメータ順は SELECT の列順と位置で
結び付いている（`ArticleRepositoryTest.articleListRowMapsEveryProjectedColumnToItsOwnField` が担保）。

## ナビゲーション

`ui/navigation/Navigator.kt` の単純なスタック型ナビゲータで Setup / Home / Settings を切り替える。
記事ビューは Home 内のペイン（ルートではない）。
