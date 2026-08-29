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
- `androidDeviceTest/` — `DatabaseMerger`/`DatabaseSnapshot` の Android 実装向け計装テスト。
  バンドルされた `requery` SQLite（ネイティブライブラリ）を直接開くため、`desktopTest` のような
  プレーンな JVM ユニットテストとしては実行できない（`.claude/rules/android-sqlite-bundling.md`
  参照）。実機または起動中のエミュレータが必要。`composeApp` には `androidUnitTest`/`androidHostTest`
  ソースセットは存在しない — デバイスか Robolectric（現状未導入の依存）のどちらか無しには JVM 上で
  テストできない Android 固有ロジックが無いため。ヘルパーは `AndroidDbTestSupport.kt`
  （`createSchemaDbFile()`。`DbTestSupport.kt` の `fileDb()` に相当するが、本番と同じスキーマ導入経路
  である実際の `AndroidSqliteDriver` 経由で作成する）。範囲は Android 固有の差異が出うる箇所に絞る
  — スキーマバージョンガード、マイグレーション経路、例外**クラス**ベースの失敗分類（Android の
  `SQLiteException` は数値エラーコードを持たない。デスクトップの `DatabaseMerger` は JDBC ドライバの
  `resultCode` を読む点と対照的）、および `NoOpDatabaseErrorHandler` の回帰確認（バンドル SQLite の
  既定エラーハンドラは破損と判定した DB ファイルを削除する。AAR の逆アセンブルで確認済み）——
  `desktopTest` のマージ/スナップショット系スイート全体を移植するものではない。マージ SQL 自体
  （`MergeSql`）は純粋ロジックであり、既に `desktopTest` 側でカバーされているため。

- `androidApp/src/androidTest/` — `androidx.compose.ui.test.junit4.v2.createComposeRule` を
  ホストできる、実際の Android アプリケーションモジュールを必要とする計装 Compose UI テスト
  （例: `nativeContextMenu` の Android `actual` の長押しジェスチャ方針を検証する
  `NativeMenuAndroidGestureTest.kt`。`ui/common/KeryxSearchBar.kt` の Android `actual` を検証する
  `KeryxSearchBarAndroidTest.kt` ——ここには `desktopTest` では一切検証できない M3 固有のリスクがある:
  編集可能な入力欄の `SearchBarDefaults.InputField` は、文字サイズ設定でテキストが最小高 56dp を
  超えて拡大されてもクリップしてはならず、これを最大（1.4倍）設定で確認している）。`composeApp` 自体は
  Android **ライブラリ**モジュール
  （`com.android.kotlin.multiplatform.library`）でありアプリケーションではない——その計装テスト
  （上記の `androidDeviceTest`）は Compose UI ツリーを必要としないネイティブドライバ寄りの範囲に
  絞っているため、Compose を実際にレンダリングするテストは、実際に Android アプリケーションである
  唯一のモジュールであるこちらに置く。

新しいテストは対象コードと同じ相対パスに置く。

## 規約

- フレームワーク: `kotlin.test`（`@Test`, `assertEquals`, `assertIs`, `assertTrue`, `assertFailsWith`）。
  コルーチン: `kotlinx.coroutines.test.runTest`。
- HTTP: Ktor `MockEngine` + `respond(...)`。クライアントは本番 DI と同じ設定
  （`followRedirects=false`, `expectSuccess=false`, フェッチャは `install(HttpTimeout)`）で組む。
- 時刻は `Clock { fixedMillis }`、スケジューリングは `SyncScheduler {}` でフェイクする。
- マージは `platform/DatabaseMerger.merge(...)` を 2 つの `fileDb()` に対して呼んで検証する
  （生コネクションでマージする前に SQLDelight ドライバを close する）。マージ失敗の分類
  （破損／非互換なクラウドDBか、一時的／アプリ側の失敗かの判別。[sync-architecture.ja.md](sync-architecture.ja.md)
  「マージ失敗の分類」参照）も同じ方法で検証する: `SyncMergerTest.kt` の
  `mergeThrows*`／`mergeDoesNotClassify*`／`mergeRethrows*` 系テストが、手作りしたクラウドDBファイル
  （外部スキーマ、実スキーマの `UNIQUE(url)`／`NOT NULL` 制約を外した `feeds` テーブル（実スキーマなら
  違反するデータを保持できる）、バイトを改変した破損ファイル、ローカルより新しい `PRAGMA user_version`）
  に対して `merge` を直接呼び、投げられる例外の型を検証する。`SyncRepositoryTest.kt` は同じ分類を
  `sync()` 経由でエンドツーエンドに検証する。
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
- 実スレッドで DB への並行書き込みを行うテストは `inMemoryDb()` ではなく `fileDb()` を使うこと —— 前者は全呼び出し元を、同期機構を持たない 1 本の共有 JDBC コネクションに固定するため、実スレッド 2 本で SQLDelight のトランザクション管理そのものが壊れ得る。`fileDb()` を使う場合でも、テスト対象と無関係な書き込みを fixture に残さないこと: SQLite の deferred `BEGIN` により、write の前に read を行うトランザクションは、無関係な並行書き込み側のロック昇格を、`busy_timeout` では救済されないリトライ不能な `SQLITE_BUSY` で失敗させることがある —— 詳細は `known-issues.md` の「並行書き込みにより read→write トランザクションがリトライ不能な SQLITE_BUSY で失敗する」を参照。

## `Result<T>` のテスト方針

成功（`Result.Ok`）と失敗（`Result.Err`）の両分岐をテストし、失敗時は具体的な `KeryxException`
サブタイプを検証する（`FeedTimeoutException`, `FeedNotFoundException(isGone=…)`,
`SyncConflictException`, `CloudAuthException`, `FeedDiscoveryException` など）。

## 実行

1 件だけ意図的に `@Ignore` しているテストがある。`ArticleReuseCrashRepro` は、意図的に修正して
いない Compose 側の不具合を再現するもので、有効にすると毎回失敗するため。詳細と、Compose 更新後に
修正されたかを確認する手順は [known-issues.ja.md](known-issues.ja.md) を参照。

```bash
./gradlew :composeApp:desktopTest
```

Android の計装テストスイート `androidDeviceTest`（実際のバンドル SQLite に対する
`DatabaseMerger`/`DatabaseSnapshot` の検証）には実機または起動中のエミュレータが必要:

```bash
$ANDROID_HOME/emulator/emulator -avd <name> -no-snapshot -no-boot-anim &
./gradlew :composeApp:connectedAndroidDeviceTest
```

（タスク名は AGP 9 の `com.android.kotlin.multiplatform.library` プラグイン自身の
`withDeviceTestBuilder` DSL に由来する — 将来の AGP リリースで名称が変わった場合は
`./gradlew :composeApp:tasks --all | grep -i device` で確認する）。`ci.yml` には組み込んでいない
— デスクトップの UI テストは `xvfb` 上で既に CI で動いているが、Android エミュレータはこのプロジェクト
がまだ導入していない別種の CI 課題のため、現状はローカル実行のみ。

`androidApp` 自身の計装テストスイート（Compose UI のジェスチャテスト。上記の
`androidApp/src/androidTest/` を参照）は、通常の `com.android.application` のタスク命名を使う:

```bash
$ANDROID_HOME/emulator/emulator -avd <name> -no-snapshot -no-boot-anim &
./gradlew :androidApp:connectedDebugAndroidTest
```

`androidDeviceTest` と同様、これも `./gradlew build` には含まれない — アプリケーションモジュールの
AGP の `build` ライフサイクルは `androidTest` ソースセットに対して静的解析タスクの
`lintAnalyzeDebugAndroidTest` のみを実行し、`compileDebugAndroidTestKotlin` /
`assembleDebugAndroidTest` は実行しない。ただし `.github/workflows/ci.yml` の
`android-instrumented-test` ジョブがプッシュごとにこのスイートを実行している。

スイートはパーサ、フェッチャのリダイレクト/304/404/410/タイムアウト/ディスカバリ、
OPML、Dropbox ストレージ/認証、PKCE、OAuth ループバックサーバ、マージ（後勝ち・OR マージ・衝突ガード・
FK ガード）、スキーマ、ローカル設定、記事 upsert、URL リゾルバ、日時パーサ、Result、Repository 層
（Article/Feed/Tag/Settings）、CloudSession、NotificationCenter、IdGenerator、SyncRepository、
ViewModel 層（Home/Settings/Setup/NotificationCenter。`SettingsViewModel` の OPML インポート/エクスポート
経路——構築したドキュメント/読み込んだファイルがピックしたパスと往復すること、ローカライズ済みの
リクエスト内容が `FakeFileSelector` に渡ること、キャンセル、そしてドキュメントの構築/書き込み/取り込み
処理が EDT ではなく注入したディスパッチャ上で実行されることを含む）、Linux/macOS/Windows の
ファイルダイアログのバックエンド分岐（`FilePickerTest`：`defaultFilePickerBackend` の OS 判定、
`FileNameExtensionFilter` と一致する拡張子述語——ディレクトリを accept することを含む——、
上書き確認の解決、ダイアログの親ウインドウ選択）、フィード一覧のドラッグ&ドロップの書き直し
（`HomeCommonTest.kt` の `parseFeedListDragSourceKey` で純粋なキー解析ロジックを、`FeedListDragTest.kt`
で実際にレンダリングしたコンポーザブルに対して `performMouseInput`/`performKeyInput` を使う実際の
エンドツーエンドのジェスチャーをカバー——フィードを別のフィードの上にドラッグして永続化された順序を
検証、しきい値未満の移動でも選択は効くケース、フォルダーヘッダー/タグ行へのドロップ、ドラッグ中に
右クリックが来てもコンテキストメニューが開かずドラッグも中断されないこと、ゴーストオーバーレイの
表示/非表示のライフサイクル、Escape によるキャンセル、フォルダー同士の並べ替え、
ペインの水平方向の範囲を越えて押し出されたドラッグが行の高さと一致していても有効なドロップ先と
判定されずドロップも適用されないこと）、フィード一覧の行内リネーム編集
（`commonTest` の `InlineRenameValidationTest` で「空欄はエラーではないが確定もできない」という
共有バリデーション規則を、`HomeCommonTest.kt` で `toInlineEditTarget` を、`FeedListInlineRenameTest.kt`
で実際にレンダリングしたコンポーザブルに対するエンドツーエンドの挙動をカバー——F2 で編集を開始し
Enter で確定、Escape と「×」アイコンでのキャンセル、blur による確定、フォルダー名の重複が Enter を
ブロックし blur では静かに元へ戻ること、フォルダー名の空欄が単に確定不可であること、フィード名を
空欄で確定すると `custom_title` がリセットされフィード自身のタイトルが `placeholder` に出ること、
タグのリネームが色に触れないこと、タグの色ドットのポップオーバーがリネーム中かどうかに関わらず
即座に色を反映すること、Feed メニューの `RenameFeed` コマンドが現在の選択に対して編集を開始すること）、
名前とタイムスタンプを並べるメタ行（`ArticleRowMetadataTest`：フィードタイトルが長くても省略されるのはタイトル側だけで、記事カードのタイムスタンプは幅を奪われず行の右端に揃ったまま表示される。`ArticleMetaTextTest`：`articleMetaText` が著者とタイムスタンプを結合すること、および null または空白のみの著者名を除去し先頭に区切りが残らないこと）、記事リーダーのネイティブ WebView（`ArticleWebViewHtmlTest`：`extractLinks`、および 3 つの文書ビルダー `wrapArticleHtml`／`articleNoContentHtml`／`articlePlaceholderHtml`——すべての文書が同じ `<style>` ブロックを共有し、テーマの色・フォントスケールで塗られるためどれもデフォルトの白いページを一瞬出せないこと、を含む。`ArticleDetailLoadGuardTest`：`shouldLoadArticleHtml` のリロード判定——プレースホルダー／本文なし状態が実記事と WebView を共有するため、記事 ID ではなく描画された文書の文字列をキーにしていること。`ArticleDetailPaneTest`：リーダーが常にコンポーズされたままであること、選択状態が変わってもその計測済みバウンズが動かないこと——`known-issues.md` に記載されたウインドウ全体のフリッカーの回帰ガード——、および未選択時、または選択中の記事に URL が無い場合にツールバーが非表示ではなく無効化されること）、AppFont（Linux の UI フォント用 Pango フォント記述のパース）、カスタム URI スキーム登録（`UriSchemeRegistration` の OS 別ディスパッチとパッケージ版ランチャー判定、`LinuxUriSchemeRegistrar` の `.desktop` 生成——`%u` フィールドコードを含む——、`mimeapps.list` の非破壊マージ、冪等性）、FTS（FtsManager/FtsSearch、
`indexMissing` の増分投入・非破壊、`rebuildIndex` がテーブル存在を前提とすること、同期アップロードが
`VACUUM INTO` スナップショットで `articles_fts` を除外し `user_version` を保全することを含む）、
Linux の SNI トレイ（`TrayPixmapTest`＝ビッグエンディアン ARGB32 / RGBA エンコーダーとアルファ保全、
`TrayMenuModelTest`＝dbusmenu レイアウト、`TrayMenuRevisionTest`＝revision / `AboutToShow` /
イベントディスパッチ、`DBusSignatureTest`＝export した D-Bus シグネチャ）、
Windows のトレイメニュー（`WindowsTrayMenuTest`＝構築される Swing ウィジェット・ラベル・コールバック・
heavyweight ポップアップの強制。置き換え対象の AWT ウィジェットは HiDPI の Windows デスクトップで
自身のラベルを重ねて描画してしまう）、
クラウドデータの破損／非互換からの復旧（`SyncRepositoryTest.kt`／`SyncMergerTest.kt`：制約違反する
クラウドデータ——`feeds` の行集合が UNIQUE な `url` を重複させている、または NOT NULL 違反の NULL を
クラウド DB 自身の（より緩い）スキーマだけが許していた——を、破損ファイルや外部スキーマと同様に
`CloudDataIncompatibleException` として分類すること、`SyncMergerTest.mergeDoesNotClassifyABrokenLocalSchemaAsCloudDataIncompatible`
がその逆（ローカル側の破損は誤分類しない）を担保すること、`SyncRepositoryTest.postMergeIndexFailureIsNotClassifiedAsCloudDataIncompatible`
がマージ commit 後の `FtsManager.indexMissing()` の失敗——壊れたクラウドスキーマと同じ曖昧な SQLite
エラーコードを共有する——を誤って分類しないことを担保すること。`core/SqliteFileTest.kt`＝アップロード
側と対称なダウンロードバイト列の SQLite ヘッダ検証）、削除ではなく退避するようになったクラウドデータの
リセット（`core/CloudBackupPathTest.kt`＝決定的で UTC 整形された退避パス、`CloudStorage.rename` は
`DropboxStorageTest.kt`／`GoogleDriveStorageTest.kt`／`OneDriveStorageTest.kt` でプロバイダごとに
（退避先の衝突・退避元の不在ケースを含めて）検証、`SyncRepositoryTest.kt` の `resetCloudData*` 系が
リネームしてから作り直すフローとその削除フォールバックを検証）、クラウド転送のファイルストリーミング
（`CloudFileTransferTest.kt`：レスポンスボディが複数チャンクにまたがっても宛先ファイルへそのまま
書かれること、短いペイロードが既存の宛先ファイルに追記されず置き換わること、`FileUploadContent` が
ファイルをストリームし——Drive の `multipart/related` 封筒を可能にする prefix/suffix で包む場合も
含めて——正しい `contentLength` を報告すること。`ContentDigestTest.kt`：アップロードのスキップ判定に
使うチャンク単位 SHA-256——最終チャンクの変更も検出されること、ファイルが無い場合は誤一致ではなく
ダイジェスト無しを返すことを含む。`SqliteFileTest.kt`：パス版のヘッダ検証が、どのバッファよりも
大きなファイルでも先頭16バイトだけで判定すること）、変更がないときの転送スキップ
（`SyncRepositoryTest.kt`：双方とも変更が無い2回目の同期がペイロードを1バイトも転送せずメタデータ取得
1回だけで済むこと、そのスキップの後にローカルを変更したら確実にアップロードされること、リモートの変更は
従来どおりダウンロードしてマージすること、アップロード自身のレスポンスからリビジョンを記録するので自分の
書き込みを再ダウンロードしないこと、`sync_state` がアップロード用スナップショットから除外されており
ダイジェストが独りでに変化しないこと、`clearSyncFailureState()` が実行中の同期に取り消されないこと
——共有ミューテックスの保証を2本のテストで半分ずつ検証する。リビジョン／ダイジェストのマーカーを
書き戻すのは**成功した**同期であり、`lastSyncError` を書き戻すのは**失敗した**同期なので、1本では
両方を検証できないため、および圧縮アップロード／レガシーフォールバックの分岐——レガシーのみの
クラウドがマージされてから `.gz` へ移行すること（リビジョンガード付きの更新ではなく create-only で）、
レガシーファイル自体がバイト単位でそのまま生き残ること、一度移行したデバイスは（レガシーファイルが
その後壊れていても）二度とレガシーを読まないこと、移行中の壊れたレガシーファイルと不正な（gzip
ではない）`.gz` ペイロードのどちらも `CloudDataIncompatibleException` に分類されること、リセットが
`.gz` のみをリネーム・再作成しレガシーファイルには一切触れないこと）、自動同期の抑制ゲート
（`SyncRepositoryTest.kt`：`AUTOMATIC` トリガーの同期が `autoSyncSuspended` 中はスキップされること、
`MANUAL` は決してゲートされないこと、`scheduleSync()` も同様に抑制されること、成功した同期／リセット／
`clearSyncFailureState()` でゲートがクリアされること——`SchemaVersionException` は意図的にゲートを
一切起動しない）などを網羅する。
`SchemaTest` / `SyncMergerTest` / `SyncRepositoryTest` の失敗は DB スキーマ・
マージ SQL・同期オーケストレーションの退行を意味するので特に注意する。

既知の未カバー範囲: `SettingsViewModel.exportOpml`/`importOpml` は今やテスト用シーム（`FileSelector`、
テストでは `FakeFileSelector` でフェイク化）を持ちカバー済み——未カバーのまま残るのは、ネイティブ
ダイアログが実際に表示される部分（本物の `JFileChooser`/`FileDialog` にはディスプレイと人手が要る
ため、下記の手動確認に留まる）と、`FilePicker.desktop.kt` の `resolveDialogOwner()` を実ウインドウに
対して動かす部分（委譲先の純粋な `chooseDialogOwner` の選択ロジックのみテスト済み）である。
`OAuthConnectFlow.connect()` のブラウザー起動〜コールバック待受〜
コード交換部分（`BrowserOpener`/`OAuthLoopbackServer` の実I/Oに依存し、シームなしにはモック不可。
App Key 空チェックで即エラーになる分岐のみ `OAuthConnectFlowTest` でカバー済み）、
`DatabaseDriverFactory.create()` そのもの（`AppDirs.appDataDir()` を直接参照しておりテスト用の
ディレクトリ差し替えができない）。ただし本質的な部分である接続設定は `sqliteConnectionProperties()`
として切り出され、`SqliteConnectionPropertiesTest` が実ファイル DB に対して検証している
（`inMemoryDb()`/`fileDb()` もこれを使ってドライバを組み立てる）。フィード/フォルダーの並び替えジェスチャー（`ui/home/FeedListDragController.kt`/
`FeedListDragGestures.kt`）は、OS レベルの DnD ではなく自前実装の Compose ネイティブなドラッグになった
ことで、まさにこの部分をテスト可能にするために書き直された経緯があり、`FeedListDragTest.kt` が
`performMouseInput`/`performKeyInput` を使って実際にエンドツーエンドで検証する（ドラッグによる並べ替え、
しきい値判定、フォルダー/タグへのドロップ、ドラッグ中の右クリック、ゴーストのライフサイクル、
Escape によるキャンセル）。並び替えの計算ロジック自体（`ReorderUtil.reorderIds`）と、それを使う
`FeedRepository.moveFeed`/`FolderRepository.reorderFolders` の DB 反映は通常どおりテストする。
新規に追加されたものとして、`SqliteConnectionPropertiesTest`（本番の接続プロパティが実際にすべての
接続へ届くこと — 外部キーが効き `busy_timeout` が適用されること。JVM ドライバは文ごとに接続を開くため
一度きりの `PRAGMA` では届かない）、`FormatTimestampTest`（`formatTimestamp` の出力そのものを固定する。
他のタイムスタンプ検証は期待値を同関数から導出しているため書式変更を検出できない）、
`LazyNativePopupTest`（初回の右クリックまでネイティブなものを一切構築しないこと。`LocalNativeWindow`
が null になる Compose UI テストからは観測できない）、
`WindowGeometryTest`（ダイアログウィンドウのジオメトリ: オーナー中央寄せと画面境界クランプ、
自動フィットの算術 `fitWindowSize`/`sizeMatches`、`nextDialogFit` のドリフト補正状態機械 —
フィットが収まった*後*に Compose の裏側から適用されたサイズも補正されるという回帰ケースと、
ジオメトリを拒否するウィンドウマネージャーとの無限往復を防ぐ target ごとの補正上限、およびフィットが
確定するまでダイアログを不可視に保つ `presentable` フラグ —— 補正上限を使い切った場合には解放される
（ジオメトリを拒否するウィンドウマネージャー環境でダイアログが永久に出ないことがない）ことを含む）がある。
なおダイアログの自動サイズ調整は「どのサイズを要求し、再適用すべきか」という判断は
`WindowGeometryTest` で全てカバーされるが、その*適用*（実 `DialogWindow` への反映）はネイティブ peer を
持つ OS ウィンドウが必要なため後述の目視確認に委ねている。
Linux の SNI トレイでは `SniConnection`（接続・バス名取得・export・登録・再登録・close）が
実セッションバスと稼働中の `org.kde.StatusNotifierWatcher` を必要とするため CI では不可。同様に
`NewIcon`/`NewToolTip`/`LayoutUpdated` の実配送（*発火の判断* はカバー済み）、`NameOwnerChanged` からの
再登録経路、ホスト起点の `Activate`/`Event` が dbus-java のワーカースレッド経由で届くこと、
`LinuxNotifier.notify` の実デーモンへの配送、`LinuxTray` コンポーザブルの結線もテスト不可。
パネル上で実際に透過して見えるかは本質的に目視確認になる。

## 手動確認（UI）

`./gradlew :composeApp:run` で起動して 3 ペイン UI・テーマ切替・フィード追加・検索を目視確認する。
`FeedListDragTest.kt` がドラッグの機構自体（並べ替え・しきい値判定・フォルダー/タグへのドロップ・
右クリックガード・ゴーストのライフサイクル・Escape）をエンドツーエンドでカバーするようになったが、
実際の画素レンダリング（色・アニメーションの滑らかさ・実コンテンツ上でのゴーストの見え方）は本質的に
目視確認になるため、以下も併せて確認する:

英語ロケールを強制した状態でも一度起動し直し —
`JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" ./gradlew :composeApp:run`
（または OS の表示言語を英語に切り替える）— ラベルが全体的に英語で表示されること、件数1件・
2件以上それぞれで複数形の文言（Add Feed のプレビュー件数・成功/失敗件数、OPML インポート結果、
トレイの新着通知）が正しく出し分けられること、設定の「Website」リンクが `/en/` のページを
開くことを確認する。`StringsXmlParityTest` は翻訳漏れをリソースレベルで検出するが、文脈上の
不自然な言い回しはこの目視確認でしか検出できない。

- フォルダーをドラッグして順序を入れ替え、アプリを再起動しても順序が保持されること。
- フォルダー内のフィードをドラッグして順序を入れ替え、再起動後も順序が保持されること。
- 「フォルダーなし」グループ内でのフィード並び替え。
- フィードを別フォルダーの任意の位置（横線の位置）にドロップし、フォルダー移動と位置決めが同時に
  反映されること。
- 「フォルダーへ移動」ダイアログでの移動が、移動先グループの末尾に追加されること。
- フィードをタグ行にドラッグしたとき、フォルダーのドロップ先とは**異なる**色
  （`tertiaryContainer` / フォルダーは `secondaryContainer`）でハイライトされ、ホバー中はタグの
  色ドットが「+」アイコンに入れ替わること。ドロップするとフォルダーから移動させずにタグが付与され、
  既に付いているタグに再ドロップしても何も変わらないこと。
- タグのシェブロンで展開すると、そのタグが付いたフィードが直下に一覧表示されること
  （複数タグを持つフィードは展開中のタグごと＋所属フォルダーの分だけ重複表示されるが、これは仕様）。
  展開/折りたたみ状態が再起動後も保持され、タグの初期状態は折りたたみで、タグを削除しても展開状態が
  残らないこと。
- 展開したタグ配下のフィードを右クリック →「タグから外す」で、そのタグからのみ外れること
  （フォルダー所属や他のタグは変わらない）。そのフィードをタグ一覧からフォルダーや別のタグへ
  ドラッグして移動/付与できること。
- ドラッグ中は Compose 側で描画したチップ状のゴースト（アイコン＋タイトル）がポインターに追従して
  表示され、macOS/Windows/Linux で同じ見た目になること — **Linux では初めてゴーストが表示される**
  （X11 の AWT は Wayland のカーソル制限以前からそもそもゴースト自体に非対応だった。ドラッグが
  OS レベルの DnD に触れなくなったことでどちらの制限も無関係になった経緯は `docs/known-issues.md`
  の「Linux の Wayland/XWayland」の項を参照）。チップが明るい背景・暗い背景のどちらでも視認でき、
  どのプラットフォームでも OS の禁止（no-drop）カーソルが一切表示されず、通常の矢印カーソルの
  ままであること。
- チップは半透明になっており（`FeedListDragController.kt` の `DRAG_GHOST_ALPHA`）、下に隠れている
  行やハイライトが完全には隠れず透けて見えること。実際にドロップを受け付ける位置の上ではこれまでどおり
  ニュートラルな色味だが、空白・セクションヘッダー・（フォルダーをドラッグしている場合の）そのフォルダー
  自身の上など、ドロップできない位置に来ると `error`/`errorContainer` 系の色味に変わること — この
  切り替えがライト/ダーク両テーマで見た目にはっきり分かること、また有効な行に出入りした瞬間に
  遅延や古い色味の残留なく追従することを確認する。
- フィードやフォルダーをフィードペインの右端を越えて記事一覧側へドラッグすると、たまたま何らかの
  行の高さと一致していたとしても垂直位置に関わらずゴーストが禁止（`error`）色になり、その位置で
  離してもフィード移動・並べ替え・タグ付与が一切適用されないこと（`FeedListDragController.kt` の
  `isWithinHost`。`FeedListDragTest.kt` の `draggingOutsideTheHostHorizontallyNeverAppliesADrop` で
  回帰テスト済み — 行の当たり判定は垂直方向のみで行っているため、隣のペインを実際に除外しているのは
  この水平方向のチェックであり、たまたまハイライトが一致しなくなるからではない）。
- ドラッグ中に右クリックしてもコンテキストメニューは開かず、ドラッグも中断されないこと。そのまま
  ドロップすれば通常どおり完了すること。
- フィードペインの縦スクロールバーのつまみをドラッグすると通常どおりリストがスクロールし、
  ドラッグしきい値を超えて動かしてもフィード/フォルダーのドラッグが始まらないこと。
- ドラッグ中に Escape を押すと、並べ替えを適用せずに即座にキャンセルされゴーストが消えること。
  ドラッグ中でない場合の Escape の挙動は従来どおり（ドラッグ処理に飲み込まれない）こと。
- ドラッグ中に Alt-Tab などでウィンドウのフォーカスを失っても同様にキャンセルされること。
- **(Linux, X11 / Wayland)** ドラッグゴーストとドロップの挙動が、この 2 つのセッション種別間で
  区別できなくなっていること — 上記の並べ替え/フォルダーへのドロップ/タグへのドロップの確認を
  Plasma X11 セッションと Plasma Wayland（XWayland）セッションの両方で行い、同じ挙動になることを
  確認する（この変更によってどの OS レベル DnD の不具合からも無関係になった経緯は
  `docs/known-issues.md` の「Linux の Wayland/XWayland」の項を参照）。

並行フィード更新（並行取得＋直列書き込み、`FeedRepository.refreshAll`）の中核となる並行動作
（取得のオーバーラップとフィードごとの書き込み完全性）は `refreshAllFetchesFeedsConcurrentlyAndAppliesEveryWrite` が、
編集の巻き戻しが起きないことは `refreshAllDoesNotRevertConcurrentUnsubscribe` /
`refreshAllDoesNotRevertConcurrentReorder` が自動テストで担保している。目視が必要な UI / エンドツーエンドの
部分のみ、複数フィードを購読した状態で以下を目視確認する:

- 多数のフィードで「すべて更新」した際、記事が最後に一括ではなくフィード単位で逐次表示され、
  最終的なリスト順序が安定していること。
- フィードエラー / 301・308 の URL 変更 / 410 Gone の各通知が従来どおり発行され、未取得の
  ファビコンが更新後に補完されること。

変更がないときの転送スキップ（[sync-architecture.ja.md](sync-architecture.ja.md) の
「変更がないときの転送スキップ」参照）は `SyncRepositoryTest` がエンドツーエンドで押さえているが、
それは `CloudStorage` の Fake に対してのみである。各実プロバイダがメタデータ取得**および自身の書き込み
レスポンス**から実際に使えるリビジョンを返すことは MockEngine のテストでは証明できないので、接続済みの
各プロバイダ（Dropbox / Google Drive / OneDrive）ごとに1回、手動で確認する:

- 同期した直後に、双方とも何も変更せずもう一度同期する: DB を転送せずに完了すること。ログに
  `Sync: nothing changed locally or remotely; skipping transfer` が出て、プロバイダ側の履歴にも
  新しいリビジョンが増えないこと。
- そのアイドル同期を数回繰り返す（またはバックグラウンド間隔を数回またいで放置する）: 自発的に
  アップロードを再開しないこと。書き込みレスポンスからリビジョンを取れていないプロバイダがあると、
  毎サイクル黙って再ダウンロード・再アップロードするので、ここで判別できる。
- 記事1件の既読状態を切り替えてデバウンスを待つ: アップロードだけが1回発生し、ダウンロードは
  発生しないこと（クラウドのリビジョンはこのデバイスが書いたものと同じままのため）。
- **2台目のデバイス**で何かを変更してから1台目で同期する: ダウンロードが走り変更が反映されること
  （スキップが他デバイスの書き込みを隠してはならない）。
- アカウントを接続し直して（切断 → 接続）その後の初回同期が正常に動くこと（保存済みのリビジョン／
  ダイジェストは前の接続のものであるため）。

クラウドデータの破損からの復旧は実際のクラウド接続がエンドツーエンドで必要なため、接続済みの
各プロバイダ（Dropbox / Google Drive / OneDrive）ごとに1回、手動で確認する:

- クラウド上の `keryx.db.gz` を任意の非 gzip ファイルに置き換えて同期する: ベル通知に
  「同期データをリセット」（`ResetCloudData`）が出る。実行すると、プロバイダのアプリフォルダに
  `keryx-YYYYMMDD-HHMMSS.db.gz.bak` の退避ファイルが残り、隣に新しく作り直された `keryx.db.gz` ができる
  ——古いファイルが単に削除されるわけではないこと。
- `keryx.db.gz` をダウンロードして展開し、`feeds` テーブルの `UNIQUE(url)` 制約を緩めてから（SQLite には
  `ALTER TABLE` で制約を削除する手段がないため、制約なしでテーブルを作り直し既存行をコピーする）、
  既存の行と `url` が重複する `feeds` 行を追加する
  （この方法でしか到達できない状態——アプリ自身のスキーマ、およびアプリ自体は決してこの状態を作らない）。
  再圧縮して再アップロードして同期する: 同じ「同期データをリセット」通知が出ること——制約に違反する
  クラウドデータも、正真正銘の破損と同様に扱われることの確認。
- **レガシーフォールバック**（[sync-architecture.ja.md](sync-architecture.ja.md) の「圧縮アップロード /
  レガシーフォールバック」参照）: プロバイダ接続済みの状態で、アプリフォルダの `keryx.db.gz`（存在すれば）
  を手動削除し、代わりに非圧縮の `keryx.db` をアップロードする（この機能導入前に `createFresh` で作られた
  ものをダウンロードするか、`.gz` を手動で展開したもの）——これは「このデバイスが圧縮機能追加後まだ一度も
  同期していないクラウド」を模擬する。同期する: アプリはその非圧縮ファイルをダウンロード・マージした後、
  新しい `keryx.db.gz` を作成する（プロバイダの操作履歴では更新ではなく作成として見えるはず）こと——非圧縮
  の `keryx.db` はフォルダ内にそのまま一切変更されずに残ること。他に何も変えず再度同期する: `keryx.db.gz`
  へのメタデータ確認だけが発生し（ペイロードのダウンロード・アップロードは発生しない）、非圧縮ファイルが
  二度と読まれないことの確認。
- クラウド DB が壊れたままの状態で、自動同期を何度か発生させ（既読・スターを何度もトグルする、
  または背景更新の間隔を待つ）、データがリセットされるまでそれ以上ダウンロードが発生しないこと
  （通信も発生せず、通知も繰り返し／重複しない）を確認する——その後、自動同期が抑制されている間でも
  手動の「今すぐ同期」は実際に同期を試み（そして再び失敗する）ことを確認する。
- リセットに成功した後、自動同期が再開すること（既読・スターのトグルで実際に同期が走ること）を確認する。

記事リーダーのネイティブ WebView（`ui/home/ArticleDetailPane.kt`）はヘビーウェイトな AWT
サーフェスであり Compose UI テストでは一切ホストできないため、`ArticleDetailPaneTest` がカバーする
バウンズ／無効化状態のチェックを超えた実際の画面上の挙動は目視で確認する必要がある。リーダーが
常時マウントされている理由は `known-issues.ja.md` の「記事が未選択の状態から選択するとウインドウ
全体がフリッカーする」を参照:

- 未選択状態から記事をクリックし、また未選択（あるいは記事の無いフィード）に戻す操作を、本文の
  ある記事・無い記事を交ぜながら繰り返す — ウインドウのどの部分（フィード一覧・記事一覧・ウインドウ
  枠）もフリッカーしないこと。ライト・ダーク両テーマで確認する。
- 未選択時、プレースホルダーのテキストがペインのテーマ背景色の上に中央表示され、デフォルトの白い
  フラッシュが出ないこと。その上のツールバー（スター／未読に戻す／URL コピー／ブラウザで開く）は
  表示されるが無効化されていること。URL のある記事を選択すると 4 つとも有効になり、URL が空の
  記事を選択した場合はコピー／ブラウザで開くの 2 つも非表示にはならず、無効化されたまま表示され
  続けること。ツールバーの位置と高さはこれらどの状態でも変わらないこと。
- 記事を開いた状態でライト／ダークテーマ（および文字サイズ設定）を切り替えると、リーダーが
  即座に新しいテーマ／スケールで再描画されること（スクロールが先頭に戻るのは想定どおり）。
- （Windows）起動時にリーダーが正しいペイン位置に描画されること（空白／位置ズレした矩形が
  残らないこと）、ウインドウのどこをクリックしてもアプリがフリーズしないこと —— `known-issues.md`
  に記載した WebView2 の `dataDirectory` 未設定による Access Denied バグの回帰確認。
  `WRYWEBVIEW_LOG=1` を付けて起動し、コンソールに `WebViewException` が出ないことを確認する。

ネイティブなコンテキストメニュー（`nativeContextMenu`。Windows/Linux では実際の `JPopupMenu`、macOS
では `java.awt.PopupMenu` によるもので、Compose 描画のポップアップではない）は Compose UI テストで
検証できないため、以下を目視確認する。ウィジェットはコンポジション時ではなく**初回の右クリック時**に
構築されるようになった（`LazyNativePopup`）ので、クリック自身の呼び出しスタックの中でネイティブピアの
生成が問題なく動くことを確かめられるのはこの目視確認だけである:

- フィード行・フォルダーヘッダー・記事行を右クリックすると、正しいアクションを持つネイティブメニューが
  表示されること。
- フィード行の「タグ」サブメニューで現在付与されているタグすべてにチェックが付いていること、
  「フォルダーへ移動」サブメニューでフィードの現在のフォルダーにチェックが付いていること。どちらも
  切り替えると即座にチェックが更新されること。
- フィード行（フォルダー配下・展開したタグ配下のどちらも）を右クリックすると「フィード URL を
  コピー」「サイト URL をコピー」「サイトを開く」が、両側に区切り線を挟んだひとまとまりとして
  （アプリケーションメニューバーの「フィード」メニューと同じ並び順で。後述）表示され、それぞれ
  意図どおりに動作すること。サイト URL が未取得のフィードでは後者2つが非表示ではなくグレーアウト
  （無効化）され、リフレッシュでサイト URL が取得された時点でメニューを開き直さなくても有効化される
  こと。
- フィード行メニューの区切り線は、各プラットフォームで実際のネイティブな区切り線として描画される
  こと（macOS はダッシュラベルの項目、Windows/Linux は `JPopupMenu.Separator`）— クリック可能な
  項目としては表示されないこと。タグの切り替えなどでメニューが再同期されてもメニュー自体が再構築
  されず、区切り線の位置が保たれること。
- URL の無い記事行を右クリックしても「URL をコピー」「ブラウザーで開く」は非表示にならず、グレー
  アウト（無効化）表示されること。URL のある記事ではどちらも有効に表示されること。
- 記事リーダーの WebView が表示された状態でこれらのメニューを開くと、メニューが WebView の背後ではなく
  前面に表示されること。
- （Linux）アプリ内テーマ（ライト↔ダーク）を再起動なしで切り替えた際、メニューバーと開いている
  ダイアログのボタン列は即座に再スタイルされ、切替後に新しく開いたコンテキストメニューも新テーマを
  反映すること。

（Android）`nativeContextMenu` の Android `actual` は長押しで開く Material 3 `DropdownMenu` で、
ジェスチャ方針そのものは `NativeMenuAndroidGestureTest.kt` の計装テスト（長押しで選択せずに開くこと、
短いタップやスクロール相当の移動では開かないこと。上記の `androidApp/src/androidTest/` を参照）で
カバーされている。それらのテストでは検証できない、実アプリ UI に対する end-to-end の確認は実機または
エミュレータで目視確認する:

- 記事行を長押しするとメニューが表示され、その記事が既読にならず、（Single/Dual レイアウトで）
  ペインも遷移しないこと——同じ行への単純なタップとは異なること。
- 記事行を押し下げたまま指を離さずゆっくり縦にドラッグすると、通常のドラッグとしてリストが
  スクロールし、メニューは開かず、行も選択されないこと。
- フィード行・フォルダーヘッダー・タグ行を長押しするとメニューが正しいアクションとともに表示され、
  長押しそのものの副作用として行の選択が変わらないこと。

（Android、スマートフォン幅）検索 —— `KeryxSearchBarAndroidTest.kt` が2つの `actual` の
セマンティクス・テキスト入力・フォントスケールの挙動を単独で検証している（上記の
`androidApp/src/androidTest/` を参照）。ナビゲーションの一連の流れは実機またはエミュレータで
目視確認する:

- 起動時、保存されたペインが `HomePane.ArticleDetail` の場合は記事一覧（深さ2）に着地し、最後に
  読んでいた記事（深さ3）には戻らないこと —— `initialPaneFor` のクランプの確認。保存されたペインが
  `HomePane.FeedList` ならそのまま（深さ1）復元される。記事一覧になるのは、未保存のセッションでの
  フォールバック先だからにすぎない。
- 記事一覧自身の検索アイコン、またはフィード一覧の折りたたみ検索バーをタップすると、検索画面が
  キーボードを上げた状態で開き、入力欄にフォーカスがあること。
- 3文字以上入力すると、同じ画面の入力欄の下に結果が表示されること —— ペイン移動は不要。
- キーボードを表示したまま、結果リストを最後の項目までスクロールでき、キーボードに隠れないこと。
- 結果を開いてから戻ると、クエリと結果が残ったまま検索画面に戻ること（キーボードは自動で
  再表示されない）。
- さらに戻ると、フィード一覧の折りたたみ検索バーにクエリが表示され、再度タップすると同じ結果に
  戻れること。
- セッションの途中でタブレット幅の横向き（`PaneLayout.Dual`）に回転しても、読んでいた内容から
  弾き出されないこと。また、その状態の記事一覧での最初の戻る操作が黙って無視されないこと
  （`canNavigateBack` の修正）。
- `PaneLayout.Triple` に達するほど広いタブレット幅の横向きでは、レイアウトがデスクトップと完全に
  一致すること —— 検索欄はフィード一覧のサイドバーに戻り、記事一覧に検索バーは表示されない。

**表示スケール**。上記の確認はすべて、**100% 以外の表示スケール**でも実施すること。特に Windows では
200%、続いて 150% で行う。AWT のメニューバックエンドは、まさにこの設定でメニューを誤った位置に開き
ラベルを重ねて描画していた一方、100% では完璧に見えていた。上記の項目のどれ一つとしてこれを検出でき
なかった（`known-issues.md` 参照）。Windows の 設定 → システム → ディスプレイ → 拡大縮小 で変更し、
AWT に読み直させるためアプリを再起動すること。各メニューについて**カーソル位置に開くこと**と、
ラベルが一切重ならないことを確認する。後述のトレイメニューについても同様に確認する。

トレイメニューも Compose の手が届かないネイティブウィジェットである（Windows は `WindowsTray` が
駆動する `JPopupMenu`、macOS は `MacTray` 経由の `java.awt.PopupMenu`、Linux SNI は
`com.canonical.dbusmenu`）ため、手動で確認する:

- トレイアイコンを右クリックするとメニューが**カーソル位置**に、タスクバーの上に収まって開くこと。
  画面端に張り付いたり見切れたりしないこと。通知領域に直接表示されているアイコンと、オーバーフロー
  （隠れているアイコン）の中のアイコンの両方で確認する —— 画面右端からの距離が大きく異なるためである。
  これはトレイ自身のデバイスピクセル座標に対する回帰確認である（`known-issues.md` 参照）。スケールの
  異なるモニタが複数ある場合は、それぞれで確認すること。
- トレイアイコンを右クリックするとメニューが開き、表示／非表示の項目がウィンドウの現在の可視状態を
  反映していること。両方の項目が意図どおりに動作すること。
- 開いているトレイメニューの外側をクリックすると閉じること。ウィンドウが残らず、タスクバーや Alt+Tab
  にも項目が現れないこと。
- （Windows）トレイアイコンのダブルクリックと、新着記事通知のクリックが、いずれも従来どおり
  `onTrayAction` に届くこと。すなわち `shouldHideOnTrayAction` に従ってウィンドウを表示／非表示に
  すること（Compose の `Tray()` を `WindowsTray` に置き換える前と同一の挙動）。
- （Windows）新着記事通知が従来どおり表示され、アイコンの未読バッジも更新されること。

OPML のファイルダイアログは実際の OS ウインドウ（macOS は `NSSavePanel`、Windows は
`GetOpenFileName`、Linux は `JFileChooser` の `JDialog`）であり Compose UI テストでは駆動できないため、
手動で確認する。この分岐が存在する理由そのものである Linux は、**パッケージ版**
（`createDistributable` → `bin/Keryx`）で、Plasma **X11**・Plasma **Wayland**（XWayland）・GNOME の
各セッションで確認すること:

- **先に記事を選択**して記事リーダーの WebKitGTK WebView をプロセス内で生かした状態にしてから
  （旧 GTK ファイルダイアログピアが落ちた条件——`known-issues.md` 参照）Settings ▸ データ管理 ▸
  OPML をインポート。GTK ではなく Swing のチューザが開き、アプリはクラッシュせず、選んだファイルが
  取り込まれる。エクスポートも同様。`<appDataDir>/logs/keryx.0.log` に何も出ず、ランチャーの隣に
  `hs_err_pid*.log` が生成されない。
- チューザが記事リーダーの WebView の**手前**に描画され、背後に隠れないこと。
- インポート: 「OPML ファイル」フィルタ選択時は `.opml`/`.xml` のみが一覧に出る。「すべてのファイル」
  に切り替えると全件表示される。フォルダーをダブルクリックして中に入れる（ディレクトリを弾く
  フィルタだと操作不能になる）。
- エクスポート: 初期名は `keryx.opml`。既存のファイルを選ぶと日本語の 置き換える／キャンセル 確認が
  出て、キャンセルするとチューザに戻る（エクスポート自体は中断しない）。置き換えると上書きされる。
  新規のファイル名なら確認なしで保存される。
- チューザの文言（「開く」「キャンセル」「ファイル名」…）がパッケージ版で**日本語**になっていること
  ——これが `composeApp/build.gradle.kts` に `jdk.localedata` モジュールを追加した理由であり、英語で
  表示される場合はまずそのモジュール一覧を確認する。
- インポート/エクスポートを 3 つの経路すべてから起動し、チューザの親ウインドウが正しいことを確認する:
  (1) Settings のボタン——チューザが Settings の**手前**に出る、(2) ウインドウ内メニューバーの
  File ▸、(3) 内蔵バーを隠した状態の KDE Global Menu の File ▸。
- 再起動せずにアプリ内テーマをライト⇔ダークで切り替えてからチューザを開き直すと、新しい FlatLaf
  テーマで描画される。
- 大きな OPML のインポート中もアプリが応答し続ける——インポートボタンのスピナーが回り続け、
  フィード/記事ペインもスクロールできる（OPML のドキュメント構築/書き込み/取り込み処理を EDT から
  外した効果の回帰確認）。

macOS と Windows は引き続き `java.awt.FileDialog` を使うが、親ウインドウと表示元のスレッドが
どちらも変わった（従来は親なしダイアログを EDT 外から表示していた）ため、こちらも再確認する:

- パネル/ダイアログが**開いた元のウインドウの中央**に出る（Settings のボタンから開いた場合は
  Settings の上）こと、かつ表示中も背後の Compose ウインドウが**再描画され続ける**こと
  （バックグラウンドスレッドではなく EDT 自身のセカンダリイベントループから表示していることの確認）。
- macOS: インポートで拡張子フィルタが引き続き効くこと、既存ファイルへの保存でシステム純正の
  置換確認が引き続き出ること。
- Windows: インポート/エクスポートが引き続き完了すること（インポートで `FilenameFilter` が効かない
  のは既存の挙動であり、今回の変更による退行ではない）。

ダイアログのサイズ自動調整（`DialogWindow` の OS ウィンドウ挙動）は自動テストできないため、以下を
目視確認する:

- 設定 / About / フィード追加 / フィード名変更 を**それぞれ 10 回連続で開いて**、毎回内容全体が
  **最初に見えるフレームの時点で**正しいサイズかつ最終位置で表示されること。内容が一度どこかに出てから
  別の位置へ飛ばないこと（典型的には、240pt のプレースホルダーと実サイズ高さの差の半分だけ上へ飛ぶ）
  —— フィットが確定するまでウィンドウは不可視のまま保持されるようになっている。
  240pt 相当の低いウィンドウ・~80x28 の極小ウィンドウ・末尾のタブが切れる狭いウィンドウが
  一度も出ないこと。ここで守っているレースは約 7/10 の確率で再現していたので、反復することが要点。
- 同じ 10 回で、ダイアログが**最初から中央に**出ること。一瞬でも画面左上に出てから中央へ飛ばないこと。
  それは AWT が新規 `Window` に与える既定位置そのもので、フィット後のサイズが位置より先に
  ネイティブウィンドウへ届くと見える。
- 同じ 10 回を**ダークモード**でも行い、明るい矩形や明るいトーンの帯が 1 フレームも出ないこと ——
  カードの縁まわりにも、下部のネイティブボタン行のまわりにも出ないこと（ネイティブウィンドウ背景と
  全面塗りの両方がダイアログ自身のコンテナ色で塗られるようになったので、カードとトーンが違う箇所は
  無いはず）。
- 「フィット完了まで不可視」のゲートが**体感できてはいけない**: クリックしてから何も表示されない
  空白時間なく、従来どおり即座にダイアログが出ること。目に見えて遅い場合は、500ms の
  `DIALOG_PRESENT_FALLBACK_MS` 安全網で表示された可能性を疑い、後述の `Dialog stayed at …`
  警告をログで確認する。
- **オートフォーカスが維持されていること**: テキスト入力系ダイアログ（フィード / フォルダー / タグの
  名前変更、フィード追加）で、表示された瞬間にカーソルがテキストフィールドにあり、直後の打鍵が
  そこに入ること。可視化を遅らせた変更で最も壊れやすい箇所なので、毎回確認する。
- 名前変更ダイアログで連続入力して supporting text を出入りさせても、ガタつかず、ネイティブボタン行が
  打鍵ごとにちらついたり再レイアウトされたりしないこと（ボタンの*ラベル*が変わったときだけ
  `revalidate()` するようになり、確定ボタンの有効/無効の切り替えでは呼ばれない）。
- 設定でタブを何度も往復すること（最も高い「一般」と最も低い「通知」の間も含めて）。
  **ウインドウが一切リサイズされない**こと —— どのタブでも高さが同じで、どの辺も動かず、タブの
  ラベルやカードがウインドウ内でずれたフレームが出てから戻る、という見え方もしないこと。
  タブ内容領域を固定高にしているのはまさにこれを起こさないためで、ここでリサイズが復活していたら
  タブごとの高さ追従が再導入されたことを意味する（`known-issues.md` 参照。リサイズを正しく描かせ
  ようとした対処が恒久的な悪化を招いた経緯も記録してある）。
- 設定の最も高いタブ: 文字サイズ「中」（1.0）で「一般」タブの内容が**スクロールなしで収まる**こと。
  スクロールする場合は `KERYX_TAB_DIALOG_CONTENT_HEIGHT` の引き上げが必要（見た目の問題であり
  正しさの不具合ではない）。「大」「特大」では高いタブがスクロールするのは想定どおり。
- 実行中にテーマをライト↔ダーク切り替えてから各ダイアログを開き直すと、Compose 側のカードだけでなく
  **ネイティブ**ウィンドウ背景も追従していること（ネイティブボタン行の背後・周囲や余剰領域のトーンで
  確認できる）。
- ダイアログを中央から動かした後に内容を変える（フィード追加の URL → 候補一覧）と、アラート系は
  内容変更にあわせて再センタリングされ（従来どおり）、設定ダイアログは動かした位置に留まること。
  この拡大は**表示済み**ダイアログのリサイズなので、リサイズが見えること自体は仕様どおり。
  してはいけないのは、新たに露出した領域やネイティブボタン行まわりの帯が整定中に明るく光ることで、
  特にダークモードで確認する。
- スケールファクタの異なるマルチモニタ環境（例: macOS Retina + 外部 1x ディスプレイ）で、
  メインウィンドウを各画面に置いた状態で両ダイアログを開き、また開いたダイアログを画面境界を
  またいで移動させる。どちらでも正しいサイズであること — フィットは測定値を*ダイアログ自身*の
  density で変換しており、オーナーウィンドウの density ではない。
- 設定を開いた直後にトレイへ最小化（またはメインウィンドウを完全に隠す）してから復帰しても、
  ダイアログが正しいサイズのままであること。フィットはダイアログのフレームクロック（何も描画されない
  間はフレームを配らない）に依存しなくなっている。
- 上記を通して、サイズの振動や CPU の張り付きが無く、`<appDataDir>/logs/keryx.0.log`
  （macOS: `~/Library/Application Support/Keryx/logs/`）に
  `Dialog stayed at … after … attempts to fit …` の警告が出ていないこと。
- （Linux、Plasma の **X11** と **Wayland** 両方）上記を再確認し、既存2件のダイアログ不具合が
  回帰していないこと: モードレスダイアログ（設定 / About）が正しいサイズで開き、その後 1 秒ほどかけて
  幅がじりじり縮んでいくことが無いこと。
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
- 通知が閉じられた理由に関わらず（クリック・破棄・自動消滅のいずれでも）その通知の id が破棄される
  こと — 複数の通知を発生させ、一部はクリックせずに破棄・自動消滅させたうえで、その後のクリックでの
  前面化動作に支障が出ないことを確認する（PendingNotificationIds に件数を可視化する手段がないため、
  間接的な確認になる）。
- AppIndicator 拡張なしの GNOME で AWT 経路に静かにフォールバックすること（クラッシュ・スタックトレース
  無し）。`DBUS_SESSION_BUS_ADDRESS` 無しの起動でハングも例外も起きないこと。
- Plasma Wayland セッションでも同じ挙動であること。
- アイコン上でホイールを回しても `journalctl --user -f` にエラーが出ないこと。
- メニューから終了した後、`busctl --user list | grep StatusNotifierItem` に残骸が無いこと。
- `GetGroupProperties`/`AboutToShowGroup`/`EventGroup` が正しく動くこと（`ai` / `a(isvu)` 入力の
  デシリアライズは dbus-java 任せで、`DBusSignatureTest` は宣言シグネチャしか保証しない）。おかしい場合は
  引数を `IntArray` / `Array<DBusMenuEventEntry>` に変える。

`keryx://` のスキーム登録はユーザーのホーム配下に実ファイルを書き、デスクトップ環境に依存するため、
エンドツーエンドの確認は Linux 実機でしかできない（ユニットテストが担保するのはファイル内容とマージであって、
OS 側のルーティングではない）。パッケージ版をインストールし
（`./gradlew :composeApp:packageDeb` → `sudo dpkg -i`）、一度起動してスキーム登録を
走らせたうえで、以下を確認する:

- `xdg-mime query default x-scheme-handler/keryx` が `keryx-url-handler.desktop` を返すこと。
- Keryx 起動中に `xdg-open 'keryx://oauth2/callback?code=test&state=test'` でウィンドウが前面に来ること。
- Dropbox / OneDrive 連携がどちらもブラウザー往復で完走すること。
- `./gradlew :composeApp:run` では `$XDG_DATA_HOME/applications/keryx-url-handler.desktop`
  （既定 `~/.local/share/applications/keryx-url-handler.desktop`）が**作られない**こと。
- `$XDG_CONFIG_HOME/mimeapps.list`（既定 `~/.config/mimeapps.list`）の無関係なエントリが登録後も
  そのまま残っていること。
