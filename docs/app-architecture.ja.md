# アプリアーキテクチャ

[English](app-architecture.md)

## 設計方針

- レイヤードアーキテクチャ（UI → ViewModel → Repository → DataSource）
- Koin で依存性注入、androidx.lifecycle ViewModel で状態管理
- SQLDelight でローカル DB を型安全に管理
- 同期処理は Repository 層に閉じ込め、UI 層は同期の存在を意識しない
- 共有のプラットフォーム抽象は `commonMain` で宣言し、可能な場合は `jvmCommonMain` に実装する。
  それ以外はターゲットごとのソースセット（`desktopMain` / `androidMain`）に実装する。

## ディレクトリー構成

```text
composeApp/src/
  commonMain/kotlin/works/merc/keryx/app/
    core/      Constants, Result, KeryxException, ArticleFilter, AppNotification, Clock, DateTimeParser, CloudStorageAvailability(expect)
    data/local/   DatabaseDriverFactory(expect), FtsManager, FtsSearch, LocalSettings(Store)
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, OpmlImporter, OpmlOpenHandler（importOpmlAndNotify。デスクトップと Android の「`.opml` ファイル関連付け」で共有）, CloudSession, NotificationCenter, MergeSql, MergeFailureClassifier, MergeSchema, IdGenerator, CloudConnectFlow, OAuthConnectFlow, OAuthRedirectTransport（interface + CustomUri）, OAuthCallbackParams, StartupMaintenanceTasks（refreshFeedsAndNotify/checkForUpdateAndNotify/maybeRebuildFtsIndex）
    di/           AppModule（+ expect platformModule）
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot, DatabaseFile（すべて expect）
    ui/           theme/, navigation/, setup/, home/（3ペイン + 検索 + 通知センター）, article/, settings/, i18n/
    LaunchArg.kt  起動時の引数（`keryx://` URI か `.opml` パスか）を分類する — プラットフォーム非依存、パッケージ直下
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq（7 テーブル）
  commonMain/composeResources/  values/strings.xml, drawable/（アイコンは SVG ではなく Android
    Vector Drawable XML — Compose Multiplatform の SVG デコーダはデスクトップ/iOS 専用で Android では
    実行時にクラッシュするため。VectorDrawable XML は `painterResource` が全ターゲットで描画できる唯一の画像形式）
  jvmCommonMain/kotlin/…/  デスクトップと Android の両方が共有する actual（どちらのプラットフォーム
    API にも依存しない）: FileIO, Gzip, Sha1, ContentDigest, Pkce, FileTokenStorage, AppInfo,
    CloudStorageAvailability（後者2つは共有生成 BuildConfig を読むだけ）
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt（runStartupTasks/backgroundUpdateLoop/handleOpenedOpmlFile というデスクトップ固有のオーケストレーションのみ。実際のメンテナンス処理は commonMain の StartupMaintenanceTasks に委譲）+ jvmCommonMain がカバーしない expect の actual（DatabaseDriverFactory, AppDirs, FilePicker, DatabaseMerger, PlatformModule）+ LoopbackRedirectTransport, OAuthUriParser, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage 実装（Keyring/File/SecurityCliTokenStorage）, DesktopOs（isMacOs/isWindows/isLinux/isTouchPrimary=false/hasNativeAppMenu=true）, DesktopLookAndFeel（Swing L&F: Linux は FlatLaf）
    tray/      KeryxTray（プラットフォーム分岐）, MacTray, LinuxTray + StatusNotifierItem/dbusmenu の D-Bus オブジェクト
  androidMain/kotlin/…/  jvmCommonMain がカバーしない expect の actual: DatabaseDriverFactory（バンドル
    SQLite、後述）, DatabaseFile（`databaseFilePath()` — `Context.getDatabasePath` で、
    AppDirs.appDataDir()/`Context.filesDir` とは別ディレクトリになる。db-schema.ja.md 参照）,
    AppDirs/BrowserOpener/ClipboardEntries（AndroidAppContext 経由 — KeryxApplication.onCreate
    で一度だけ設定される静的 Context ホルダ）, PlatformModule（Ktor OkHttp エンジン、Dropbox/OneDrive
    プロバイダを登録した CloudSession — 下記 Provider/DI 参照。加えて AndroidNotificationSink、下記
    「バックグラウンド更新」参照）, CloudStorageAvailability（Dropbox/OneDrive は実判定、Google Drive は
    `false` 固定 — 理由は sync-architecture.ja.md の「Android で Google Drive が未対応な理由」参照）,
    KeryxTextField/KeryxAlertDialog/KeryxTabDialog（素の M3。
    KeryxTabDialog はエッジツーエッジ対応で safe-drawing padding 済み）,
    DatabaseMerger/DatabaseSnapshot（専用の `io.requery.android.database.sqlite.SQLiteDatabase`
    接続に対する実装 — デスクトップ実装の専用 JDBC 接続に相当。下記「DatabaseMerger」参照）,
    AndroidSqliteSupport.kt（`NoOpDatabaseErrorHandler` — バンドル SQLite の既定ハンドラは破損と
    判定した DB ファイルを削除する。AAR の逆アセンブルで確認済み。加えて両者が共有する
    `setBusyTimeout()`/`userVersion()`）, FilePicker（Storage Access Framework の
    `OpenDocument`/`CreateDocument`。この `expect object` は自身では `ActivityResultLauncher` を
    持てないため AndroidFilePickerHost 経由）, KeystoreTokenStorage（クラウドプロバイダーごとに
    Android Keystore 保持の AES-256/GCM 鍵。sync-architecture.ja.md の「トークン保存先」参照）,
    AndroidOAuthCallback.kt（`dispatchOAuthCallbackIfPresent`。`keryx://` OAuth リダイレクト用に
    `:androidApp` の `MainActivity` から呼ばれる — デスクトップの `main.kt` の URI ルーティングに相当）,
    AndroidOpmlOpen.kt（`handleOpmlOpenIfPresent`。`.opml` の「Keryx で開く」`ACTION_VIEW` インテント用に
    同じ `MainActivity` から呼ばれる — デスクトップの `.opml` ファイル関連付けに相当。
    `platform/FilePicker.android.kt` の `readTextFromUri` で `content://` `Uri` を読み取り、
    commonMain の `domain/OpmlOpenHandler.kt` に委譲する）,
    nativeContextMenu（適応レイアウトのフェーズで実装した実際の
    長押し DropdownMenu — タップと長押しの判別は KDoc 参照）, BackHandler（`androidx.activity.compose.BackHandler`
    へ委譲）, PlatformOs（isTouchPrimary = true, hasNativeAppMenu = false — Android にはメニューバーが
    無いため、FeedListToolbarRow/GeneralTab が独自の設定/バージョン情報導線を持つ）,
    SelfUpdateCheck（インストール元パッケージ名に基づく判定、下記「バックグラウンド更新」参照）,
    NotificationPermission（`POST_NOTIFICATIONS` 用に `rememberLauncherForActivityResult` をラップ）+
    AndroidStartupTasks.kt（`runAndroidStartupTasks`。`:androidApp` の `MainActivity` から呼ばれる）+
    background/（`FeedRefreshWorker` + `BackgroundRefresh.kt` の `startBackgroundRefresh`。
    `WorkManager` ベース — Android のバックグラウンド/通知の全体像は
    [background-update.ja.md](background-update.ja.md) を参照）
  commonTest/ + desktopTest/ + androidDeviceTest/（DatabaseMerger/DatabaseSnapshot の Android 実装向け
    計装テスト — バンドル SQLite ネイティブライブラリの読み込みに実機/エミュレータが必要。
    testing.ja.md 参照）
```

パッケージルートは `works.merc.keryx.app`（`keryx.merc.works` の逆順 DNS）。

ルート直下の別モジュール `androidApp`（`com.android.application`。上記の Kotlin Multiplatform
ソースセット構成には含まれない）は `AndroidManifest.xml`、`KeryxApplication`（プロセス全体の初期化:
`AndroidAppContext.init`、`startKoin`、`configureImageLoader`、FTS バックフィルの `ensureIndexed()`、
`startBackgroundRefresh`）、`MainActivity`（`setContent { App() }`、続けて
`runAndroidStartupTasks`）のみを持つ。これが別モジュールになっているのは、AGP 9 の
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
実行することだけ。Android の `actual` は別経路で同じカテゴリを供給する — その
`android.database.sqlite.SQLiteException` は（デスクトップが読む JDBC ドライバの数値コードと違い）
数値コードを公開しないため、投げられた例外自身のサブクラスで分岐する
（`SQLiteConstraintException`/`SQLiteDatabaseCorruptException` → `CORRUPT_OR_CONSTRAINT`、
いくつかの named サブクラス → `OTHER`、素の `SQLiteException` → `STATEMENT_ERROR`。ここは
`validateSchema` が解消すべき曖昧さそのものと一致する） — それ以外は同じ attach → バージョン確認 →
マージ → detach の手順を、専用の `io.requery.android.database.sqlite.SQLiteDatabase` 接続上で、
ライブラリの既定ではなく `NoOpDatabaseErrorHandler` を指定して開いて実行する（既定ハンドラは破損と
判定した DB ファイルを削除する — バンドル AAR の逆アセンブルで確認済み。`platform/AndroidSqliteSupport.kt`
参照）。

### CloudSession / SyncRepository

`CloudSession` が現在の `CloudStorage`（デスクトップは Dropbox / Google Drive / OneDrive、Android は
Dropbox / OneDrive — sync-architecture.ja.md の「Android で Google Drive が未対応な理由」参照）を
提供し、アクセストークンの自動リフレッシュを担う。
`SyncRepository` はダウンロード → マージ（`DatabaseMerger`）→ 新記事の増分索引（`indexMissing`）→
`VACUUM INTO` スナップショット生成（`DatabaseSnapshot`、コピー側で `articles_fts` を除外）→ アップロード
（rev チェック）、のフローとデバウンス（`SyncScheduler`）を実装する。ライブ DB の FTS は触らない。
`SyncRepository` の `localDbPath` の既定値は `platform/DatabaseFile.kt` の `databaseFilePath()`
— プラットフォームごとにライブ DB の実パスを解決する唯一の `expect` 関数（db-schema.ja.md 参照）。

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

`platform/NativeMenu.android.kt` の `nativeContextMenu` は、同じ呼び出し箇所（記事行、
フィード／フォルダー／タグ行）を長押しで開く Material 3 `DropdownMenu` で裏打ちする。実装は
自前の `awaitEachGesture` ループで、最初の押下（down）は消費しない。押下が
`viewConfiguration.longPressTimeoutMillis` の間、離されも（up）どこか他所で消費されもせずに
（例えば `LazyColumn` のスクロールに奪われる、など）生き残った場合にのみ長押しと判定し、そこから
残りのジェスチャーを消費し始める — こうすることで、直前でチェーンされている
`ui/home/ListRowChrome.kt` の `listRowClickable`（より外側のノード。Compose のポインタ入力
`Main` パスは同一イベントに対して祖先より先に子孫のノードを再開するため）が同じ押下に対して
`onClick` を重ねて発火することがない。`NativeSubMenu` はネストしたポップアップを開くのではなく
その場でドリルダウンする（先頭の「戻る」行がトップレベルをサブメニュー自身の項目に差し替える）。

`platform/NativeMenu.desktop.kt` の `defaultPopupHandle` は、同じ呼び出し箇所をデスクトップでは
長押しではなく右クリックで開き、2 つの実装のどちらかで裏打ちする。

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

### アイコンセット

`ui/common/KeryxIcons.kt` が全 UI 呼び出し箇所の唯一の間接参照点になっており（意味的な名前 →
`composeResources/drawable/` 配下のバンドル Android Vector Drawable XML）、現在は Tabler Icons
（MIT）を使用している（デスクトップ3OS共通でMac寄りの見た目に寄せるため。詳細は `ui-guidelines`
skill）。Android のネイティブな視覚言語は Material Design であるため、Android ターゲットだけ
Material 系アイコン（Material Symbols）に差し替えることを検討する余地がある — `KeryxIcons` を
`expect`/`actual` に分割すればプラットフォームごとに個別のアイコンセットを出し分けられるが、
現時点ではまだ着手していない。iOS/iPadOS/macOS がいずれネイティブ SwiftUI 化された場合
（`external-spec.md` §2 の想定どおり）、そちらは Kotlin の `KeryxIcons` とは無関係の別コードベース
になるため、SF Symbols を `Image(systemName:)` で直接使えばよく、Kotlin 側に追加の差し替え機構は
不要。つまり将来「SwiftUI = SF Symbols / Android = Material / Windows・Linux = 現行の Tabler」
という3分岐になっても、Kotlin 側で実質必要になるのは上記の Android 用 `expect`/`actual` 分割だけ
である。

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

### Home の適応的ペインレイアウト

`ui/home/HomePaneLayout.kt` は、Home の3ペイン（フィード一覧・記事一覧・記事詳細）のうち
いくつを横並びで表示するかを、利用可能な幅だけから解決する: `PaneLayout.Triple`（3ペインすべて —
デスクトップは `WINDOW_MIN_WIDTH` が常に `TRIPLE_PANE_MIN_WIDTH` 以上であるため常にここに解決される。
`core/Constants.kt` の当該定数の KDoc 参照）、`PaneLayout.Dual`（記事一覧 + どちらか一方の隣接ペイン）、
`PaneLayout.Single`（1ペインのみ、スマートフォン幅）のいずれか。ナビゲーションスタック自体は常に
3段（`HomePane.FeedList` → `ArticleList` → `ArticleDetail`）であり、狭いレイアウトはそのうち表示する
段数を減らしているに過ぎない。`HomePane.ordinal + 1` がそのままスタックの現在の深さを兼ねるため、
`HomeScreen` は別途深さの状態を持つ必要がない — フィルターや記事の選択で深さが進み
（`onSelectionAdvance`。`Triple` では no-op）、`platform/BackHandler`（Android では実際の戻る
ジェスチャー/ボタンを横取りし、デスクトップでは no-op）が1段戻す。`PaneLayout.Dual` は、そのスタック上を
スライドする2ペインの窓であり、単純な隣接ペア表示ではない: 記事一覧はどの深さでも表示される2ペインの
一方であり続けるため、記事にドリルインするとフィード一覧が記事詳細ペインに入れ替わる形になり、
一覧自体が画面外にスライドすることはない。

これが、記事リーダーの WebView を無条件にコンポーズし続けること（下記「記事リーダー」参照）が
デスクトップにおいて安全である理由でもある: デスクトップは常に `Triple` にしか解決されないため、
WebView をホストするペインを含む3ペインすべてがアプリのライフタイム全体でマウントされ続ける。
`Single`/`Dual` は、対象のペインが現在表示されていない場合にそれをアンマウントするが、これは
Android では（重量級 AWT インターロップの懸念が無いため）問題ない。
