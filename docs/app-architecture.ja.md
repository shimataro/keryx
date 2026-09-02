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
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels, UpdateDownloader, ReleaseFeedSource（アプリ内アップデート——後述の「アプリ内アップデート」参照）
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, OpmlImporter, OpmlOpenHandler（importOpmlAndNotify。デスクトップと Android の「`.opml` ファイル関連付け」で共有）, CloudSession, NotificationCenter, MergeSql, MergeFailureClassifier, MergeSchema, IdGenerator, CloudConnectFlow, OAuthConnectFlow, OAuthRedirectTransport（interface + CustomUri）, OAuthCallbackParams, StartupMaintenanceTasks（refreshFeedsAndNotify/checkForUpdateAndNotify/maybeRebuildFtsIndex）, UpdateChecker/UpdateRepository/UpdateAsset/UpdateInstallPolicy/UpdateInstaller（expect 相当の interface）/AvailableUpdate/UpdateState（アプリ内アップデート——下記「アプリ内アップデート」参照）
    di/           AppModule（+ expect platformModule）
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot, DatabaseFile, InstallLocation, FileSystemExtras, ZipExtractor（すべて expect）
    ui/           theme/, navigation/, setup/, home/（3ペイン + 検索 + 通知センター）, article/, settings/, i18n/
    LaunchArg.kt  起動時の引数（`keryx://` URI か `.opml` パスか）を分類する — プラットフォーム非依存、パッケージ直下
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq（7 テーブル）
  commonMain/composeResources/  values/strings.xml, drawable/（アイコンは SVG ではなく Android
    Vector Drawable XML — Compose Multiplatform の SVG デコーダはデスクトップ/iOS 専用で Android では
    実行時にクラッシュするため。VectorDrawable XML は `painterResource` が全ターゲットで描画できる唯一の画像形式）
  jvmCommonMain/kotlin/…/  デスクトップと Android の両方が共有する actual（どちらのプラットフォーム
    API にも依存しない）: FileIO, Gzip, Sha1, ContentDigest, Pkce, FileTokenStorage, AppInfo,
    CloudStorageAvailability（後者2つは共有生成 BuildConfig を読むだけ）, FileSystemExtras,
    ZipExtractor（アプリ内アップデート——下記「アプリ内アップデート」参照）
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt（runStartupTasks/backgroundUpdateLoop/handleOpenedOpmlFile というデスクトップ固有のオーケストレーションのみ。実際のメンテナンス処理は commonMain の StartupMaintenanceTasks に委譲）+ jvmCommonMain がカバーしない expect の actual（DatabaseDriverFactory, AppDirs, FilePicker, DatabaseMerger, PlatformModule, InstallLocation）+ LoopbackRedirectTransport, OAuthUriParser, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage 実装（Keyring/File/SecurityCliTokenStorage）, DesktopOs（isMacOs/isWindows/isLinux/isTouchPrimary=false/hasNativeAppMenu=true/hasSystemTray=true）, DesktopLookAndFeel（Swing L&F: Linux は FlatLaf）
    tray/      KeryxTray（プラットフォーム分岐）, MacTray, LinuxTray + StatusNotifierItem/dbusmenu の D-Bus オブジェクト
    platform/update/  DesktopUpdateInstaller, UpdateScriptWriter（純粋な自己置換／msiexec スクリプトのテンプレート）, ProcessLauncher/RealProcessLauncher（テストがフェイクに差し替える detached 起動のシーム）
  androidMain/kotlin/…/  jvmCommonMain がカバーしない expect の actual: DatabaseDriverFactory（バンドル
    SQLite、後述）, DatabaseFile（`databaseFilePath()` — `Context.getDatabasePath` で、
    AppDirs.appDataDir()/`Context.filesDir` とは別ディレクトリになる。db-schema.ja.md 参照）,
    InstallLocation（常に ANDROID_SIDELOADED か ANDROID_STORE のどちらか——下記「アプリ内アップデート」
    参照）,
    AppDirs/BrowserOpener/ClipboardEntries（AndroidAppContext 経由 — KeryxApplication.onCreate
    で一度だけ設定される静的 Context ホルダ）, PlatformModule（Ktor OkHttp エンジン、Dropbox/OneDrive
    プロバイダを登録した CloudSession — 下記 Provider/DI 参照。加えて AndroidNotificationSink、下記
    「バックグラウンド更新」参照）, CloudStorageAvailability（Dropbox/OneDrive は実判定、Google Drive は
    `false` 固定 — 理由は sync-architecture.ja.md の「Android で Google Drive が未対応な理由」参照）,
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
    `ListRowChrome.android.kt` の `listRowSurface`（`ListRowKind.NavItem` 行は
    `NavigationDrawerItem` 風のピル形ハイライト、`ListRowKind.ListItem` 行はフルブリード —
    詳細は同ファイル自身の KDoc）、TooltipIconButton/ToolbarIconGroup/FlatTooltipContent
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
    無いため、FeedListToolbarRow/GeneralTab が独自の設定/バージョン情報導線を持つ）,
    SelfUpdateCheck（インストール元パッケージ名に基づく判定、下記「バックグラウンド更新」参照）,
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
増分投入）、`rebuildIndex()`（日次アイドルの全再構築 heal のみ）を持つ。`FtsSearch.search()` は語の長さで
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

### CloudSession / SyncRepository

`CloudSession` が現在の `CloudStorage`（デスクトップは Dropbox / Google Drive / OneDrive、Android は
Dropbox / OneDrive — sync-architecture.ja.md の「Android で Google Drive が未対応な理由」参照）を
提供し、アクセストークンの自動リフレッシュを担う。
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
手で書く」という形をそのまま踏襲）、そして `platform/UpdateInstaller`（新規の expect 相当の
interface。`OsNotificationSink` とまったく同じように `platformModule` 経由でバインドされる
プラットフォームごとの `single<UpdateInstaller>` で、テストではフェイクに差し替えられる）。
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
いる（testing.ja.md 参照）。

デスクトップと Android の `UpdateInstaller` actual はコードを一切共有していない——デスクトップ
（`platform/update/DesktopUpdateInstaller.kt`）は `platform/ZipExtractor.kt`（`jvmCommonMain`。
`FileIO`/`Gzip` とまったく同じ形で Android と共有）経由で ZIP を展開し、現在のインストール先の
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

**Android のリーダー（`ui/home/ArticleDetailPane.kt`。commonMain 共有のコンポーザブル）は、
狭いレイアウトではスワイプによる前後移動も持つ**（`ui/home/ArticleSwipeNav.kt`） — リーダー上の
水平ドラッグで次/前の記事へ移動する。有効化条件は `isTouchPrimary && onNavigateUp != null &&
article != null`（`HomePaneLayout.kt` が他所でも使っている、狭いレイアウトを示す既定の
nullable-callback シグナルと同じ — 下記「Home's adaptive pane layout」参照）で、
`PaneLayout.Triple` およびデスクトップでは無効になる。Android の `WebView`（`AndroidView` 経由で
埋め込まれる）は通常の in-tree ビューだが、タッチ入力は自分自身で消費してしまうため、このジェスチャーは
`platform/NativeMenu.android.kt` の長押しや `ui/home/FeedListDragGestures.kt` の並べ替えドラッグと
同じ方式で調停する: `pointerInput` ループが `PointerEventPass.Initial`（この祖先ノードに WebView 側の
interop 処理より先に届くパス）を監視し、ドラッグが水平方向であると確定する（touch slop を超え、かつ
垂直方向より水平方向の移動量が大きい）までは一切イベントを consume しない。これにより通常の縦方向
ジェスチャー（WebView 自体のスクロールやリンクタップ）は妨げられない。確定した時点で初めて consume を
始め、WebView 側のジェスチャーをキャンセルさせる。`HorizontalPager` の採用は検討した上で見送った —
ページごとに `WebView` を 1 つずつマウントする必要があり、隣接ページの本文を先読みすると、
`error-design.md` の「選択した瞬間に既読」というルールに従って、ユーザーがまだスワイプしてすら
いないページまで本文をロード（＝既読化）してしまう。これは `HomeViewModel.selectArticle` の設計意図に
反する。代わりに、このジェスチャーはドラッグが確定した時点で `HomeViewModel.selectNext`/
`selectPrevious`（デスクトップの J/K キーボードショートカットと同じ呼び出し）を駆動するだけで、
リーダー本文のスライドは、2 つ目の WebView を差し替えるのではなく、既存の単一の WebView インスタンスに
対する単純な `Modifier.offset` で行う。

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
以下のデスクトップ実装とは異なり、Android には固有の挙動が 2 つある: 長押しが確定しても
`onOpen`（デスクトップの「右クリックで行を選択する」フック）は一切呼ばれない — Android の
長押しはメニューを開くだけで、行の選択は行わない。また同じ `awaitEachGesture` ループは、
指が `viewConfiguration.touchSlop` を超えて動いた時点で長押し判定を打ち切るため、行の上で
始まったゆっくりしたドラッグ（`LazyColumn` のスクロール）が長押しと誤認されることもない。

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
`composeResources/drawable/` 配下のバンドル Android Vector Drawable XML）、`expect`/`actual`
でプラットフォームごとに分割されている — 2つのターゲットが意図的に異なるアイコンセットを
バンドルしているため。デスクトップ側の `actual` は Tabler Icons（MIT）を使用する
（デスクトップ3OS共通で macOS 寄りの見た目に近づけるための選択。詳細は `ui-guidelines` skill）。
Android 側の `actual` は Material Symbols Outlined（Apache-2.0）を使用し、Android 自身のネイティブな
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
（`FeedListPane` / `ArticleListPane` それぞれの `onSelectionAdvance`。どちらも `Triple` では
`null` — 全ペインが既に見えており、進む先がない）、`platform/BackHandler`（Android では実際の戻る
ジェスチャー/ボタンを横取りし、デスクトップでは no-op）が1段戻す — その有効/無効は
`homeBackAction(layout, depth, searchScopeReturnPending)` で決まる。これは、ペインだけを見る
純関数 `canNavigateBack(layout, depth)`（「1段戻っても実際には画面が変わらない」場合に常に
`false` を返す — `Triple` では常に。`PaneLayout.Dual` の深さ 1→2 でも、下記のスライド窓が同じ
2ペインを表示するため同様 — この関数が無かった頃は、そこでの戻る操作が何も起こさず黙って消費されて
いた）に、「戻る操作が実際に何をするか」のもう半分——復元待ちのスナップショットがあるときは
ペインを1段戻すのではなく検索スコープを抜けること（下記「狭いレイアウトでの検索」参照）——を
組み合わせたものであり、`canNavigateBack` だけでは「何もしない」はずの場面でもこちらが優先される
（検索を抜けることは常に画面を変えるため）。`PaneLayout.Dual` は、そのスタック上を
スライドする2ペインの窓であり、単純な隣接ペア表示ではない: 記事一覧はどの深さでも表示される2ペインの
一方であり続けるため、記事にドリルインするとフィード一覧が記事詳細ペインに入れ替わる形になり、
一覧自体が画面外にスライドすることはない。

狭いレイアウトでは、3ペインは `ui/home/NarrowPaneRow.kt` がホストする。これが、スタックの
出入りをまたいで各ペインのスクロール位置を保つ仕組みであり、2つのレイアウトは別々の理由で
それを失うため、両方に対処している。`Dual` は記事一覧をアンマウントしないが、スライドによって
`visiblePanes` の結果の index 1 から 0 へ移動する。`visible.forEach` ループでは全イテレーションが
同じ compose グループキーを共有するため、位置が変わったペインは画面から消えていないにもかかわらず
破棄・再構築されていた。`NarrowPaneRow` は代わりに各ペインをそれぞれ固定のソース位置から出力する
（ペインを追加する場合も、ループのイテレーションではなく必ず専用の `if` として足すこと）ので、
ペインは一度も dispose されず `LazyListState` をそのまま保持する。`Single` は1つを除く全ペインを
実際にアンマウントするため、そちらは `rememberSaveableStateHolder` が各ペインの `rememberSaveable`
由来の state（実質は `LazyListState`。`rememberLazyListState` がその形で保持している）を保存し、
リスト state の**初期** index/offset として復元する。よってスクロールは一切走らず、
`known-issues.md` が未修正の上流 Compose クラッシュの要因として挙げている `scrollToIndexIfNeeded`
の経路に新たな呼び出しが増えることもない。`ArticleListPane` の `lastFilter` が
`ArticleFilter.encode()` の文字列を保持する `rememberSaveable` なのも同じ理由による: ペインが
アンマウントされている間にフィルタが変わりうる（通知の `ShowFeedDetail`、あるいは閲覧中フィードの
削除）ため、素の `remember` では再マウント時に新しいフィルタで初期化されてしまい、復元された位置が
前のフィルタの一覧を指したまま先頭へのリセットも起きない。

これが、記事リーダーの WebView を無条件にコンポーズし続けること（下記「記事リーダー」参照）が
デスクトップにおいて安全である理由でもある: デスクトップは常に `Triple` にしか解決されないため、
WebView をホストするペインを含む3ペインすべてがアプリのライフタイム全体でマウントされ続ける。
`Single`/`Dual` は、対象のペインが現在表示されていない場合にそれをアンマウントするが、これは
Android では（重量級 AWT インターロップの懸念が無いため）問題ない。

狭いレイアウトでは、`initialPaneFor(layout, saved)` が起動時に `HomeScreen` が復元するペインも
クランプする: `Triple` では `HomePane.ArticleDetail` をそのまま復元する（これまでどおり、最後に
読んでいた記事）が、`Single`/`Dual` では `ArticleList` に切り下げる — 一覧も無く、どうやってそこへ
たどり着いたかという文脈も無いまま記事詳細にいきなり着地するのは、スマートフォンのセッションでは
使い勝手が悪い。このクランプは、レイアウト後の実際の幅が判明した最初のフレームで一度だけ適用され、
以後は二度と適用されない — 後からのリサイズや回転で、読んでいる最中のユーザーを弾き出してはならない
ため。

**狭いレイアウトでの検索**は、周囲のクロームだけでなく入力欄自体が移動する — 詳しい設計は
`ui-guidelines` スキルの「Adaptive pane layout & touch affordances」節を参照
（`ui/common/KeryxSearchBar.kt` の `KeryxCollapsedSearchBar`/`KeryxExpandedSearchBar`、および
narrow/`Triple` の分岐が `PaneLayout` や `isTouchPrimary` ではなく `onSelectionAdvance`/
`onNavigateUp` が `null` かどうかで決まる理由）。`HomeViewModel.pendingSearchFocus` が一発
イベントではなく latch された `StateFlow<Boolean>` なのも、上記の深さカーソルと同じ理由による:
入力欄へフォーカスを要求する操作は、スタックを進めるのと同じクリックの中で発生するため、実際に
入力欄を持つことになるペインはまだコンポーズされておらず、購読者のいない `SharedFlow` では要求が
黙って失われてしまう。

検索専用の `HomePane` は存在しない — どの入口も `HomePane.ArticleList` の中身を差し替えて
`ArticleFilter.Search` を設定するだけで、スタックを進めるとは限らない（記事一覧自身の検索アイコンは
進めないが、折りたたみバーは進める）— そのため単純な「1段ポップ」では、どちらの
経路でも正しく元に戻せない。`HomeViewModel.enterSearchScope(returnPane)` が、切り替え直前の
filter・選択行と、狭いレイアウトの戻る操作が着地すべきペインをスナップショットし、
`exitSearchScope()` がその両方を復元してそのペインを返す。これを上記 `homeBackAction` の
`ExitSearch` が `PopPane` の代わりに使う。検索クエリ自体はこの一連の処理では一切触れられず、
折りたたみバー上にそのまま残る。

`enterSearchScope` のスナップショットには、その瞬間の閲覧コンテキスト — 既読ピン留め・未読
スターピン留めのマップ、選択中の記事、キーボード操作用カーソル（下記「楽観的な既読/スター
ピン留め」参照）— も含まれる。検索に入るのも他のフィルタ変更と同じく `selectFilter` を経由する
ため、それらが全部クリアされてしまうからである。`exitSearchScope` はこれを復元するが、そのまま
再生するわけではない: スナップショットされた全 ID を、下記の再検証と同じ
`ArticleRepository.aliveArticleFlags` を使って DB の**現在の**フラグに照らして再解決する。これに
より、検索結果自体から加えられた変更や、検索中に同期で届いた変更が、凍結されたスナップショット
で上書きされることがない。検索の**中で**付いたピンはこの対象に一切含まれない — 含まれるのは
検索に入る**前**にピン留めされていたものだけである。含めてしまうと、戻った先のフィルタの一覧に
無関係なフィードの記事が混入してしまう（下記 `articles` の combine の `extra` 処理を参照）。
フィルタ自体が別のものにフォールバックした場合（`validateFilterTarget` が対象の削除を検知した
場合）は、復元自体を行わない — スナップショットのピン・選択は**元の**フィルタに属するものであり、
フォールバック先のものではないため。

検索専用の `HomePane` が存在しないため、`ArticleListPane` は `NarrowPaneRow` のペイン単位の
`SaveableStateHolder` を経由せず、同じコンポーザブル内の早期 `return` から `SearchListPane` を
描画している。そのため記事一覧自身の `listState`/`lastFilter` は、検索が有効な間もコンポジション
に残り続けられるよう、その `return` より**前**で宣言しておく必要がある — それらを使う側の
コンテンツのすぐ近く（`return` より後）で宣言すると、検索を開くたびに破棄・再生成され、戻る
たびに一覧が先頭にリセットされてしまう。`filter is ArticleFilter.Search` の間は `lastFilter` を
更新しないようにもしてあり、検索に入る前と同じ filter に戻った時は「変化なし」と判定されて、
通常のフィルタ変更時に働く先頭へのリセットがスキップされる。選択中の記事を復元すると、
`ArticleListPaneContent` 自身の「選択を表示範囲に収める」スクロールが remount 時に再発火しうる
— ペインが実際にアンマウントされたケース（選択は常に復元直後のビューポート内に収まっている。
上記「Adaptive pane layout」参照）では無害だが、ここではその保証がない。一覧自身のスクロール
位置と復元された選択は、それぞれ独立したスナップショット由来だからである。
`ArticleListPaneContent` の `preserveScrollPositionOnMount` パラメータはまさにこのために存在する:
`ArticleListPane` は検索が閉じた直後の1回のコンポジションだけこれを立て、mount 時の最初の評価
だけそのスクロールを抑止する — その後の正当な選択変更では通常どおりスクロールする。

**`PaneLayout.Single` での記事一覧への入り直し。** ここまでに述べたどの箇所でも、既に選択中の
フィルタを選び直す操作は行のハイライトが動くだけの no-op である（`selectFilter` 自身の早期
return。下記「楽観的な既読/スターピン留め」参照）——記事一覧ペインが既に画面上にあり、何も
変える必要がない場合はそれで妥当である。`Single` の depth 1 ではこの前提が崩れる: 記事一覧
ペインはそもそも画面上に存在しないため、フィード一覧の行をタップする操作は、それが既に選択中の
フィルターを指していたとしても常に記事一覧への**入場**である——読みかけのセッション（一覧から
一度完全に抜けたあとも、未読のみ一覧に既読済み記事がピン留めされたまま残っている状態）は、遷移先が
たまたま同じフィルターだったからといって復活してよいものではない。`FeedListPane` の
`onEnterArticleList`（その depth でのみ非 null。記事一覧が隣に表示され続ける `Dual` でも
`null`）は、行自身の `vm.selectFilter` 呼び出しの直前に呼ばれ、2つのことを行う:
`HomeScreen` はこれを使って、ペインをホストする `NarrowPaneRow` からホイストされた
`SaveableStateHolder.removeState(HomePane.ArticleList)` を呼び出し、保存済みの `LazyListState`
を復元するのではなく捨てる——これにより一覧は最後にスクロールしていた位置ではなく先頭から開く。
そしてその非 null であること自体が `selectFilter` の `reentering` 引数としてそのまま渡され、
同一フィルタの早期 return を突破させて、閲覧コンテキスト（ピン・選択・カーソル）を別のフィルタが
選ばれたときと同じように作り直させる。

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

`HomeViewModel.reconcilePinnedArticles` はこの隙間を埋める: `articleChangeSignal` コレクタ経由で
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

同一フィルタの選び直しは通常、両方のピン・選択・カーソルをすべてそのまま残す（上記の
`selectFilter` の早期 return）——ただし、それが記事一覧ペインへの**入場**である場合はこの限りで
ない（`PaneLayout.Single` の depth 1。上記「Home の適応的ペインレイアウト」参照）: そこでは
`selectFilter` の `reentering` 引数が、実際にフィルタが変わった場合と同じリセットを強制し、
`_selectedArticle` を両方のピンと一緒にクリアする。ピンそのものにとって本質的に重要なのは選択の
クリアの方である——選択を残したままだと、次にユーザーが「未読のみ」を再度 ON にした瞬間に
`HomeViewModel.pinnedReadArticlesKeepingSelected` がそこから既読ピンを再シードしてしまい、このリセット
自体が意味を失ってしまう。
