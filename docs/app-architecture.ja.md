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
    domain/       Feed/Article/Tag/Settings/SyncRepository, CloudSession, NotificationCenter, MergeSql, MergeFailureClassifier, MergeSchema, IdGenerator, CloudConnectFlow, OAuthConnectFlow, OAuthRedirectTransport（interface + CustomUri）, OAuthCallbackParams, StartupMaintenanceTasks（refreshFeedsAndNotify/checkForUpdateAndNotify/maybeRebuildFtsIndex）
    di/           AppModule（+ expect platformModule）
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot（すべて expect）
    ui/           theme/, navigation/, setup/, home/（3ペイン + 検索 + 通知センター）, article/, settings/, i18n/
    LaunchArg.kt  起動時の引数（`keryx://` URI か `.opml` パスか）を分類する — プラットフォーム非依存、パッケージ直下
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq（7 テーブル）
  commonMain/composeResources/  values/strings.xml, drawable/（アイコンは SVG ではなく Android
    Vector Drawable XML — Compose Multiplatform の SVG デコーダはデスクトップ/iOS 専用で Android では
    実行時にクラッシュするため。VectorDrawable XML は `painterResource` が全ターゲットで描画できる唯一の画像形式）
  jvmCommonMain/kotlin/…/  デスクトップと Android の両方が共有する actual（どちらのプラットフォーム
    API にも依存しない）: FileIO, Gzip, Sha1, ContentDigest, Pkce, FileTokenStorage, AppInfo,
    CloudStorageAvailability（後者2つは共有生成 BuildConfig を読むだけ）
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt（runStartupTasks/backgroundUpdateLoop/handleOpenedOpmlFile というデスクトップ固有のオーケストレーションのみ。実際のメンテナンス処理は commonMain の StartupMaintenanceTasks に委譲）+ jvmCommonMain がカバーしない expect の actual（DatabaseDriverFactory, AppDirs, FilePicker, DatabaseMerger, PlatformModule）+ LoopbackRedirectTransport, OAuthUriParser, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage 実装（Keyring/File/SecurityCliTokenStorage）, DesktopOs（isMacOs/isWindows/isLinux）, DesktopLookAndFeel（Swing L&F: Linux は FlatLaf）
    tray/      KeryxTray（プラットフォーム分岐）, MacTray, LinuxTray + StatusNotifierItem/dbusmenu の D-Bus オブジェクト
  androidMain/kotlin/…/  jvmCommonMain がカバーしない expect の actual: DatabaseDriverFactory（バンドル
    SQLite、後述）, AppDirs/BrowserOpener/ClipboardEntries（AndroidAppContext 経由 — KeryxApplication.onCreate
    で一度だけ設定される静的 Context ホルダ）, PlatformModule（Ktor OkHttp エンジン、プロバイダ未登録の
    CloudSession — 下記 Provider/DI 参照）, KeryxTextField/KeryxAlertDialog/KeryxTabDialog（素の M3）,
    FilePicker/DatabaseMerger/DatabaseSnapshot（フェーズ4までのスタブ。例外を投げるが CloudSession に
    プロバイダが無い間は到達不能）, nativeContextMenu（現状 no-op — 理由は KDoc 参照）
  commonTest/ + desktopTest/
```

パッケージルートは `works.merc.keryx.app`（`keryx.merc.works` の逆順 DNS）。

ルート直下の別モジュール `androidApp`（`com.android.application`。上記の Kotlin Multiplatform
ソースセット構成には含まれない）は `AndroidManifest.xml`、`KeryxApplication`（プロセス全体の初期化:
`AndroidAppContext.init`、`startKoin`、`configureImageLoader`、FTS バックフィルの `ensureIndexed()`）、
`MainActivity`（`setContent { App() }`）のみを持つ。これが別モジュールになっているのは、AGP 9 の
`com.android.application` プラグインが Kotlin Multiplatform プラグインと同一モジュールで併用できない
ため — `composeApp` は代わりに `com.android.kotlin.multiplatform.library` による Android ライブラリで、
`androidApp` がそれに依存してインストール可能な APK を生成する。

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

Android の `actual` は `AndroidSqliteDriver` を生成する。こちらは `onCreate`/`onUpgrade` コールバックで
`Schema.create`/`migrate` を自動的に駆動するため、desktop のような `PRAGMA user_version` の手動管理は
不要。端末標準の SQLite ではなく `com.github.requery:sqlite-android` のバンドル SQLite
（`RequerySQLiteOpenHelperFactory`、`androidx.sqlite.db.SupportSQLiteOpenHelper.Factory` の実装）を
使う — AOSP の SQLite ビルドは FTS5 自体を含んでいないため、`articles_fts` の `tokenize='trigram'` は
どの API レベルでも端末標準の SQLite では動作しない。詳細な理由と撤退条件は
`.claude/rules/android-sqlite-bundling.md` を参照。`busy_timeout`/`foreign_keys` は
`AndroidSqliteDriver.Callback.onConfigure` から設定する。なお `PRAGMA busy_timeout=N` は結果行として
新しい値を返すため、`execSQL` ではなく `SupportSQLiteDatabase.query` を経由する必要がある点に注意
（requery は結果行を返す文を `execSQL` に渡すと拒否する）。

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

プラットフォーム固有なのは SQLite ドライバとのやりとりだけである。判定*ロジック*自体は `commonMain` に
ある: `domain/MergeFailureClassifier`（純粋関数。失敗カテゴリ + エラーコード名 + 遅延評価のスキーマ検証
コールバック → `CloudDataIncompatibleException?`）と `domain/MergeSchema`（スキーマバージョンごとの期待
テーブル/カラム。純粋なデータ）。デスクトップの `actual` が担うのは、原因チェーンを辿って
`org.sqlite.SQLiteException` を見つけ、`resultCode.code and 0xFF` を `SqliteFailureCategory` へ変換し、
判定結果をログに出し、`validateSchema` で `MergeSchema.EXPECTED_SCHEMAS` に対して `PRAGMA table_info` を
実行することだけ。将来の Android の `actual`（その `SQLiteException` は数値コードを公開しない）も、
同じカテゴリを与えるだけで済む。

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

`ArticleWebView` は `webSettings.desktopWebSettings.dataDirectory` も明示的に設定しており、
`AppDirs.cacheDir()` 配下の `webview` サブディレクトリを、デスクトップ 3 OS すべてに同一に適用している
（OS 分岐なし）。デフォルトの `null` のままだと WebView2 は実行ファイルの隣に自分のデータフォルダを
作ろうとし、その場所が書き込み不可の場合は Access Denied で失敗する — 調査の詳細は
[known-issues.md](known-issues.ja.md) 参照（この生成失敗の例外が uncaught のまま伝播し、ライブラリの
生成リトライタイマが止まらなくなることが、クリック時にアプリ全体がフリーズする原因でもあった）。

### デスクトップトレイ（プラットフォーム分岐）

`tray/KeryxTray.kt` が 4 実装のいずれかを選ぶ。

| プラットフォーム | 実装 | 理由 |
| --- | --- | --- |
| macOS | `MacTray`（生の AWT `TrayIcon`） | Compose の `Tray()` は `TrayIcon.setPopupMenu()` を使い、macOS では左右どちらのクリックでもメニューが開いてしまうため。 |
| Linux（SNI ホストあり） | `LinuxTray`（D-Bus StatusNotifierItem） | AWT は X11 で透過トレイアイコンを描画できないため（下記）。 |
| Windows | `WindowsTray`（生の AWT `TrayIcon` + `JPopupMenu`） | `Tray()` のメニューは `java.awt.PopupMenu` であり、JDK の Windows ピアは表示スケール 100% 超でラベルを重ねて描画するため。コンテキストメニューを AWT から移したのと同じ不具合（下記「ネイティブコンテキストメニュー」参照）。 |
| Linux（SNI ホスト無し） | Compose `Tray()` | そのままで問題ない。 |

`MacTray` と `WindowsTray` はどちらも生の `TrayIcon` を駆動して `Tray()` を迂回するが、理由は無関係で
あり、意図的な差異が 2 つある。1 つは、`MacTray` のインボーカ用 Frame は常時表示・フォーカス不可である
（AWT の `PopupMenu` は自前のネイティブなモーダルループを持つため）のに対し、`WindowsTray` のそれは
フォーカス可能で使用時以外は非表示である（`JPopupMenu` は所有ウィンドウがフォーカスを保持し、かつ
失える場合にのみ外側クリックで閉じるため）。もう 1 つは、`MacTray` がイベント自身の
`xOnScreen`／`yOnScreen` からメニュー位置を決めるのに対し、`WindowsTray` は `trayMenuAnchor` を通して
`MouseInfo` を使うこと。`TrayIcon` の MouseEvent は Windows では**デバイスピクセル**、macOS では
**ポイント**を運ぶ一方、`Window.setLocation` はどちらでもユーザー空間を要求するためである。両者とも
`newArticleNotifications` を自分で消費する（キューされた `TrayState` 通知を実際の OS 通知に変えるのは
Compose の `Tray()` だけであるため）。

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

### ネイティブコンテキストメニュー（プラットフォーム分岐）

`platform/NativeMenu.desktop.kt` の `defaultPopupHandle` が、すべての `Modifier.nativeContextMenu`
呼び出し箇所（記事行、フィード／フォルダー／タグ行）を 2 つの実装のどちらかで裏打ちする。

| プラットフォーム | 実装 | 理由 |
| --- | --- | --- |
| macOS | `AwtPopupHandle`（`java.awt.PopupMenu`） | AWT が本物の `NSMenu` に写像し、かつ AppKit はポイント基準なので、モディファイアが算出する Dp 空間の座標をデバイスピクセルへ変換する必要がない。 |
| Windows / Linux | `SwingPopupHandle`（`javax.swing.JPopupMenu`） | Linux では AWT の `PopupMenu` が Swing の Look & Feel を無視する heavyweight な XAWT ウィジェットで、Motif 世代の見た目のままになるため。Windows では JDK の AWT メニューピアが Java のユーザー空間とデバイスピクセルの変換を一切行わず、メニューが `ウィンドウ原点 + クリックオフセット ÷ スケール` に開き、行の高さがそこに描かれる文字の `1 / スケール` にしかならずラベルが重なるため。いずれも `known-issues.md` に詳述。 |

`defaultPopupHandle` の `macOs` 引数（既定値はプロセス定数）は、`NativeMenuTest` がどの CI ホストでも
対応関係を固定できるようにするためだけのものである。アプリではなく選択されたバックエンドに追随して
変わる挙動が 2 つある。セパレータが Swing 経路では `JPopupMenu.Separator`、AWT 経路では `"-"` ラベルの
`MenuItem` になること、そして修飾キーなしのショートカット（F2 / Delete）がアクセラレータ列に表示される
のは Swing 経路だけであること（`java.awt.MenuShortcut` は常にプラットフォームの主修飾キーを含んでしまい、
構造的に表現できない）。`forceHeavyweight`（`isLightWeightPopupEnabled = false`）は Swing のポップアップが
記事リーダーの WebView の背後に描画されるのを防ぐためのもので、Linux では FlatLaf があるので冗長だが、
`installLookAndFeel` のシステム L&F 分岐を通る Windows では必須である。

### ネイティブファイルダイアログ（プラットフォーム分岐）

`platform/FilePicker.desktop.kt` の `defaultFilePickerBackend` は 2 つの実装のどちらかを選ぶ。
`NativeMenu.desktop.kt` の `defaultPopupHandle` と同じ Linux は Swing・他は AWT という分岐だが、
ファイルダイアログは Windows を AWT 側に残す点だけが異なる。Windows の `java.awt.FileDialog` は本物の
`GetOpenFileName` パネルであり、メニューピアのようなスケーリングの問題が無いためである。

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
