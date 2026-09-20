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
    core/      Constants, Result, KeryxException, ArticleFilter, AppNotification, Clock, DateTimeParser, CloudStorageAvailability(expect),
               AppInfo, CloudBackupPath, HtmlText, Log, SearchQuery, SemVer, SqliteFile, UntrustedText, UpdateDistribution
    data/local/   DatabaseDriverFactory(expect), FtsManager, FtsSearch, LocalSettings(Store)
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels, UpdateDownloader, ReleaseFeedSource（アプリ内アップデート——後述の「アプリ内アップデート」参照）
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens,
                  CloudFileTransfer, SecretStoreTokenStorage
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, OpmlImporter, OpmlOpenHandler（importOpmlAndNotify。デスクトップと Android の「`.opml` ファイル関連付け」で共有）, CloudSession, NotificationCenter, MergeSql, MergeFailureClassifier, MergeSchema, IdGenerator, CloudConnectFlow, OAuthConnectFlow, OAuthRedirectTransport（interface + CustomUri）, OAuthCallbackParams, StartupMaintenanceTasks（refreshFeedsAndNotify/checkForUpdateAndNotify/maybeRebuildFtsIndex）, UpdateChecker/UpdateRepository/UpdateAsset/UpdateInstallPolicy/UpdateInstaller（expect 相当の interface）/AvailableUpdate/UpdateState（アプリ内アップデート——下記「アプリ内アップデート」参照）
    di/           AppModule（+ expect platformModule）, HttpClientFactory, ImageLoaderSetup
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot, DatabaseFile, InstallLocation, FileSystemExtras, ZipExtractor,
                  BackHandler, ClipboardEntries, ContentDigest, CursorIcons, FileSelector, Gzip, NativeMenu, NativeWebViewAccessibility,
                  NativeWebViewScrollbar, NativeWebViewSupport, NativeWebViewVisibility, NotificationPermission, PlatformOs, PlatformScrollbar,
                  SelfUpdateCheck, Sha1, WindowChrome, WindowDragArea（大半が expect 宣言。InstallLocation.kt は既に唯一の `expect fun` をプレーンなデータ型と同居させている——下記「Android」の `ScrollIndicatorOverlay.kt`／`ScrollIndicatorGeometry.kt` も参照。こちらは同じディレクトリに置かれているだけの、自身の expect を持たないプラットフォーム非依存の共有 Compose コード）
    ui/           theme/, navigation/, setup/, home/（アダプティブな1/2/3ペインレイアウト + 検索 +
                  通知センター）, article/, settings/, i18n/, common/（KeryxTextField/KeryxDialogs/
                  KeryxIcons/FlatButtons/FlatToggles/SegmentedControl/KeryxSearchBar/… — expect/actual
                  分割された、全ペイン共通のプレーンな M3 見た目のコンポーネント）, menu/（MenuController）
    LaunchArg.kt  起動時の引数（`keryx://` URI か `.opml` パスか）を分類する — プラットフォーム非依存、パッケージ直下
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq（7 テーブル）
  commonMain/composeResources/  values/strings.xml（日本語、デフォルト/フォールバック）,
    values-en/strings.xml（英語、同じキー集合）, drawable/（アイコンは SVG ではなく Android
    Vector Drawable XML — Compose Multiplatform の SVG デコーダはデスクトップ/iOS 専用で Android では
    実行時にクラッシュするため。VectorDrawable XML は `painterResource` が全ターゲットで描画できる唯一の
    *ベクター*形式——ビットマップ資産（`app_icon.png`、`onedrive.png`、トレイの PNG 群）は対象外）
  jvmCommonMain/kotlin/…/  デスクトップと Android の両方が共有する actual（どちらのプラットフォーム
    API にも依存しない）: FileIO, Gzip, Sha1, ContentDigest, Pkce, FileTokenStorage,
    AppInfo（共有生成 BuildConfig を読むだけ）, FileSystemExtras,
    ZipExtractor（アプリ内アップデート——下記「アプリ内アップデート」参照）,
    di/CloudPlatformModule.kt（両プラットフォームの platformModule が呼ぶ共有クラウドプロバイダー DI 配線
    ——cloudSessionSingles, dropboxProvider, oneDriveProvider）,
    domain/OAuthUriParser.kt（parseOAuthUri。デスクトップと Android の `keryx://` リダイレクト処理が共有）
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt（runStartupTasks/backgroundUpdateLoop/handleOpenedOpmlFile というデスクトップ固有のオーケストレーションのみ。実際のメンテナンス処理は commonMain の StartupMaintenanceTasks に委譲）+ jvmCommonMain がカバーしない `platform/` expect の actual（例: DatabaseDriverFactory, AppDirs, FilePicker, DatabaseMerger, DatabaseSnapshot, DatabaseFile, PlatformModule, InstallLocation, PlatformScrollbar, BackHandler, ClipboardEntries, CursorIcons, NotificationPermission, NativeMenu, SelfUpdateCheck, WindowChrome、および WebView をホストする4本 NativeWebViewSupport/NativeWebViewScrollbar/NativeWebViewAccessibility/NativeWebViewVisibility）+ LoopbackRedirectTransport, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage 実装（KeyringTokenStorage/SecurityCliTokenStorage/LibSecretTokenStorage——1番目と3番目は commonMain の SecretStoreTokenStorage から outcome 合成ロジックを継承するが、SecurityCliTokenStorage は同じロジックを自前で実装している）, DesktopOs（isMacOs/isWindows/isLinux/isSnap/isTouchPrimary=false/hasNativeAppMenu=true/hasSystemTray=true）, DesktopLookAndFeel（Swing L&F: Linux は FlatLaf。テキストアンチエイリアスヒントの正規化も担う——hint が存在しない場合、DEFAULT、OFF のいずれでもグレースケールアンチエイリアスに解決され、Swing 面のテキストが Compose 描画部と並んだ際にジャギーにならない）。さらに、`expect` を持たないパッケージルート直下のデスクトップ専用クラスとして: IconBadge（Dock/タスクバー/ウインドウアイコンの未読件数バッジ——external-spec.ja.md §7 参照）、MacActivationPolicy（生の `objc_msgSend` 呼び出し——known-issues.md の「macOS: clicking a notification banner does not restore a tray-hidden window」内「What a real fix would need」参照）、WindowStatePersistence
    tray/      KeryxTray（プラットフォーム分岐）, MacTray, LinuxTray, WindowsTray +
               StatusNotifierItem/dbusmenu の D-Bus オブジェクト
    appmenu/   KDE Global Menu / D-Bus アプリケーションメニュー連携（AppMenuBarHost, AppMenuConnection,
               AppMenuDBusMenu, AppMenuRegistrar）— external-spec.ja.md §9 参照
    platform/update/  DesktopUpdateInstaller, UpdateScriptWriter（純粋な自己置換／msiexec スクリプトのテンプレート）, ProcessLauncher/RealProcessLauncher（テストがフェイクに差し替える detached 起動のシーム）, ArchiveExtractor（macOS は DittoArchiveExtractor——署名済みバンドルが自身の symlink を封印しているため。それ以外はインプロセスの InProcessArchiveExtractor）, CodeSigningVerifier/RealCodeSigningVerifier（`codesign --verify` のシーム）
  androidMain/composeResources/drawable/  Android の `KeryxIcons` actual 自身のアイコンセット
    — Material Symbols Outlined（Apache-2.0）、ベクター drawable 43 個 — 下記「アイコンセット」参照
  androidMain/kotlin/…/  jvmCommonMain がカバーしない expect の actual: DatabaseDriverFactory（バンドル
    SQLite、後述）, DatabaseFile（`databaseFilePath()` — `Context.getDatabasePath` で、
    AppDirs.appDataDir()/`Context.filesDir` とは別ディレクトリになる。db-schema.ja.md 参照）,
    InstallLocation（常に ANDROID_SIDELOADED か ANDROID_STORE のどちらか——下記「アプリ内アップデート」
    参照）,
    PlatformScrollbar（`VerticalScrollbarIfNeeded` — `ScrollableState` と 2 つの inset コールバック
    （`trackStartInsetPx`／`trackEndInsetPx`）を commonMain の `platform/ScrollIndicatorOverlay.kt` の
    `ScrollIndicatorOverlay` に渡すだけの薄い actual 2 本。デスクトップのドラッグ可能な
    `VerticalScrollbar` ではなく、非操作でフェードするオーバーレイ・スクロールインジケーター。契約の
    全体は `ui-guidelines` スキルの「Scroll indicators」参照。そのオーバーレイの thumb 比率計算を担う
    `platform/ScrollIndicatorGeometry.kt` の純粋関数群（`scrollIndicatorLengthFraction`／
    `scrollIndicatorStartFraction`／`minLengthFraction`——それぞれの役割は `testing.ja.md` 参照）は、
    デスクトップからは一切呼ばれないのに commonMain に置かれている——理由は下記
    `canInstallAndroidApkUpdate` と同じ。つまみの位置・長さは、`LazyListState` の**画面内に見えている**
    アイテムの平均サイズから導く推定値であるため、行の高さが不揃いなリスト（`FeedListPane` の
    スティッキーヘッダー／フォルダーヘッダー／区切り線／フィード行の混在）ではスクロールに伴って
    どの行が画面に入るかでつまみの長さがわずかに揺れる——Android 自身の一覧が使うのと同じ推定であり、
    理由も同じ: これは非操作のインジケーターであって正確な位置指示ではないため受け入れる）,
    AppDirs/BrowserOpener/ClipboardEntries（AndroidAppContext 経由 — KeryxApplication.onCreate
    で一度だけ設定される静的 Context ホルダ）, PlatformModule（Ktor OkHttp エンジン、Dropbox/OneDrive
    プロバイダに加え Play 開発者サービスがある端末では Google Drive も登録した CloudSession — 下記
    Provider/DI 参照。加えて AndroidNotificationSink、[background-update.ja.md](background-update.ja.md) 参照）,
    CloudStorageAvailability（Dropbox/OneDrive は BuildConfig のキーを見るが、Google Drive は
    ビルド時のクライアント ID ではなく Play 開発者サービス経由のため、プロセスごとに一度だけ
    `GoogleApiAvailability` で判定する — sync-architecture.ja.md の「Android での Google Drive」参照）,
    KeryxTextField/KeryxAlertDialog/KeryxIcons/FlatButtons/FlatToggles/
    SegmentedControl（素の M3。後の4つも同様に `expect`/`actual` 分割されており、Android 側は
    Material Symbols（アイコン）や M3 の `Button`/`FilledTonalButton`/`TextButton`/`Switch`/
    `Checkbox`/`SingleChoiceSegmentedButtonRow`+`SegmentedButton`/`FilterChip`（コンポーネント）を
    そのまま使う — 詳細は下記「アイコンセット」参照）,
    KeryxTabDialog（ほぼ全画面のモーダル `Dialog`。エッジツーエッジ対応で safe-drawing padding 済み。
    本物の M3 `TopAppBar`（戻る矢印＋画面名）を、デスクトップ側の自前タブバーとは異なる本物の M3
    `PrimaryScrollableTabRow`/`Tab` の上に載せる — 詳細は `ui-guidelines` スキル参照）,
    PlatformTheme（`platformShapes` は M3 既定の `Shapes()`、`ProvidePlatformInteraction` は
    no-op — `LocalIndication`/`LocalRippleConfiguration` を M3 既定のままにすることで、あらゆる
    `clickable` と M3 部品が本物のリップルを持つようになる。external-spec.ja.md の「UI 方針」参照）、
    `ui/home/ListRowChrome.kt` の `listRowSurface`（`expect`/`actual` ではなく単一の commonMain 関数——
    両 `ListRowKind` で同じ 12dp インセットを `listRowHorizontalMargin()` で共有（`isTouchPrimary` で
    分岐: タッチ 12dp / マウス 8dp。タッチ側は M3 の `NavigationDrawerItemDefaults.ItemPadding` と一致）
    — し、`listRowShape(kind)` でクリップ形状を決める: `ListItem` 行は常に角丸長方形、`NavItem`
    行も `PaneLayout.Triple` の常設サイドバーペインとして描画されている間（記事一覧の隣に
    並ぶので、2 つが 1 つのデザインに見える）は同じ形。実際にフィード一覧のナビゲーション
    ドロワーの中身として描画されているときだけ（`LocalFeedListInDrawer`、`FeedListPane` 自身の
    `onSelectionAdvance` の非 null 性から提供される）`NavigationDrawerItem` 風のピルになる。
    選択色は `rowSelectionColors()` の
    `secondaryContainer`/`onSecondaryContainer` で、どのペインがキーボードフォーカスを持つかに
    関わらず同じ値を使う（ペインフォーカスは代わりに `RowSelectionColors.paneFocus` の
    `PaneFocusIndication.Ring` による `secondary` の輪郭線で表し、`HomeCommon.kt` の
    `listRowOutline` が描く — 詳細は同ファイル自身の KDoc）、TooltipIconButton/ToolbarIconGroup/FlatTooltipContent
    （それぞれ、独自のネイティブな長押しトリガーを持つ `TooltipBox` の中に置いた M3 自身の
    アイコンボタン群 — どれを使うかは `IconButtonKind` が決める: `IconButton`（`Standard`）、
    `FilledIconButton`（`Primary`）、`OutlinedIconButton`（`Secondary`）、`errorContainer` に
    塗り替えた `FilledTonalIconButton`（`Destructive`）、
    デスクトップの macOS ツールバー風カプセルの代わりの装飾なし `Row`、M3 自身の `PlainTooltip`）、
    KeryxRaisedSurface（デスクトップのヘアライン枠フラットカードの代わりに、明確に色調の異なる
    `colorScheme.surfaceContainerHigh` トーナルコンテナ）、KeryxBadgedIcon（デスクトップの
    自作ピルの代わりに M3 自身の `BadgedBox`/`Badge` — `NotificationsBell` が使用）,
    KeryxSettingRow（行全体がタップ対象になる本物の M3 `ListItem` — `SettingsComponents.kt` の
    `LinkRow`/`ActionLinkRow`/`SwitchRow` を支える）, KeryxAnchoredPanel（本物の M3
    `ModalBottomSheet` — `NotificationsBell` の通知ポップオーバーと `TagColorPickerPopup` を
    支える。単なる作法の一致ではなく必須の対応でもある — 素の `Popup` のままだと、デスクトップの
    ヘビーウェイト WebView が素の Compose オーバーレイの手前に来るのと同じ理由で、記事リーダーの
    `WebView` の背後に隠れてしまう。下記「Article Reader」参照）, KeryxPaneTopBar（本物の M3
    `TopAppBar` — 3ペインそれぞれ自身のヘッダー行を支え、アプリ全体で共有される単一のバーでは
    ない）,
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
    へ委譲）, PlatformOs（isTouchPrimary = true, hasNativeAppMenu = false, hasSystemTray = false — Android にはメニューバーやシステムトレイが
    無いため、`FeedListPane` 自身の設定用フッター行（スクロールするフォルダー/タグ/フィード一覧の下）が
    Android の設定への導線となり、`GeneralTab` がバージョン情報を持つ）,
    SelfUpdateCheck（インストール元パッケージ名に基づく判定、[background-update.ja.md](background-update.ja.md) 参照）,
    NotificationPermission（`POST_NOTIFICATIONS` 用に `rememberLauncherForActivityResult` をラップ）+
    AndroidStartupTasks.kt（`runAndroidStartupTasks`。`:androidApp` の `MainActivity` から呼ばれる）+
    background/（`FeedRefreshWorker` + `BackgroundRefresh.kt` の `startBackgroundRefresh`。
    `WorkManager` ベース — Android のバックグラウンド/通知の全体像は
    [background-update.ja.md](background-update.ja.md) を参照）, platform/update/AndroidUpdateInstaller
    （`PackageInstaller` セッション＋その結果を受ける動的登録の `BroadcastReceiver` — 下記
    「アプリ内アップデート」参照）
  androidMain/res/  `works.merc.keryx.app.R` を生成する通常の AGP リソースディレクトリ
    （`values/`、`drawable/` など）— 上記 `commonMain/composeResources/`（Compose Multiplatform
    自身の仕組みで、リソース ID ではなく型付きの `Res.drawable.*` アクセサーを生成する）とは別物。
    `:composeApp` は `:androidApp` 自身の `res/` に依存できないため、生の `@DrawableRes Int` が
    必要な Android リソース（例: `NotificationCompat.Builder.setSmallIcon`）はここに置く必要がある
    — 現状は `drawable/ic_stat_keryx.xml`（`AndroidNotificationSink.kt` が投稿するステータスバー/
    通知ドットのアイコン。background-update.ja.md 参照）のみ
  commonTest/ + desktopTest/ + androidDeviceTest/（実機/エミュレータが必要な Android 実装向け計装テスト
    — DatabaseMerger/DatabaseSnapshot のバンドル SQLite ネイティブライブラリだけでなく、Android Keystore
    （`KeystoreTokenStorageDeviceTest`）、Play 開発者サービス（`PlayServicesGoogleDriveAuthDeviceTest`、
    `AndroidAuthorizationHostDeviceTest`）、Storage Access Framework（`FilePickerDeviceTest`）も含む。
    testing.ja.md 参照）
```

パッケージルートは `works.merc.keryx.app`（`keryx.merc.works` の逆順 DNS）。

ルート直下の別モジュール `androidApp`（`com.android.application`。上記の Kotlin Multiplatform
ソースセット構成には含まれない）は `AndroidManifest.xml`、`KeryxApplication`（プロセス全体の初期化:
`AndroidAppContext.init`、`startKoin`、`configureImageLoader`、FTS バックフィルの
`ensureIndexedIfTableAbsent()`（プロセス起動のたびに呼ばれる軽量版——db-schema.md の `articles_fts` 節参照）、
`startBackgroundRefresh`）、`MainActivity`（`setContent { App() }`、続けて
`runAndroidStartupTasks`）に加え、自身の `res/`（ランチャーアイコン、`values/strings.xml`、
`backup_rules.xml`、`data_extraction_rules.xml`）、サイドロード可能な GitHub ビルドと Play ストア版を
分ける `github` フレーバー用の `AndroidManifest.xml`、そして `androidTest/`（
`KeryxSearchBarAndroidTest`、`NativeMenuAndroidGestureTest`、`KeryxSettingRowAndroidGestureTest` ——
上記の `androidDeviceTest` とは異なり、実機/エミュレータが必要な計装 Compose UI テスト）を持つ。
これが別モジュールになっているのは、AGP 9 の
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
- **ライブ DB の `articles_fts` は決して DROP しない** — アップロードからの除外はスナップショットの
  コピー側（`DatabaseSnapshot`）で `VACUUM INTO` により行うため、実行中の検索が `no such table` に
  当たることはない。
- **Hot path**（フィード更新・同期マージ後）は `FtsManager.indexMissing()` で新規行のみを増分投入する
  — 全体を索引し直す `'rebuild'` は `O(索引済みテキスト全体)` で重く、実行中の検索をブロック/0件化
  しうるため決して使わない。
- **Heal。** インデックス全体の再構築は稀な heal パスでのみ行う: 日次アイドルパス
  （`domain/StartupMaintenanceTasks.kt` の `maybeRebuildFtsIndex`。`lastFtsRebuiltAt` + `ActivityCenter`
  のアイドル状態がゲート。desktop の `StartupTasks.kt` と Android の起動処理の両方から呼ばれる）が、
  増分投入では古いままの内容を再索引する。
- **起動時。** desktop の起動時は `FtsManager.ensureIndexed()` がテーブルを初回作成し未索引行を
  バックフィルする。Android は軽量版 `ensureIndexedIfTableAbsent()` をプロセス起動のたびに呼ぶ
  （理由は db-schema.md の `articles_fts` 節参照）。
- **並行性。** `DatabaseDriverFactory` で設定する `busy_timeout` により、検索は増分投入や rebuild の
  短い書き込みロックをエラーにせず待ち抜ける。`FtsSearch.search()` は語の長さで
分岐する — 3 文字以上の語は `articles_fts MATCH`（ランク順）を実行するが、trigram トークナイザはそれより
短い語を索引化できないため、2 文字の語は `LIKE` フィルタとして扱う（長い語が1つでもあればマッチ済みの行への
追加 AND、全語が2文字ならマッチ ID なしの単独 `LIKE` 走査を `published_at DESC` 順・
`SEARCH_FALLBACK_RESULT_LIMIT` 上限で行う。FTS ランクが存在しないため）。両経路のハイライトマーカーは
FTS5 の `highlight()` ではなく Kotlin 側（`markTerms`）で生成し、`LIKE` で拾った短い語も FTS 一致と
同じ見え方でマークされる。正確な文字数の閾値は [db-schema.ja.md](db-schema.ja.md) の `articles_fts` 節を参照。

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

`DatabaseSnapshot` にも同じ「ロジックは commonMain、コネクションは actual」という分割が当てはまる:
`domain/SnapshotSql`（`MergeSql` と同様、純粋なデータ）が、`VACUUM INTO` 後にスナップショットのコピー側へ
実行するクリーンアップ文の共有リストであり、両 actual が同じテーブル/インデックスを同じ順序で DROP する
ため、個別に再導出して乖離するリスクがない。異なるのはコピーを開くコネクション（JDBC か requery のバンドル
SQLite か）だけで、Android 固有の `android_metadata` テーブルは共有リストにハードコードせず、追加の
drop テーブルとして渡している。

### CloudSession / SyncRepository

`CloudSession` が現在の `CloudStorage`（デスクトップは Dropbox / Google Drive / OneDrive、Android も
同じ 3 つだが Google Drive は Play 開発者サービスのある端末のみ）を提供し、アクセストークンの自動
リフレッシュを担う。ただし Android の Google Drive だけは例外で、トークンを所有するのは本アプリでは
なく Play 開発者サービスであるため、プロバイダ自身が `CloudSession.Provider.accessTokenProvider` で
供給し、リフレッシュは一切行わない — sync-architecture.ja.md の「Android での Google Drive」参照。
`SyncRepository` はダウンロード → マージ（`DatabaseMerger`）→ 新記事の増分索引（`indexMissing`）→
`VACUUM INTO` スナップショット生成（`DatabaseSnapshot`、コピー側で `articles_fts` を除外）→ アップロード
（rev チェック）、のフローとデバウンス（`SyncScheduler`）を実装する。ライブ DB の FTS は触らない。
`SyncRepository` の `localDbPath` の既定値は `platform/DatabaseFile.kt` の `databaseFilePath()`
— プラットフォームごとにライブ DB の実パスを解決する唯一の `expect` 関数（db-schema.ja.md 参照）。

### アプリ内アップデート

`domain/UpdateRepository` は上記の `SyncRepository` と同じ種類の、アプリのライフタイムを持つ
Koin `single` のオーケストレーターであり、`StateFlow<UpdateState>` を UI 側の各面（Updates タブ、
トレイ、ベル）が読む——設定ダイアログを閉じても進行中のダウンロードはキャンセルされない。これは
3 つのシームを組み合わせる: `domain/UpdateChecker`（候補選択とバージョン比較のポリシーのみ——
GitHub Releases への HTTP リクエストと JSON パースは `data/remote/ReleaseFeedSource` に住み、
`UpdateChecker` は自分のコンストラクタと同じ引数から内部でそれを組み立てる。依存として
受け取らないのは、多数のテストが直接使っているこのクラス自身のコンストラクタ形状を、
単なる内部の層分けの都合で変えたくないため）、
`data/remote/UpdateDownloader`（手動リダイレクト追従＋ホスト allowlist＋digest 検証。
`FeedFetcher` 自身が自前のリダイレクト処理に使っている「共有クライアントのプラグインに頼らず
手で書く」という形をそのまま踏襲）、そして `domain/UpdateInstaller`（新規の expect 相当の
interface——その実装自体は隣接する場所ではなく `platform/update/` にある。`OsNotificationSink`
とまったく同じように `platformModule` 経由でバインドされるプラットフォームごとの
`single<UpdateInstaller>` で、テストではフェイクに差し替えられる）。
何をすべきかを決める 2 つの純粋な `domain/` 関数はネットワークにもファイルシステムにも触れない
ため、どちらも素の `commonTest` の対象になる: `UpdateAsset.kt` の `selectUpdateAsset`
（`platform/InstallLocation.kt` の `detectInstallLocation()` を踏まえて、どのリリースアセットか）
と `UpdateInstallPolicy.kt` の `updatePlan`（そのアセットで何をすべきか——自己置換、OS の
インストーラーへの引き渡し、リリースページへのフォールバックのいずれか）。`UpdateInstaller.canInstall(plan)`
は意図的に純粋関数では**ない**——プラットフォームの `actual` が「今はダメ」と言える唯一の場所で
あり、その理由は `updatePlan` 自身には知りようがないもの（典型的には Android の実行時インストール
同意状態）だからである。それでも `UpdateInstallPolicy.kt` の `canInstallAndroidApkUpdate` は
その*判断*自体を 1 つの boolean を受け取る純粋関数として切り出しており、`androidMain` 自体には
JVM でテスト可能なユニットテストのソースセットが無いにもかかわらず `commonTest` でカバーされて
いる（testing.ja.md 参照）。`platform/ScrollIndicatorGeometry.kt` の純粋関数群も同じ理由で同じ形を
採っている——Android のスクロールインジケーターの thumb 比率計算は純粋なので commonMain に置かれ
commonTest でカバーされる。デスクトップ側からは一度も呼ばれないにもかかわらず、である。

上記の `selectUpdateAsset` と `updatePlan` に並ぶもう 1 つの純粋関数が、意図的に `domain/` の
外に置かれている: `ui/settings/ReleaseNotesText.kt` の `plainTextReleaseNotes`（Updates タブの読み取り専用サマリー
向けの Markdown → プレーンテキスト変換）は更新ポリシーではなく UI 層の表示整形であり、
`ui/home/HomeCommon.kt` の `formatTimestamp` や `ui/i18n/ErrorMessages.kt` が `domain/` の外に
置かれているのと同じ理由による。唯一の呼び出し元も `ui/settings/UpdatesTab.kt` である。

デスクトップと Android の `UpdateInstaller` actual はコードを一切共有していない——デスクトップ
（`platform/update/DesktopUpdateInstaller.kt`）は `platform/update/ArchiveExtractor.kt` 経由で ZIP を
展開し（macOS は `ditto`——署名済みバンドルが自身の symlink を封印しているため。それ以外は
`platform/ZipExtractor.kt`（`jvmCommonMain`。`FileIO`/`Gzip` とまったく同じ形で Android と共有）。
[background-update.ja.md](background-update.ja.md) 参照）、現在のインストール先の
隣にステージングしてから、`platform/update/UpdateScriptWriter.kt`（純粋な文字列テンプレート——
本文そのものを直接アサーションで検証し、実際に起動することは無い）が生成した detached ヘルパー
スクリプトへ、`platform/update/DetachedProcess.kt` の `ProcessLauncher` シーム
（`data/cloud/SecurityCliTokenStorage.kt` の `CommandRunner`/`RealCommandRunner` の分割を踏襲）
経由で引き渡す。`main.kt` がアプリ全体を終了するのは、この引き渡しが `Launched` を返した
ことを受けて流れる `UpdateRepository.installLaunched` シグナルによってのみで、まだ展開中に
立つ `UpdateState.Installing` を理由に終了することはない。Android
（`platform/update/AndroidUpdateInstaller.kt`）は代わりに、ダウンロードした APK を
`PackageInstaller` セッションへストリーム書き込みする。挙動面の全体（状態機械、プラットフォームごとの
インストール手順、提示のしかた）は `background-update.ja.md` の「アプリ内アップデート」を、
完全性検証の信頼モデルについては `SECURITY.ja.md` を参照。

### Provider / DI（Koin）

`appModule`（commonMain）にリポジトリ・サービス・ViewModel を登録——desktop と Android 共通。
`platformModule` は各プラットフォームが個別に持つ: desktop 版は HttpClient・TokenStorage・CloudSession・
CloudConnectFlow・`OsNotificationSink`・`UpdateInstaller` を登録し、Android 版は Android 固有の実装
（OkHttp ベースの HttpClient、`KeystoreTokenStorage`、Dropbox と OneDrive に加え、Play 開発者サービスが
利用できる環境でのみ Google Drive も持つ `CloudSession` 等——上記「Android」各節参照）を登録する。
ViewModel はアプリスコープの `single` として登録し、`koinInject()` で取得する。

### 記事リーダー（ネイティブ WebView）

`ui/home/ArticleDetailPane.kt` のリーダーは記事 HTML をネイティブ WebView
（`io.github.kdroidfilter.webview`。ヘビーウェイトな AWT `SwingPanel` が実 OS のブラウザビュー
— Windows は Edge WebView2、macOS は WebKit、Linux は WebKitGTK — をラップしたもの）で描画して
おり、Compose が描画するテクスチャではない。ペインの生存期間中は `if` の下に置かず常時
無条件でコンポーズする — Compose Desktop の `SwingInteropContainer` はヘビーウェイトな
コンポーネントが追加・削除・移動されるたびに、このペインだけでなく**ウインドウ全体**を
再検証＋再描画するため。その帰結として、
描画すべき記事が無い状態（「記事未選択」「本文なし」）は Compose の `Text` ではなく、同じ
WebView **内部**の HTML として描画する（`ui/article/ArticleWebViewHtml.kt` の
`articlePlaceholderHtml`／`articleNoContentHtml`。実記事用の `wrapArticleHtml` と同じ
`<style>` ブロックを共有し、どの状態でも同じテーマ色で塗られる）。この共有 `<style>` ブロックは
`color-scheme`（`dark` か `light` のどちらか一方——`themeMode` から直接ではなく
`ArticleHtmlTheme.surface` 自身の輝度から `ArticleHtmlTheme.isDark` 経由で導く。リーダーは
`resolveDarkTheme` の入力にアクセスできないため）も宣言する——`light dark` 併記はしない。これに
より、ブラウザは自身のフォームコントロールとスクロールバーを OS の設定を独自に追従させるのではなく
アプリのテーマに合わせて描く。`::-webkit-scrollbar`（や `scrollbar-width`／`scrollbar-color`）の
ルールは一切定義しない——そもそもオーバーレイ・スクロールバーを描くエンジン（Android の
WebView、macOS/Linux の WebKit）では、そのいずれか 1 つでも定義するとブラウザはそれをやめ、
レイアウト幅を消費するクラシックなものに切り替わり、本文が狭くなる。詳細は `ui-guidelines`
スキルの「Scroll indicators」参照。（Windows の WebView2 は既定でクラシックなスクロールバーを
描くため、このルールをやめさせる対象がそもそも無いが、プラットフォームごとの例外にはせず
4 エンジン共通のルールとして扱う。）リーダー上部のツールバーも
同様に常時表示し、未選択時はボタンを非表示にせず無効化する。また、記事が選択されている間は
その記事のフィード名とファビコン（`FeedAvatar`）を表示し、`KeryxPaneTopBar` の `titleContent`
として空のタイトル枠に差し替わる——そのためツールバーの Compose 構造は状態によって変わるが、
*測定される高さ*は変わらない: 実際に高さを固定しているのは常在する `TooltipIconButton` の
アクション行であり、タイトル枠にどちらのコンテンツが組み込まれているかに関わらず、リーダー自身の
計測済みバウンズは状態間で常に同一に保たれる。

**Android では `color-scheme` だけでは足りない。** `android.webkit.WebView` の既定スタイル
`Widget.WebView` は `scrollbars="horizontal|vertical"` を設定しており、そのルートフレームの
スクロールバーは描画エンジンではなく Android の**View フレームワーク自身**が描く——
`color-scheme` を含むいかなる CSS もそこには届かない。そのサムはプラットフォーム自身の
drawable で、ホストする Activity のテーマに対して解決された `?attr/colorControlNormal` で
ティントされる。`:androidApp` はそのテーマを固定で `Theme.Material.Light.NoActionBar` にしている
（アプリのライト/ダーク設定は OS とは独立した自前の設定のため）。何もしなければサムは常に
ライトテーマの暗いグレーのままとなり、ダークなリーダー背景の上ではほとんど見えない。
`platform/NativeWebViewScrollbar.kt` の `setNativeWebViewScrollbarColor` がこれを直接修正する:
Android（API 29 以降のみ——`setVerticalScrollbarThumbDrawable`／
`setHorizontalScrollbarThumbDrawable` はそれより前には公開 API の代替が無い）では、縦横両方の
サム drawable を `MaterialTheme.colorScheme.outline` で塗った単色の図形に差し替える——これは
`platform/ScrollIndicatorOverlay.kt` が記事一覧自身のインジケーターに使うのと同じ色ロールで、
ドキュメント中の他のすべてと同様にアプリ内テーマ（と Material You の動的パレット）に追従する。
`ArticleWebView` はこの色をキーにした `LaunchedEffect` で再適用するため、記事を開いたまま
テーマを切り替えても塗り直される。デスクトップ側の `actual` は no-op——WebView2/WebKit/WebKitGTK
は既に `color-scheme` から自身のスクロールバーを描いているため、他に何もする必要がない。

**フィード本文はサードパーティのコンテンツであり、文書はそれに耐えるよう組み立てている。** 本文は
リッチマークアップを活かすため無加工で埋め込まれ、その上に記事自身のオリジンを指す `<base href>` が
付く。このため本文は配信元サイトのスタイルシートにも到達できてしまう — 本文が持つ
`<link rel="stylesheet">` からでも、本文中のスクリプトが `<head>` に追加したものからでも。この種の
スタイルシートは初回描画の一拍あとに届き、カスケード上は後ろに来るため、共有の `<style>` ブロックに
勝ってリーダーの体裁（余白・タイトル色・フォントスケール）を作り替えてしまう
（[known-issues.ja.md](known-issues.ja.md) 参照）。そこで `articleDocument` は
`<meta http-equiv="Content-Security-Policy" content="style-src 'unsafe-inline'">` を出力する。
`style-src` に URL ソースを一切書かないので外部スタイルシートは決して取得されず、一方
`'unsafe-inline'` によってアプリ自身の `<style>` ブロックと本文の `style=""` 属性は従来どおり効く。
`script-src` も `default-src` も**意図的に**書いていない — 本文のスクリプトと、それを必要とする SNS
埋め込み（上記のリンク横取りの記述を参照）はそのまま動き続ける。加えて、リーダー自身の**クローム**の
ルールは全宣言に `!important` を付けており、より低い詳細度からの上書き — メタ CSP を解釈しない
エンジンや、`'unsafe-inline'` では通ってしまう本文内のインライン `<style>` の通常のケース — に対して
硬くしている。ただしこれは硬化であって隔離ではなく、効くのはここに列挙した宣言に対してだけである。
どのルールも `!important` を付けていないプロパティ（`.article-title { display: none !important }` は
タイトルごと消してしまう）はそのまま通り、同等以上の詳細度を持つ本文側の `!important` ルールは、
本文が `<style>` ブロックより後ろに来る以上、出現順で勝つ。本文そのものを封じ込めるには
サニタイズか描画境界の分離が必要で、[known-issues.ja.md](known-issues.ja.md) の
「本当の修正に必要なこと」を参照。クロームに限っていること自体は意図的で、本文コンテンツ向けの
ルール（`a`、`img`/`video`/`iframe`、`table`、`td`/`th`）は素のままで、記事筆者自身の `style=""` が
引き続き勝てる。

`ArticleWebView` は `webSettings.desktopWebSettings.dataDirectory` も明示的に設定しており、
`AppDirs.cacheDir()` 配下の `webview` サブディレクトリを、デスクトップ 3 OS すべてに同一に適用している
（OS 分岐なし）。デフォルトの `null` のままだと WebView2 は実行ファイルの隣に自分のデータフォルダを
作ろうとし、その場所が書き込み不可の場合は Access Denied で失敗する（この生成失敗の例外が uncaught の
まま伝播し、ライブラリの生成リトライタイマが止まらなくなることが、クリック時にアプリ全体がフリーズする
原因でもあった）。

**狭いレイアウトではリーダーは `HorizontalPager` になる**（`ui/home/ArticleDetailPane.kt`。
commonMain 共有のコンポーザブル） — 水平ドラッグで次/前の記事へ移動し、画面に出ているページの
両隣は実際にマウントされた `WebView` であり、それはページャ自身の item content ではなく
`ArticleWebViewCarousel` 経由で描画される（理由は下記）。`PaneLayout.Triple`（デスクトップの全ウインドウと、
横持ちの大型タブレット）は上で述べた単一の無条件コンポーズのリーダーのままで、2 つの形態の分岐は
`readerPaging != null && article != null && isTouchPrimary` の 3 つすべてを見る——`readerPaging`
単独ではない。`article != null` があるのは、paging データはあるがまだ何も選択されていない
`PaneLayout.Dual` のリーダーを、ページャがたまたま最後に表示していた記事のままにせず、
プレースホルダの HTML に留めるため。`isTouchPrimary` をここでもう一度見ているのは、
`ArticleDetailPane`（ViewModel 側のラッパー）がタッチ主体のプラットフォームでしか
`readerPaging` を組み立てないにもかかわらず——レイアウト確定前の過渡フレームではデスクトップでも
`PaneLayout.Single` に解決され得るためで（`HomeScreen` 自身の `BoxWithConstraints` に関する
コメント参照）、重量級ページャを実際にマウントするかどうかを決めているのはこのコンポーザブル
自身なので、この不変条件は呼び出し元だけでなくここでも保たれている必要がある。

`ArticleSwipeNavigation` と `ArticleReaderPaging` は同じ「null = `PaneLayout.Triple`」のシグナル
だが、前者が意図的に安定した `remember` 済みのコールバック束であるのに対し後者は記事のロードに
応じて変化するデータを運ぶため、別パラメータに分けてある——`ArticleDetailPane` は自身の組み立てを
`remember(pages, contents, article)` で包み、記事の書き込みのたびにリーダー全体を再コンポーズ
しないようにしている。シグナルが `onNavigateUp` ではなく `swipeNavigation` なのは、
`PaneLayout.Dual` ではリーダーに戻るボタンが無い（`onNavigateUp` が `null`）がスワイプは有効で
なければならないため（下記「Home's adaptive pane layout」参照）。

**`HorizontalPager` 自身は `WebView` を一切描画しない — スクロール物理演算・スナップ・確定検知
（`pagerState.currentPage` / `currentPageOffsetFraction` / `settledPage`）を駆動するためだけに
不可視のまま組み込まれている**。その item content は空の `Box` である。ページャと並んで組み込まれる
きょうだいの `ArticleWebViewCarousel` こそが、実際に 3 ページを表示している本体である:
`ARTICLE_READER_SLOT_COUNT`（3）個の常時生存する `ArticleWebView` を固定の呼び出し位置で
無条件にコンポーズし続ける — 上記の `PaneLayout.Triple` リーダーで使っている「一度コンポーズしたら
`if` の裏に置かない」という同じ発想である — そして、不可視のページャが示す位置へ、手作業（ジェスチャー
自身のラバーバンドと同様にレイアウト時に読む、ラムダ式の `Modifier.offset`。`pagerState.currentPage`/
`currentPageOffsetFraction` を読む）で追従させる。`slotIndex` は各スロットに、3 を法として同じ剰余を
持つページ index を割り当てる。連続する 3 つの index は常に 3 つの異なる剰余に落ちるため、確定ページと
両隣は必ず別々のスロットになり、隣のページへ一歩進んでも 3 つのうち高々 1 つのスロットしか
再割り当てされない。

この分離が存在する理由は、*ページャ自身の* 遅延コンポーズされる item スロットに直接 `WebView` を
持たせる — 本コードの以前のバージョンはそうしていた — と Android でちらつきが出たためである:
`LazyLayout` は、ページが `beyondViewportPageCount` を超えて外れた瞬間にそのコンポジション（と、その
内側にある `AndroidView` でホストされたネイティブビュー）を破棄し、そのページが範囲へ再び入ると
ゼロから作り直す。その再生成は実際の `android.webkit.WebView` を（Compose のコンテンツだけでなく）
丸ごと破棄・再構築し、一瞬何も表示しなくなる——実機でのフレーム単位のキャプチャで確認済みで、
前方スワイプでは毎回再現し（後方では決して起きず、最終記事でも起きない）、これは Compose Foundation
自身のページャの prefetch/破棄がたまたま両方向で非対称であることに起因する。Compose の
cache-window prefetch フラグ（`ComposeFoundationFlags` の `isCacheWindowForPagerEnabled`）を無効化
しても直らなかった — 破棄・再生成のサイクルはその特定の prefetch 戦略ではなく `LazyLayout` の
item 破棄そのものに内在するものだった。`ArticleWebViewCarousel` は、そもそも `WebView` を遅延コンポーズ
の範囲の外に置くことで、この仕組み全体を迂回している。

この（今は不可視になった）ページャと、その状態を読み取るカルーセルについて、次の 4 点は挙動を支える要である。

- **`slotIndex` による 3 を法とした剰余の割り当て。** 「スワイプで離れて戻ると元の位置に戻る」を
  今支えているのはこれであって `beyondViewportPageCount` ではない: 確定ページを保持している物理
  スロットは、ユーザーが隣へ一歩進んで戻ってきても（位置を変えるだけで）そのページを保持し続ける。
  1 歩の移動では常に「もう片方」のスロットしか再割り当てされないためである。制限は以前と同じ
  （2 記事離れて戻ると位置は失われる——スロットは新たに範囲へ入ってきたものへ再割り当てされ、
  内容を読み込み直す。記事一覧へ戻った場合やレイアウトが変わった場合も同様で、それらはペインごと
  アンマウントされるため）。ページャを使わないデスクトップでは読み位置は一切復元されない。
  なお、リストの端では 1 つのスロットが割り当てを持たず解放される（何も emit しないスロットは
  Compose がコンポジションごと破棄するため）。ただしそのスロットが保持しているのは確定ページから
  2 ページ以上離れたページだけなので、画面上のもの（およびスワイプ 1 回で出るもの）が破棄されることは
  なく、保存対象の読み位置が失われることもない。
- **不可視のページャに設定された `beyondViewportPageCount = 1`。** カルーセル自身のスロット割り当ては
  この値を読まないため、もはや読み位置を保存しているのはこれではない——しかし、この分離より前から
  ある他のテストが検証しているページャの*それ以外*の内部挙動（先行コンポーズのスケジューリング、
  `key` が一覧の変化時に依拠する `LazyLayoutKeyIndexMap` ベースの再マッピング）を、この分離によって
  できるだけ変えないために、あえてそのまま残してある。
- **`userScrollEnabled = false`。** Android の `WebView`（`AndroidView` 経由で埋め込まれる）は
  通常の in-tree ビューだが、タッチ入力を自分自身で消費し、Compose の nested scroll にも参加
  しないため、ページャ自身のジェスチャー処理とぶつかる。代わりに、`platform/NativeMenu.android.kt`
  の長押しや `ui/home/FeedListDragGestures.kt` の並べ替えドラッグと同じ方式で調停する:
  `ui/home/ArticleSwipeNav.kt` の `pointerInput` ループが `PointerEventPass.Initial`（この祖先
  ノードに WebView 側の interop 処理より先に届くパス）を監視し、水平方向が確定するまで一切
  consume せず、確定後はページャ自身を駆動する。ユーザースクロールを無効にすると
  ページャ自身のアクセシビリティ・スクロールアクションも消えるが、それは
  `articleSwipeAccessibilityActions` が既に代替している。プラットフォーム標準のオーバースクロール
  表現も出なくなるため、リストの端を指すドラッグ用に、リーダーは自前のラバーバンド
  （`swipeDragOffset`。ページャ全体への単純な `Modifier.offset`）を持ち続けている。
- **`key` はページ index ではなく記事 ID。** 同期マージ・フィード更新・「未読のみ」の切り替えは、
  ページャの下で一覧を並べ替える。`ui/home/ArticlePagerSync.kt` が選択とページの同期を両方向とも
  持ち、自身の `@Composable` である `ArticleReaderPagerSync` がそれを配線する:
  - **確定 → 選択**（`settledPageSelects`）は、`ArticleSwipeController` 自身がそのページを
    「確定したスワイプの遷移先」として記録していた場合（`pendingSelectionPage` /
    `consumePendingSelection`）にだけ、`HomeViewModel.selectArticle`（既読化するのはここ）へ昇格
    させる。`PagerState` は一覧がその現在ページより縮んだとき自身の現在ページをクランプする
    ——検索、同期マージによるトゥームストーン、「未読のみ」が今読んだばかりの記事を隠す、など、
    スワイプが一切絡まないケースで起こる——このゲートが無ければ、そのクランプはクランプ先に
    たまたま来た記事を選択し既読化してしまう。未選択のときにも何もしないので、`PaneLayout.Dual`
    で一覧に触れないままページ 0 に居るリーダーが、勝手に先頭記事を開いてしまうこともない。
  - **選択 → ページ**（`pageIndexToRestore`）は、他所で行われた選択（記事一覧・J/K・リーダー
    自身のアクセシビリティアクション）に追従し、一覧が下で変化したときにページャを再アンカーする。
    `ArticleSwipeController.gestureInProgress` が `true` の間は手を出さない。このゲートが見るのは
    `PagerState.isScrollInProgress` ではなくこのフラグである: コントローラは最初に確定した
    ドラッグサンプルから settle/turn アニメーションの終了まで一貫してこれを保持するが、
    `isScrollInProgress` は指を離した直後・アニメーション開始前の間隙で既に false になって
    しまっており、その間もスワイプはまだページャを掌握している。

  どちらの方向も、`key` と同じ理由で index ではなく ID を比較する。

**ジェスチャーはドラッグ 1 回につき 1 つのスクロールセッションでページャを駆動し、ポインタサンプル
ごとに `scrollBy` を呼ぶことはしない。** `ScrollableState` の変更は `MutatorMutex` を経由するが、
これは直列化ではなく、進行中のミューテーションの**キャンセル**を行う——サンプルごとに `scrollBy`
を呼ぶと、前のミューテーションがキャンセルされている間に届いたサンプルのデルタが黙って失われる。
ページャのスクロールは相対デルタなので（旧実装の `Animatable.snapTo` は絶対値だった）、失われた
デルタは二度と戻らず、コンテンツが指からずれていく。代わりに `ArticleSwipeController.onDragStart`
は 1 つの `PagerState.scroll { }` セッションを開き、`Channel` 経由でデルタを流し込み、
`onDragEnd`/`onDragCancel` で初めて閉じる。続く settle/ページ送りアニメーションはまずこの
セッションの終了を待ってから走るので、開いたままのセッションと競合しない。セッションをジェスチャー
の間ずっと開いたままにすることは、`PagerState.isScrollInProgress` がドラッグ中に false へ落ちる
のを防ぐことでもあり、これが上記の `gestureInProgress` をこれとは別に追跡しなければならない理由
でもある。

各ページの本文は `HomeViewModel.requestArticleContent` が供給し、これは
`ui/home/ArticleContentCache.kt`（`HomeViewModel` に直書きするのではなく、独立してテストできる
小さな協力オブジェクト）に委譲する。`getArticleById` による純粋な読み取りを、`Articles` の全列
ではなく `ArticleReaderRow`（`domain/ArticleRepository.kt`）に射影したうえで `articleContents`
（上限 `ARTICLE_CONTENT_CACHE_LIMIT`、古いものから追い出し）へ格納する——全列には本文の
HTML 除去済みコピーである `search_text` も含まれ、リーダーはそれを一切読まない。**本文のロードは
選択ではない**: 既読化するのは `selectArticle` だけなので、隣のページは「開いた」ことにならずに
描画される。キャッシュは選択中の記事をあえてスキップしない——`ui/home/ArticlePagerSync.kt` の
`readerContents` が選択の正本をキャッシュより手前にマージするが、キャッシュ自身も自分のコピーを
保持し続けており、これが選択が隣へ移った後も、直前までスワイプで見ていたページを（空白化・
再読み込みさせず）描画され続けさせている。`readerPages` は一覧側の対になる仕組みで、選択中の
記事がまだ `pagerArticles` に載っていない（この Flow は `Eagerly` ではなく `WhileSubscribed` な
ので最初は空——後述）、あるいはちょうどトゥームストーンされた場合、ページャは空のページャと
埋まったページャを切り替える代わりに、選択だけから合成した 1 ページのリストを描画する
（切り替えるとその 1 ページの `WebView` が破棄・再構築されてしまう）。`HomeViewModel.pagerArticles`
が兄弟の `Eagerly` と違って `WhileSubscribed` なのは、これを購読するのがリーダーのページャだけ
（＝タッチ主体のプラットフォームの狭いレイアウトだけ）だから。`ArticleContentCache` は
リーダーがコンポジションを離れると（`ArticleDetailPane` の `DisposableEffect`）
`HomeViewModel.clearArticleContents` 経由で空になるので、長い閲覧セッションがページ送りした
すべての本文を ViewModel の寿命いっぱい抱え続けることはない。

隣接ページの本文をロードするということは、ユーザーがまだスワイプしていない段階でその画像・
埋め込み・スクリプトが取得・描画されることを意味する——`external-spec.ja.md` §10 に記載している。
そこで意味してはならないのは、そのページがあたかも画面に出ているかのように振る舞うことである:
`ArticleWebView` の `active` パラメータ（ページャの現在位置にあるページだけが `true`）は、
Compose 自身のセマンティクスツリーとは独立に、ネイティブビュー側で直接次の 2 つをゲートする
——ネイティブ `WebView` のアクセシビリティノードとナビゲーションイベントは、どちらもこの
ツリーを完全にバイパスするため——

- **ナビゲーション。** 非アクティブなページの `RequestInterceptor` はあらゆる URL を拒否する。
  これにはライブラリがスクリプト起点の `location.href` やメタリフレッシュを報告してくる場合も
  含む（ライブラリはこれらを実タップと区別しない）。これが無ければ、フィード自身が自動遷移する
  コンテンツや悪意あるコンテンツによって、ユーザーが一度も開いていない記事から外部ブラウザが
  起動されたり、画面外の `WebView` が共有プロファイル上の任意のオリジンへ誘導されたりし得る。
- **アクセシビリティ。** `platform/NativeWebViewAccessibility.kt` の
  `setNativeWebViewImportantForAccessibility` は、非アクティブなページの `WebView` に Android の
  `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` を設定し、記事本文が実際に存在するその
  サブツリーごとスクリーンリーダーの線形移動から除外する。周囲の Compose `Box` にも
  `clearAndSetSemantics {}` を付けているが、それだけではネイティブビュー自身のアクセシビリティ
  ノードには届かないため、両方が必要になる。デスクトップの `actual` は no-op ——
  `PaneLayout.Triple` は非アクティブなページを一切マウントしないため。アクティブなページ自身の
  コンテナには代わりに `liveRegion = Polite` による記事タイトルのアナウンスを付けている——
  ページャのユーザースクロールを無効化する（上記）と、その組み込みのページ変更アナウンスも
  一緒に消えるため、スクリーンリーダー利用者が記事間を移動する手段は
  `articleSwipeAccessibilityActions` のカスタムアクションだけになる。

**ネイティブ WebView をそもそも生成できない環境**では、リーダーは記事を Compose で描画する
フォールバックに切り替わる。`platform/NativeWebViewSupport.kt` の `isNativeWebViewSupported()` が
ライブラリのネイティブエントリポイントを 1 回だけプローブする — ライブラリはプラットフォーム／
アーキテクチャの組み合わせごとにビルド済みバイナリを 1 つずつ同梱しており、バイナリが無い
組み合わせでは `UnsatisfiedLinkError` が発生する。これがコンポジションの中から送出されると、
モーダルのエラーダイアログの裏でウインドウが停止する。プローブが失敗した場合、`ArticleWebView` は
`WebView` ではなく `ui/article/ArticleContentView.kt` を描画する。

このフォールバックは、生の記事本文ではなく **WebView に渡されるはずだった完成ドキュメント文字列
そのもの**を受け取り、ksoup で再パースする（`ui/article/ArticleContentParser.kt`）。リーダーの
4 つの状態（プレースホルダー、本文なし、本文ロード中のヘッダのみ、通常の記事）は上記の
ドキュメントビルダーが既に決定しているため、ここで導出し直すとロジックが二重化し、2 つのリーダーが
乖離していくからである。ブロック構造（`<figure>`/`<figcaption>` のグルーピングや `<dl>`/`<dt>`/
`<dd>` の定義リストを含む）・インライン装飾（記事側の `style=""` が持つ色・サイズ・太さも
`ui/article/InlineCss.kt` で解釈し、実際の CSS カスケードと同じくインラインスタイルを優先させる）・
画像（`srcset`／遅延読み込み属性、および画像しか持たない段落やリンクを消えるままにせず実際の
ブロック画像へ昇格させる処理を含む）を再現し、ブラウザエンジンを本当に必要と
する内容（iframe、スクリプト駆動のウィジェット、動画）は外部ブラウザで開くボタンになる。本文全体は
`SelectionContainer` で包まれており、WebView 版のドキュメント本文と同様にテキストを選択・コピー
できる。これはタイトル・インラインリンク（`LinkAnnotation.Clickable` であり、競合する
`Modifier.clickable` ではない）とも共存する。なお移植の
対象はリーダー自身の CSS ではほとんどない。ドキュメントが宣言しているコンテンツ向けルールは 5 つ
だけで、それ以外（段落の余白、見出しサイズ、リストのマーカー、`pre` の等幅、blockquote の
インデント）はブラウザが暗黙に提供しているものであり、Compose 側ではそれを明示的に定義し直す
必要がある——`MaterialTheme.typography` にではなく、**ドキュメント自身**の等幅スケール
（`ui/article/ArticleTextStyles.kt`：UA 既定の見出し比率とブロックマージンを、フォントサイズ設定と
連動するよう `sp` 経由で換算したもの。WebView 側の `font-size: N%` が連動するのと同じ理屈）に
対応付ける。このリーダー自体がブラウザの描画結果を再現しているのであって、アプリ自身の UI クロームを
描いているわけではないためである。ただし一部はあえて UA 既定へ寄せず、アプリ独自の装飾を残している
——引用の縦バー、コードブロックの背景、表の行罫線はいずれもそのまま維持され、表の列幅は固定値では
なく内容から計測される（`ArticleBlockViews.kt` のカスタム `Layout`）。

この経路では AWT のサーフェスを一切生成しないため、上記のヘビーウェイト interop に関する制約は
どれも当てはまらない。ウインドウ全体の再描画は起きず、Compose はペインの上に自由に描画できる。
分岐は `reader` ラムダの内側にあるので、ペイン自体の構造はどちらの経路でも無条件のまま変わらない。
現時点でこれが必要になる具体的なプラットフォームは Linux arm64 である。根拠、
`-Dkeryx.reader.webview` による上書き、そして arm64 Linux バイナリを同梱するバックエンドへの
移行がなぜ大がかりな変更になるのかは `known-issues.ja.md` を参照。

このフォールバックにはブラウザエンジン由来のスクロールが存在しないため、
`ui/home/KeyboardNav.kt` の共通ハンドラーがその `LazyListState` を直接駆動する——↑/↓ は
固定の行分だけ、Space / Page Down（逆方向は Shift+Space / Page Up）はビューポートの
大部分を移動し、Home / End は記事の先頭/末尾へ直接ジャンプする——いずれも通常のブラウザと
同じ挙動である。`ArticleContentView.kt` の
`FallbackReaderScrollHost`（`HomeScreen.kt` で一度だけ提供される `staticCompositionLocalOf`）
が、`ArticleDetailPane`/`ArticleWebViewCarousel`/`reader` ラムダを介したコールバックの
引き回しなしに、このハンドラーから現在アクティブなリーダーインスタンスへ到達する手段になって
いる——`LocalSnackbarHostState` が別の横断的関心事に対してすでに使っているのと同じパターンで
ある。これはネイティブ WebView リーダーには一切影響せず、実際にフォーカスを保持している間は
従来どおり自身でスクロールを処理する。

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
- `/StatusNotifierItem/menu` — `SniDBusMenu`（`com.canonical.dbusmenu`。アプリ内アップデート項目・
  セパレータ・表示/非表示・終了の順——`tray/TrayMenuModel.kt` の `MENU_UPDATE_ID` /
  `MENU_SEPARATOR_ID` / `MENU_TOGGLE_ID` / `MENU_QUIT_ID` がこの順）。
  ラベル／enabled 変更（アップデート項目自身の `enabled` 切り替えを含む）時は revision を上げつつ、
  変化した項目だけを名指しした
  `ItemsPropertiesUpdated`（`TrayMenuModel.kt` の `changedItemProperties`）を発火する ——
  `LayoutUpdated` ではない。メニューの形（存在する項目）は一切変化せず、かつ一部のクライアント（GNOME Shell の
  AppIndicator 拡張）は `label`／`enabled` を `GetLayout` で自発的に再取得しないため、
  `LayoutUpdated` だけを送ると既に開いたメニューが古いラベルのまま固まってしまう。
  `AboutToShow` は引き続き現在のラベルと `GetLayout` が最後に返した内容を比較するため、
  シグナルが落ちても復旧する。なお GNOME はメニューが開くまでシグナルを退避し、開いた後も初回
  描画をブロックせずに貼り替えるため、変化したラベルは一瞬だけ直前の値が見える。これは既知の
  アーティファクトであり（`known-issues.md` 参照）、こちら側では解消できない。再び
  `LayoutUpdated` に手を出す理由にはならない。

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
（例えば `LazyColumn` のスクロールに奪われる、など）生き残った場合にのみ長押しと判定する。この
検出ループが意図的に `PointerEventPass.Main`（子孫→祖先）を読むのは、子孫が既にそのジェスチャーを
奪っていないかを観測する必要があるからである。長押しが確定した後の**消費**ループは一転して
`PointerEventPass.Initial`（祖先→子孫）に切り替わる: このノード自身の消費が、他のどのノードよりも
先に届くようになる — 直前でチェーンされている `ui/home/ListRowChrome.kt` の `listRowClickable`
（より外側のノード）にも、行に**埋め込まれた**プレーンな `clickable`（タグ行のカラードットや
フォルダー／タグ行の展開シェブロンなど、子孫にあたる要素 — `Main` だけではこのノードより先に
同じイベントを見て、指を離した際に自分のタップを発火してしまう）にも。`PointerInputChange.isConsumed`
は同じ変化に対して全ノードで共有されるため、`Initial` パスで一度消費すれば十分で、`Main` で
重ねて消費する必要はない。これにより、埋め込みコントロールへの長押しが行のメニューを開くと同時に
そのコントロール自身のタップ動作も発火してしまう、という事態を防いでいる。`NativeSubMenu` は
ネストしたポップアップを開くのではなくその場でドリルダウンする（先頭の「戻る」行がトップレベルを
サブメニュー自身の項目に差し替える）。以下のデスクトップ実装とは異なり、Android には固有の挙動が
2 つある: 長押しが確定しても `onOpen`（デスクトップの「右クリックで行を選択する」フック）は
一切呼ばれない — Android の長押しはメニューを開くだけで、行の選択は行わない。また同じ
`awaitEachGesture` ループは、指が `viewConfiguration.touchSlop` を超えて動いた時点で長押し判定を
打ち切るため、行の上で始まったゆっくりしたドラッグ（`LazyColumn` のスクロール）が長押しと
誤認されることもない。

`platform/NativeMenu.desktop.kt` の `defaultPopupHandle` は、同じ呼び出し箇所をデスクトップでは
長押しではなく右クリックで開き、2 つの実装のどちらかで裏打ちする。

| プラットフォーム | 実装 | 理由 |
| --- | --- | --- |
| macOS | `AwtPopupHandle`（`java.awt.PopupMenu`） | AWT が本物の `NSMenu` に写像し、かつ AppKit はポイント基準なので、モディファイアが算出する Dp 空間の座標をデバイスピクセルへ変換する必要がない。 |
| Windows / Linux | `SwingPopupHandle`（`javax.swing.JPopupMenu`） | Linux では AWT の `PopupMenu` が Swing の Look & Feel を無視する heavyweight な XAWT ウィジェットで、Motif 世代の見た目のままになるため。Windows では JDK の AWT メニューピアが Java のユーザー空間とデバイスピクセルの変換を一切行わず、メニューが `ウィンドウ原点 + クリックオフセット ÷ スケール` に開き、行の高さがそこに描かれる文字の `1 / スケール` にしかならず、表示スケーリングが 100% を超えるとラベルが重なるため。 |

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
| Linux | `SwingFilePickerBackend`（`javax.swing.JFileChooser`） | `sun.awt.X11.XToolkit.createFileDialog()` は `GtkFileDialogPeer` を選ぶが、そのネイティブ GTK コールバックは、記事リーダーの WebView がプロセス内で WebKitGTK を 2 つ目の GTK コンシューマにした状態だと NULL の `JNU_GetEnv` の返り値を逆参照し、JVM をクラッシュさせる SIGSEGV になる。`JFileChooser` はどの Look & Feel でも純粋な Swing であり（FlatLaf の初期化に失敗した際のシステム L&F フォールバックも含む——`GTKLookAndFeel` 自身の `GTKFileChooserUI` もまた純粋な Swing であるため）、そのネイティブコードには一切到達せず、アプリの他の Linux Swing 画面と同じく FlatLaf にも追従する。 |

ダイアログの親ウィンドウは呼び出し元から渡すのではなく、デスクトップ版 `actual` の**内部**で解決する
（`KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow`、表示中の `Frame` へのフォール
バック付き）。`LocalNativeWindow` は常にメインウィンドウにしか解決されず、modeless な設定ウインドウ
から開いたダイアログの親としては不適切なため。`JFileChooser` にはネイティブな上書き確認が無い
（Linux で置き換える前の AWT `FileDialog` は SAVE アクションに無条件で
`gtk_file_chooser_set_do_overwrite_confirmation(dialog, TRUE)` が設定されており、macOS/Windows は
今もネイティブにこれを提供している）ため、`SwingFilePickerBackend` はこれを明示的に
（`resolveSavePath` + `JOptionPane` による確認）復元しており、Linux の従来動作をクラッシュ修正のついでに
静かに退行させることはない。

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
`composeResources/drawable/` 配下のバンドル Android Vector Drawable XML）、`expect`/`actual`
でプラットフォームごとに分割されている — 2つのターゲットが意図的に異なるアイコンセットを
バンドルしているため。デスクトップ側の `actual` は Tabler Icons（MIT）を使用する
（デスクトップ3OS共通で macOS 寄りの見た目に近づけるための選択。詳細は `ui-guidelines` skill）。
Android 側の `actual` は Material Symbols Outlined（Apache-2.0）を使用し
（`androidMain/composeResources/drawable/` にバンドル）、Android 自身のネイティブな
視覚言語に合わせている。`KeryxIcon(...)`（`Icon` のラッパー composable）は引き続き単一の
`commonMain` 定義のままで、`KeryxIcons` オブジェクトが選ぶアイコンだけがプラットフォームごとに
異なる。iOS/iPadOS/macOS がいずれネイティブ SwiftUI 化された場合（`external-spec.md` §2 の
想定どおり）、そちらは Kotlin の `KeryxIcons` とは無関係の別コードベースになるため、SF Symbols を
`Image(systemName:)` で直接使えばよく、Kotlin 側に追加の差し替え機構は不要である。

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

`ui/navigation/Navigator.kt` は現在の `Screen`（`Setup` または `Home` の一値——スタックではなく、
3番目の `Settings` という値も存在しない）を保持し、`replace()` で切り替える。設定は Home の上に表示される
ダイアログであり、記事ビューは Home 内のペインであって、どちらも独自のルートではない。

### Home の適応的ペインレイアウト

`ui/home/HomePaneLayout.kt` は、Home の3ペイン（フィード一覧・記事一覧・記事詳細）のうち
いくつを横並びで表示するかを、利用可能な幅だけから解決する: `PaneLayout.Triple`（3ペインすべて —
デスクトップは `WINDOW_MIN_WIDTH` が常に `TRIPLE_PANE_MIN_WIDTH` 以上であるため常にここに解決される。
`core/Constants.kt` の当該定数の KDoc 参照）、`PaneLayout.Dual`（記事一覧 + 記事詳細）、
`PaneLayout.Single`（1ペインのみ、スマートフォン幅）のいずれか。どちらの閾値も独立した
ブレークポイントではなくペイン幅の定数から合算しており、とくに `TRIPLE_PANE_MIN_WIDTH` は最小幅では
なくリサイズ可能な2ペインの *既定* 幅から求めている: 3枚すべてを同時に下限へ張り付かせなければ収まらない
程度の幅——Android タブレットの縦向き——は狭いレイアウトとして扱う。これはタッチ環境でとくに効く。
`ResizableDivider` にドラッグのアフォーダンスが無く、ユーザーがペインを広げ直せないためである
（当該定数の KDoc 参照）。`feedListIsDrawer(layout)`
（`layout != Triple`）が、以下のあらゆるレイアウト判断が分岐する唯一の情報源である:
`Triple` 以外のすべてのレイアウトでは、フィード一覧はオンスクリーンのペインではなく Gmail 風の
モーダルナビゲーションドロワー（`ModalNavigationDrawer`）となり、`ArticleListPane` 自身のヘッダー
にあるハンバーガーボタン（`onOpenDrawer`）で開き、中の何かを選択する（`onSelectionAdvance`）と
閉じる。`HomePane.FeedList` という enum の値自体は今も存在する——`Triple` が描画するのはこれ
だからだが、狭いレイアウトで `visiblePanes` がこれを返すことは無く、`NarrowPaneRow` はこの不変
条件を自前の `require()` で強制している。

ナビゲーションスタック自体は常に3段（`HomePane.FeedList` → `ArticleList` → `ArticleDetail`）だが、
狭いレイアウトでは depth 1（フィード一覧）に到達できない——ドロワーは `focusedPane` が指す
スタックの一部ではなく、開いても `focusedPane` は進まないし、`initialPaneFor`/`paneForFeedDetail`
（後述）もそこには決して解決されない。このため、狭いレイアウトでは「今キーボード操作の対象は
フィード一覧か」という問いに `focusedPane` だけでは答えられない（Android タブレットには物理
キーボードが接続されうる）——その答えを必要とするすべての呼び出し箇所（矢印キーのルーティング、
F2/Delete のフィード一覧ショートカット、各ペイン自身の `focused` 引数——選択行のキーボード
フォーカス枠/減光を駆動する）は代わりに `HomePaneLayout.kt` の
**`keyboardPaneFor(focusedPane, feedDrawerOpen)`** を読む。これはただ一つの純関数であり、
ドロワーが開いていれば常に `HomePane.FeedList` に解決し（`feedDrawerOpen =
feedListIsDrawer(paneLayout) && drawerState.isOpen`——開いている間は `focusedPane` に関わらず
常に画面の最前面にあるため）、それ以外では `focusedPane` そのものに解決する。`focusedPane` と
`feedDrawerOpen` を呼び出し箇所ごとに個別に読んでいた頃は、同じドロワー優先の判定をその都度
手で再導出する必要があった上、一度実際にそれが食い違うバグがあった:
`HomeScreen` の `Triple`/ドロワーの `FeedListPane` と `ArticleListPane` はそれぞれ独自に
`focused` フラグを計算しており、両方が同時に `true` になり得た（`PaneLayout.Dual` でドロワーが
開いている場合）ため、2つのペインに同時にキーボードフォーカス枠が描かれてしまっていた——
`keyboardPaneFor` はこれを構造的に不可能にする。すべての消費側が、この関数が解決する
ただ一つの値だけを読むようになったからである。

**`focusedPane` 自体が「進む」のは `PaneLayout.Single` のときだけである。**
`HomePane.ordinal + 1` がそのままスタックの現在の深さを兼ねるため、`HomeScreen` は別途深さの
状態を持つ必要がない——`platform/BackHandler`（Android では実際の戻るジェスチャー/ボタンを
横取りし、デスクトップでは no-op）が `homeBackAction(layout, depth, searchBarOpen)`
（後述）の有効/無効に従って1段戻し、記事の選択も同じ軸で前に進める——ただし `visiblePanes` が
depth によって実際に変化するレイアウトでのみ。`ArticleListPane` の `onSelectionAdvance`
（ドロワーの `FeedListPane` 自身の同名の引数も同様——こちらは行選択時にドロワーを閉じるだけでなく
`focusedPane` を必ず `HomePane.ArticleList` へ動かす）は `PaneLayout.Triple` と
`PaneLayout.Dual` のどちらでも no-op である。`visiblePanes` が返すペインはどちらのレイアウトでも
既に画面上に揃っており、それ以上 `focusedPane` を進めても、記事一覧ペインが次のフレームで
`focused = false` を報告するだけで得るものが無いからだ——詳細は `ArticleListPane` 自身の
この引数の KDoc を参照。**この `focusedPane` の状態とは独立に、実際の Compose キーボード
フォーカスが画面全体で存在しうる場所はちょうど2つしかない: `homeKeyboardShortcuts` が付与された
ルート `Box`、そして現在入力中のテキストフィールド（サイドバーの検索欄、狭いレイアウト自身の
検索欄、あるいはフィード一覧の行のインライン改名欄のいずれか）である。** 一覧の行自体は決して
実フォーカスを持たない（`ListRowChrome.kt` の `listRowClickable` が
`Modifier.focusProperties { canFocus = false }` でこれを無効化している）——行への到達は完全に
このアプリ独自の矢印キー/J・K モデルによるものであり、Tab 順やクリックによるフォーカス移動では
ない。各ペイン自身の `onActivated` は `HomeScreen` の小さなヘルパーを経由し、`focusedPane` への
作用に加えて実フォーカスをルート `Box` へ戻す——これが、どのテキストフィールドにフォーカスが
あっても、行やボタンへのタップでそこからフォーカスを外せる理由である。

`homeBackAction(layout, depth, searchBarOpen)` は、ペインだけを見る純関数 `canNavigateBack(layout,
depth)`（「1段戻っても実際には画面が変わらない」場合に常に `false` を返す — `Triple` では常に。
`Dual` でも常に——`visiblePanes(Dual, *)` はどの深さでも同じ2ペインを返すため——`canNavigateBack`
が無かった頃は、そこでの戻る操作が何も起こさず黙って消費されていた）に、「戻る操作が実際に何を
するか」のもう半分——展開された検索バーが開いているときはペインを1段戻すのではなくそれを閉じる
こと（下記「`ArticleFilter` から独立した検索」参照）——を組み合わせたものであり、記事一覧が
実際に見えている場所（`Single` の depth 2、`Dual` の全深さ）では `canNavigateBack` より
優先される（バーを閉じることは常にそこでの画面を変えるため）。**記事一覧自身の深さで（バーが
閉じている場合に）`canNavigateBack`/`homeBackAction` が `false`/`None` に解決されるのは
見落としではなく意図的である**——`HomeScreen` の `BackHandler` は `None` のとき自身を無効化するため、
そこでの戻る操作はプラットフォームの既定動作（Android ではアプリの終了）へフォールスルーし、
このコードベースが戻り先の無い操作を握りつぶすことはない。

ドロワーが存在する以前と異なり、`PaneLayout.Dual` はもはやスタック上をスライドする窓では
**ない**: フィード一覧がペインではなくドロワーになったことで、`visiblePanes(Dual, depth)` は
深さに関わらず常に同じ `[ArticleList, ArticleDetail]` を返す——記事詳細ペインは記事一覧の常設の
隣人であり、Gmail 自身のタブレット閲覧ペインと同じ形で、自身の戻る操作を持たない
（`ArticleDetailPane` の `onNavigateUp` はそこでは `null`。`swipeNavigation` がそれとは独立した
別のシグナルである理由は下記「`ArticleFilter` から独立した検索」参照）。

狭いレイアウトでは、残る2ペインを `ui/home/NarrowPaneRow.kt` がホストする。これが、スタックの
出入りをまたいで各ペインのスクロール位置を保つ仕組みである。`Dual` はどちらのペインも一切
アンマウントしない（`visiblePanes` がそこでは変化しないため）が、`NarrowPaneRow` はそれでも
`visible.forEach` ループではなく各ペインをそれぞれ固定のソース位置から出力する——ループでは
全イテレーションが同じ compose グループキーを共有するため、将来リスト内での位置が変わりうる
ペインが追加された場合、画面から消えていないにもかかわらず破棄・再構築されてしまう（ペインを
追加する場合も、ループのイテレーションではなく必ず専用の `if` として足すこと）。`Single` は
スタックの深さが `ArticleList` と `ArticleDetail` の間で変わるたびに、1つを除く全ペインを実際に
アンマウントするため、そちらは `rememberSaveableStateHolder` が各ペインの `rememberSaveable`
由来の state（実質は `LazyListState`。`rememberLazyListState` がその形で保持している）を保存し、
リスト state の**初期** index/offset として復元する。よってスクロールは一切走らず、
`known-issues.md` が未修正の上流 Compose クラッシュの要因として挙げている `scrollToIndexIfNeeded`
の経路に新たな呼び出しが増えることもない。`ArticleListPane` の `lastFilter` が
`ArticleFilter.encode()` の文字列を保持する `rememberSaveable` なのも同じ理由による: ペインが
アンマウントされている間にフィルタが変わりうる（通知の `ShowFeedDetail`、あるいは閲覧中フィードの
削除）ため、素の `remember` では再マウント時に新しいフィルタで初期化されてしまい、復元された位置が
前のフィルタの一覧を指したまま先頭へのリセットも起きない。

これが、記事リーダーの WebView を無条件にコンポーズし続けること（前述の「記事リーダー（ネイティブ WebView）」参照）が
デスクトップにおいて安全である理由でもある: デスクトップは常に `Triple` にしか解決されないため、
WebView をホストするペインを含む3ペインすべてがアプリのライフタイム全体でマウントされ続ける。
`Dual` も今ではこれを一切アンマウントしない（記事詳細ペインが常に表示される2ペインの一方であるため）。
アンマウントが起きるのは `Single` の depth 2↔3 の遷移だけであり、Android では（重量級 AWT
インターロップの懸念が無いため）問題ない。

狭いレイアウトでは、`initialPaneFor(layout, saved)` は `saved` の値に関わらず常に `ArticleList`
に解決される——ドロワーが登場する前のバージョンが保存した `HomePane.FeedList` も、もはや
復元先として成立しないため（そもそも復元できるペインが存在しない）——一方 `Triple` では `saved`
をそのまま返す（これまでどおり、最後に読んでいた記事）。一覧も無く、どうやってそこへたどり着いたか
という文脈も無いまま記事詳細にいきなり着地するのは、スマートフォンのセッションでは使い勝手が
悪いためである。このクランプは、レイアウト後の実際の幅が判明した最初のフレームで一度だけ適用され、
以後は二度と適用されない — 後からのリサイズや回転で、読んでいる最中のユーザーを弾き出しては
ならないため。同じ最初のフレームの副作用として、`shouldAutoOpenFeedDrawer` が真を返す場合——
フィードが1件も無くクラウド連携も未設定の狭いレイアウトで、それを解消するはずの「+」ボタンが
既定で閉じているドロワーの中にある場合——にはフィードドロワーも自動的に開く
（`HomeViewModel.hasAnyFeed()` は、DB への一回きりの問い合わせであり、既に collect 済みの
`feeds` `StateFlow`——`Eagerly` 共有の初期値が「空」と「まだ読み込んでいない」を区別できない——
とは別物である）。

**検索は `ArticleFilter` から独立しており、そのバリアントではない。** `core/ArticleFilter.kt` が
持つのは `All`/`Starred`/`Feed`/`Tag`/`Folder` のみで、`Search` というケースは存在しない。クエリ
（`HomeViewModel.searchQuery`）は、選択中のフィルタを置き換えるのではなく、それを絞り込む。
`HomeViewModel.searchActive`（`_searchBarVisible && searchQuery.value.isNotEmpty()`）が、記事一覧
が今表示しているのが検索結果（`HomeViewModel.searchResults`。これ自体が現在のフィルタでスコープ
されている——下記 `FtsSearch.articleScopeSql` 参照）かフィルタ自身の一覧（`HomeViewModel.articles`）
かを示す唯一の派生フラグであり、`ArticleListPane` はこれを読んで、どちらを1つの
`ArticleListPaneContent` 呼び出しに渡すかを選ぶ。フィルタが決して置き換えられないため、検索が
終わるときにスナップショットを取ることも復元することも一切不要になる——以前の設計がまさにそれの
ために必要としていた `SearchScopeEntry`/`enterSearchScope`/`exitSearchScope` の仕組みはもはや
存在しない。

**`_searchBarVisible` は検索にまだ残っている唯一の実体的な状態**であり、狭いレイアウトのためだけに
存在する: `PaneLayout.Triple` では `FeedListPane` 自身のクエリ欄が常設なので、`HomeScreen` 自身の
`LaunchedEffect(layout)` がそこでは常に `true` に保つ。狭いレイアウトでは `false` から始まり、
`ArticleListTopBar` の検索アイコン（`onSearchClick` → `setSearchBarVisible(true)`）と、展開された
バー自身の戻る矢印（`onExitSearch` → `homeBackAction` の `CloseSearchBar` ケース →
`setSearchBarVisible(false)`）で切り替わる。その存在理由は「クエリにまだ文字が残っている」ことと
「バーが今開いている」ことの間のまさにその隙間である: 狭いレイアウトでバーを閉じるときは、クエリを
消さずにフィルタ自身の一覧を再び表示しなければならず、それにより後で検索アイコンを再度タップした
ときに同じ結果が再び表示される——`searchActive` が両方を要求することがそれを実現している。
`HomeViewModel.pendingSearchFocus` は、依然として一発イベントではなく latch された
`StateFlow<Boolean>` である。理由も変わらない: 入力欄へフォーカスを要求する操作は、バーを開くのと
同じクリックの中で発生するため、実際に入力欄を持つことになるコンポーザブルはまだコンポーズされて
おらず、購読者のいない `SharedFlow` では要求が黙って失われてしまう。`setSearchBarVisible(false)` は、
以前フィルタを離れることでクリアしていたのと同じように、消費されなかった要求を落とす。

**サイドバーにはもう「検索」クイックフィルター行が存在しない。** `FeedListPane` の常設欄
（あるいは狭いレイアウトでバーが開いた後の展開欄）へ直接入力することが唯一の入口である——
`searchActive` はクエリだけから導かれるため、タップ・入力欄に乗ったキーボードカーソル・矢印キー
によるナビゲーションはすべて同じに振る舞う。選択的にトリガーする別個の「検索に入る」アクションは
存在せず、したがってタップとキー入力とで検索の始まり方が非対称になることもない。これはまた、
`buildOrderedFeedListRows` が `includeSearchRow` パラメータを必要とせず、`feedListRowIndex` も
もはや「検索」行を特別扱いしないことを意味する——その行はどのレイアウトであれ、そもそも
存在しないからである。

**検索自体のスコープは常に「すべてのフィード」ではなく、現在選択中のフィルタである。** クエリが
有効な間に別のフィード/フォルダ/タグを選択すると、即座に**同じ**検索がその範囲に再スコープされる
（`_rawSearchResults` はデバウンスされたクエリと並んで `_filter` も combine する）。「すべての
フィード」に切り替えればすべてを検索する。`FtsSearch.articleScopeSql` は、`ArticleFilter` の
各バリアントに対応する `WHERE` 句フラグメントを、`articles.sq` 自身の `watchAll`/`watchStarred`/
`watchByFeed`/`watchByTag`/`watchByFolder` クエリと一行単位で一致するように構築する——それらの
既存の非対称性（`Starred` と `Feed` は `feeds` に一切 JOIN しないため、購読解除済みフィードの
スター付き記事・自身の記事もそのスコープでは表示される。`All`/`Tag`/`Folder` は JOIN する）も
含めて。このクエリ機構と組み合わさる部分については `sync-architecture.md` の「FTS5 handling」と
`db-schema.md` 自身の `articles_fts` 節を参照。

**検索を終えても何も復元されない。** クエリを消す（あるいは狭いレイアウトではバーを閉じる）ことは、
単に `ArticleListPane` の表示内容をフィルタ自身の一覧に戻すだけである——`selectFilter` と
`setSearchQuery` は互いに独立している（フィルタの切り替えはクエリに一切触れず、クエリの変更は
フィルタに一切触れない）。そして `_pinnedReadArticles` は、クエリの変更でクリアされるのではなく、
フィルタ自身の一覧とその検索結果との間で意図的に共有される——そのため、検索の中から既読にした
記事は、あたかも普通の一覧から既読にしたかのように、未読のみ表示の下でも引き続き表示され続ける。

したがって `ArticleListPane` は `searchActive` に関わらず1つの連続したコンポーザブルを描画する
——早期 `return` はなく、`PaneLayout.Single` の `NarrowPaneRow` の各ペインのような分岐ごとの
（アン）マウントも無い。`baseListState`/`searchListState`（独立した2つの `LazyListState`）と
`lastFilter` はすべて最上部で無条件に宣言されており、検索の内外を切り替えてもどちらの一覧の
スクロール位置も破棄されない——検索を経て戻ってきたときにフィルタ自身の一覧が元の位置のままである
ために、スナップショット・復元の手順は一切不要である。

#### iOS

`external-spec.md` は iOS/iPadOS をまず Compose、のちにネイティブ SwiftUI と計画している。
本節のモデルはそのまま持ち越せない箇所がある:

- **ドロワーは Android のイディオムであり、狭いレイアウト全般に普遍的なものではない。**
  iOS/iPadOS は compact 幅で `NavigationSplitView` のサイドバーを押し込まれたナビゲーション
  スタックに畳む（Mail.app、NetNewsWire、Reeder）——これはドロワーが存在する以前にこのアプリが
  していたことであり、`git log` にも今なお残っている: `Single` の depth 1 で `visiblePanes` が
  `[FeedList]` を返していたこと、`FeedListPane` 自身の通知ベル、`onEnterArticleList`——これらは
  ドロワーと引き換えに削除された（戻りリップル自体はこれとは無関係で、現在も
  `HomePaneLayout.kt` の `shouldFlashReturnedArticle` として存在する）。`paneLayoutFor` と `visiblePanes` の
  `Triple`/`Dual` の場合分けは iPadOS にそのまま持ち越せる（`NavigationSplitView` の3カラム・
  2カラムモードにそのまま対応し、`Dual` の常設の閲覧ペインは iPad 自身のスプリットビューとも
  一致する）——持ち越せないのは `Single` の見せ方だけである。
- **`hasNativeAppMenu`（`platform/PlatformOs.kt` 参照）は「ネイティブなアプリメニューバーが
  無い」ことを表す Android 側の代用であって、iOS 用ではない**——iOS もそこでは `false` になる
  ため、このフラグが今新たに担っているヘッダーの `app_name`/設定フッターの判断は、iOS が
  実装される前にプラットフォームごとに分割する必要がある。
- **`HomeBackAction.None` が OS へフォールスルーする挙動には iOS の対応物が無い**——
  `OnBackPressedDispatcher` に相当する戻るジェスチャーも、「アプリを終了する」という概念自体も
  無い。iOS 自身の戻るナビゲーションは、このコードベース自身が描く UI 要素である。
- **ドロワーのエッジスワイプで開くジェスチャー（`gesturesEnabled`）は iOS 自身の
  `interactivePopGestureRecognizer`（左端スワイプ ＝ 戻る）と衝突する**——ドロワー自体を
  Android 限定に留めておく限りは問題にならない。
- edge-to-edge レイアウト（root の `Box` が `WindowInsets.safeDrawing` の水平方向のみを適用し、
  各ペインが残りの方向を自分で適用する）はそのまま持ち越せる——これは iOS 自身のセーフエリア
  対応（ノッチ／Dynamic Island／ホームインジケータ）が必要とするのと同じ形である。

### 楽観的な既読/スターピン留め

`HomeViewModel._pinnedReadArticles`/`_pinnedUnstarredArticles` は、ユーザーの操作の瞬間に一覧が
その足元で動いてしまうのを防ぐ仕組みである: 未読記事を選択すると DB への既読反映は非同期
（`dbWriteDispatcher`）で行われるが、行は**今すぐ**既読として表示されなければならず、かつ
未読のみ表示では次のフィルタ切り替えまで一覧から単純に消えてもいけない。`articles` の combine
は、ピンが存在すればそこから各行の `is_read`/`is_starred` を解決し、無ければ生クエリの値に
フォールバックする。未読のみ表示では、既読ピン留めのメンバーシップ自体を「今は表示すべき程度に
未読」として扱う。したがってこれらのピンは、意図的に DB を先回りしうる楽観的キャッシュである
— しかし、ピンを立てる操作自体は「その後 DB が実際に追いついたか」を再確認しないため、
再検証なしでは、外部からの変更（他端末の同期による「未読にする」・再スター、または論理削除の
tombstone）を、書き込みが in-flight の短い間だけでなく**永久に**隠し続けてしまいかねない。

`HomeViewModel.reconcilePinnedArticlesAndSelection` はこの隙間を埋める: `articleChangeSignal` コレクタ経由で
`articles` への書き込みのたびに走り、ピン留め済みの全 ID — および現在の選択のキャッシュされた
フラグ — を `ArticleRepository.aliveArticleFlags` に対する1クエリでまとめて再検証し、記事が
既に存在しないか、フラグがピンの値と一致しなくなったものを外す（選択については更新する）。
この読み取りをあえて `dbWriteDispatcher` — 各ピン設定箇所（`selectArticle`/`toggleRead`/
`toggleStar`/`markAllRead`/`markSelectedUnread`）が自身の DB 書き込みを投入するのと同じ直列
（`limitedParallelism(1)`）ディスパッチャ — 経由で行っている。そして、これらの各箇所はいずれも
ピン/選択の状態を更新する**前**に、その書き込みを投入している（後にではない）。ピン/選択の
フィールドは `MutableStateFlow` なので、ここであるピンを観測できたということは（flow の
メモリ可視性保証により）そのピンを正当化した書き込みが既に `dbWriteDispatcher` に投入済みで
あることを意味する。同じ FIFO ディスパッチャ経由でこの読み取りを行うことで、その書き込みの
**後**に必ず実行されることが保証され、この関数がまだ in-flight の楽観的書き込みを外部変更と
誤認してまだ正しいピンを外してしまう、という事態が構造的に起こらなくなる。これは実際のマルチ
スレッド・ディスパッチャ（`Dispatchers.Default`）下でのみ意味を持つ懸念であり、既存の単一
スケジューラのテストスイートでは直接再現できない — この不変条件はコードレビューと各呼び出し
箇所のコメントによって担保されており、専用の競合テストによるものではない。

同一フィルタの選び直しは、両方のピン・選択・カーソルをすべてそのまま残す（上記の
`selectFilter` の早期 return）。記事一覧は今や、常に画面上のペインである（`Triple`/`Dual`）か、
リーダーで読んでいる間を除き narrow レイアウトが画面上に保ち続ける唯一のペインである
（`Single`）かのどちらかなので、これは妥当な挙動になった——現在のフィルタを選び直すことが、
かつてのように「そこへの**復帰**」と「そこへの**入場**」を区別する必要が二度となくなったためである
（この、今は削除された仕組みについては上記「Home の適応的ペインレイアウト」の「iOS」を参照）。
一方、実際のフィルタ変更はそこに至るどの経路でも変わらず `_selectedArticle` を両方のピンと
一緒にクリアする。これはピンそのものにとって本質的に重要である——選択を残したままだと、次に
ユーザーが「未読のみ」を再度 ON にした瞬間に `HomeViewModel.pinnedReadArticlesKeepingSelected`
がそこから既読ピンを再シードしてしまい、このリセット自体が意味を失ってしまう。
