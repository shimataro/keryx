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

1 件だけ意図的に `@Ignore` しているテストがある。`ArticleReuseCrashRepro` は、意図的に修正して
いない Compose 側の不具合を再現するもので、有効にすると毎回失敗するため。詳細と、Compose 更新後に
修正されたかを確認する手順は [known-issues.ja.md](known-issues.ja.md) を参照。

```bash
./gradlew :composeApp:desktopTest
```

スイートはパーサ、フェッチャのリダイレクト/304/404/410/タイムアウト/ディスカバリ、
OPML、Dropbox ストレージ/認証、PKCE、OAuth ループバックサーバ、マージ（後勝ち・OR マージ・衝突ガード・
FK ガード）、スキーマ、ローカル設定、記事 upsert、URL リゾルバ、日時パーサ、Result、Repository 層
（Article/Feed/Tag/Settings）、CloudSession、NotificationCenter、IdGenerator、SyncRepository、
ViewModel 層（Home/Settings/Setup/NotificationCenter）、フィード一覧のドラッグ&ドロップの書き直し
（`HomeCommonTest.kt` の `parseFeedListDragSourceKey` で純粋なキー解析ロジックを、`FeedListDragTest.kt`
で実際にレンダリングしたコンポーザブルに対して `performMouseInput`/`performKeyInput` を使う実際の
エンドツーエンドのジェスチャーをカバー——フィードを別のフィードの上にドラッグして永続化された順序を
検証、しきい値未満の移動でも選択は効くケース、フォルダーヘッダー/タグ行へのドロップ、ドラッグ中に
右クリックが来てもコンテキストメニューが開かずドラッグも中断されないこと、ゴーストオーバーレイの
表示/非表示のライフサイクル、Escape によるキャンセル、フォルダー同士の並べ替え、
ペインの水平方向の範囲を越えて押し出されたドラッグが行の高さと一致していても有効なドロップ先と
判定されずドロップも適用されないこと）、
名前とタイムスタンプを並べるメタ行（`ArticleRowMetadataTest`：フィードタイトルが長くても省略されるのはタイトル側だけで、記事カードのタイムスタンプは幅を奪われず行の右端に揃ったまま表示される。`ArticleMetaTextTest`：`articleMetaText` が著者とタイムスタンプを結合すること、および null または空白のみの著者名を除去し先頭に区切りが残らないこと）、記事リーダーのネイティブ WebView（`ArticleWebViewHtmlTest`：`extractLinks`、および 3 つの文書ビルダー `wrapArticleHtml`／`articleNoContentHtml`／`articlePlaceholderHtml`——すべての文書が同じ `<style>` ブロックを共有し、テーマの色・フォントスケールで塗られるためどれもデフォルトの白いページを一瞬出せないこと、を含む。`ArticleDetailLoadGuardTest`：`shouldLoadArticleHtml` のリロード判定——プレースホルダー／本文なし状態が実記事と WebView を共有するため、記事 ID ではなく描画された文書の文字列をキーにしていること。`ArticleDetailPaneTest`：リーダーが常にコンポーズされたままであること、選択状態が変わってもその計測済みバウンズが動かないこと——`known-issues.md` に記載されたウインドウ全体のフリッカーの回帰ガード——、および未選択時にツールバーが非表示ではなく無効化されること）、AppFont（Linux の UI フォント用 Pango フォント記述のパース）、カスタム URI スキーム登録（`UriSchemeRegistration` の OS 別ディスパッチとパッケージ版ランチャー判定、`LinuxUriSchemeRegistrar` の `.desktop` 生成——`%u` フィールドコードを含む——、`mimeapps.list` の非破壊マージ、冪等性）、FTS（FtsManager/FtsSearch、
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
が null になる Compose UI テストからは観測できない）がある。
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
  記事を選択した場合はこのペインの改修前と同様にコピー／ブラウザで開くの 2 つが非表示のままである
  こと。ツールバーの位置と高さはこれらどの状態でも変わらないこと。
- 記事を開いた状態でライト／ダークテーマ（および文字サイズ設定）を切り替えると、リーダーが
  即座に新しいテーマ／スケールで再描画されること（スクロールが先頭に戻るのは想定どおり）。

ネイティブなコンテキストメニュー（`nativeContextMenu`。Linux では実際の `JPopupMenu`、macOS/Windows
では `java.awt.PopupMenu` によるもので、Compose 描画のポップアップではない）は Compose UI テストで
検証できないため、以下を目視確認する。ウィジェットはコンポジション時ではなく**初回の右クリック時**に
構築されるようになった（`LazyNativePopup`）ので、クリック自身の呼び出しスタックの中でネイティブピアの
生成が問題なく動くことを確かめられるのはこの目視確認だけである:

- フィード行・フォルダーヘッダー・記事行を右クリックすると、正しいアクションを持つネイティブメニューが
  表示されること。
- フィード行の「タグ」サブメニューで現在付与されているタグすべてにチェックが付いていること、
  「フォルダーへ移動」サブメニューでフィードの現在のフォルダーにチェックが付いていること。どちらも
  切り替えると即座にチェックが更新されること。
- 記事リーダーの WebView が表示された状態でこれらのメニューを開くと、メニューが WebView の背後ではなく
  前面に表示されること。
- （Linux）アプリ内テーマ（ライト↔ダーク）を再起動なしで切り替えた際、メニューバーと開いている
  ダイアログのボタン列は即座に再スタイルされ、切替後に新しく開いたコンテキストメニューも新テーマを
  反映すること。

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
