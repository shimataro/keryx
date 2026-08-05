# 同期アーキテクチャ

[English](sync-architecture.md)

対象: クラウドストレージ同期（Dropbox / Google Drive / OneDrive）。実装は `domain/SyncRepository.kt`, `domain/MergeSql.kt`, `platform/DatabaseMerger`, `platform/DatabaseSnapshot`。

## 設計方針

- 同期ファイルは `keryx.db`（SQLite）をアップロードする。ただしライブ DB をそのまま読むのではなく、
  `VACUUM INTO` で一貫スナップショットを作り、その**コピー側で `articles_fts` を DROP**してアップロードする
  （ライブの索引は決して DROP しない → 同期中の検索が `no such table` にならない。[db-schema.ja.md](db-schema.ja.md) の `articles_fts` 節）。
- 競合解決はアップロード前にアプリ側でマージ（ATTACH DATABASE）する。
- FTS5 インデックスはクラウドに含めない（コピー側で DROP）。マージで入った新記事はマージ後に**増分投入**する。
- **クラウドファイルは旧版（前身実装）と非互換**（ユーザー決定）。

## クラウド上のファイル構成

```bash
/keryx.db   ← 同期用 SQLite（articles_fts なし）
```

競合防止はアップロード時のリビジョンチェックで行う — Dropbox: `rev`、サーバー側の compare-and-set（不一致時 409）。Google Drive: ファイルの `version`、クライアント側で比較後に書き込み（同期のリトライで担保）。lock ファイルは使わない。

## 同期フロー（`SyncRepository.sync()`）

1. クラウドに `keryx.db` が無ければローカルをそのままアップロード（初回）。
2. クラウドから `keryx.db` をダウンロード（一時ファイルへ）。
3. **`DatabaseMerger.merge()` でマージ**（後述）。直後に `ftsManager.indexMissing()` でマージが入れた
   新記事を**増分投入**（ライブ索引は wipe しない）してから、`driver.notifyListeners(...)`（マージが触れる
   全テーブル）を呼ぶ。マージは `DatabaseMerger` 専用の生 JDBC コネクションで書き込み SQLDelight のクエリ通知を
   発火しないため、`watchAll` フロー（と索引済みの再検索）を再発火させて同期内容を再起動なしで UI に反映する。
4. `sync_state.cloud_file_rev` を記録。
5. `DatabaseSnapshot.exportForUpload()` で `VACUUM INTO` スナップショットを作り、その**コピー側で `articles_fts` を
   DROP**（ライブ DB は不変）。そのバイト列を `rev` を指定してアップロード。
   - `rev` 不一致（409 → `SyncConflictException`）なら再ダウンロードからリトライ（最大 3 回）。
6. 成功したら `last_synced_at` を記録。

デバウンス: 既読・スター等の変更後は `SyncScheduler.scheduleSync()` が最後の操作から一定秒後に
まとめて同期する。

## マージ（`DatabaseMerger` + `MergeSql`）

ダウンロードした DB を `cloud` としてアタッチし、テーブルごとにタイムスタンプ比較でマージする。

> [!IMPORTANT]
> SQLDelight の JVM `JdbcSqliteDriver` は
> ファイル DB に対してステートメントごとに新しいコネクションを開く。そのため `ATTACH` を driver 越しに
> 実行すると後続のマージ文からアタッチが見えず `no such table: cloud.*` になる。マージは
> `platform/DatabaseMerger` の**専用 JDBC コネクション 1 本**で attach → バージョン確認 → マージ
> （トランザクション）→ detach を完結させる。

マージ SQL（`MergeSql`）の要点:

- **列を明示指定**する（`SELECT *` はスキーマ差異で列数不一致を起こしうるため避ける）。
- feeds / tags / folders / global_settings: タイムスタンプ後勝ち（論理削除含む）。ただし feeds 文の
  `ON CONFLICT` は **ユーザー編集フィールド（`folder_id` / `sort_order` / `custom_title` / `deleted_at`）を
  一切扱わない**（下記の専用文に委譲）。内容が新しいという理由でこれらが上書きされないようにするため。
  ON CONFLICT が扱うのは内容フィールド（url/title/description/etag 等 + `updated_at`）のみ。
  feeds を **`id` で照合**するため、feed id は購読時に `url` から **UUIDv5** で決定的に生成し
  （`IdGenerator.feedId`）、同じフィードが全デバイスで同一 id になることが前提。ランダム id だと両
  デバイスが独立購読した同一フィードが別 id になり、URL 衝突ガードにスキップされて収束しない（feed が
  収束しないと記事 id も `feed_id` 由来で食い違い記事も収束しない）。詳細は
  [db-schema.ja.md](db-schema.ja.md) の `feeds` 節。
- articles: 既読（`read_at`）・スター（`starred_at`）は後勝ち、本文は OR マージ、`search_text` を再計算。削除は `deleted_at` / `deleted_updated_at` の後勝ち（既読・スターと同じフィールド別）で、キャッシュ削除の論理削除がクラウドから復活せず伝播する。削除より新しいスターがあれば記事を復活（`deleted_at` → NULL）させる。`upsert`（フィード更新）は `deleted_at` に書き込まないため、更新が削除済み記事を復活させることはない。
  記事を **`id` で照合**するため、記事 ID は `(feed_id, guid)` から **UUIDv5** で決定的に生成し
  （`IdGenerator.articleId`）、同じ記事が全デバイスで同一 ID になることが前提。ランダム ID だと両
  デバイスが独立取得した同一記事が別 ID になり、下記の guid 衝突ガードにスキップされて既読が伝播しない
  （その不具合の修正）。詳細は [db-schema.ja.md](db-schema.ja.md) の `articles` 節。
- feed_tags: 後勝ち。参照先 feed / tag が main に存在する場合のみ取り込む（FK 保護）。
- **feeds のユーザー編集フィールドは専用文でフィールド専用タイムスタンプを使い独立に後勝ちマージする**
  （記事の `read_at` / `starred_at` と同じ設計。行全体の `updated_at`＝内容リフレッシュで更新、とは切り離す）:
  `mergeFeedFolderId`（`folder_id` / `folder_updated_at`）、`mergeFeedSortOrder`（`sort_order` /
  `sort_order_updated_at`）、`mergeFeedCustomTitle`（`custom_title` / `custom_title_updated_at`）、
  `mergeFeedDeletedAt`（`deleted_at` / `deleted_updated_at`）。いずれも NULL 認識の比較
  （`c.<ts> IS NOT NULL AND (main が NULL または cloud が厳密に新しい)`）で、リフレッシュに妨げられない伝播・
  収束後の無駄書き込み無し・ローカルが新しければ維持、を満たす。`folder_id` は列に値を持たず専用文が解決
  （main に folder があれば維持、無ければ同名解決、それも無ければ NULL）するため feeds INSERT にも含めない。
  `sort_order` / `custom_title` / `deleted_at` は初期値伝播のため feeds INSERT に残す（`ON CONFLICT` のみ除外）。
- UNIQUE / FK 違反でトランザクション全体が失敗しないよう、`NOT EXISTS` / `EXISTS` ガードで
  衝突する行をスキップする（同一 URL・別 ID など）。
- `MergeSql.all` の適用順は
  `updateFoldersByName, insertFolders, feeds, mergeFeedFolderId, mergeFeedSortOrder, mergeFeedCustomTitle, mergeFeedDeletedAt, updateTagsByName, insertTags, articles, feedTags, globalSettings`。
  `folders` を `feeds` より前にマージし（`feeds.folder_id` の FK 参照のため）、4 つの `mergeFeed*` は
  `feeds`（および `insertFolders`）の後に置く（main 側に feed 行と folder 行が揃ってから解決するため）。

## スキーマバージョン

`PRAGMA user_version` で管理。マージ時に `cloud.user_version` を確認し、クラウドがローカルより新しければ
`SchemaVersionException` を投げてユーザーにアプリ更新を促す（マージ中止）。
現在の `user_version` は 2（`1.sqm` が `articles.deleted_at` / `deleted_updated_at` を追加する）。

> [!NOTE]
> **クラウドが古い場合のローカル方向マイグレーション**: `DatabaseMerger.merge` は
> マージ本体の前にダウンロードしたクラウド DB の `user_version` を確認し、ローカルより古ければ一時ファイルに
> 対して `KeryxDatabase.Schema.migrate` でローカルのスキーマまで引き上げてからマージする。これにより、
> 新しい列を参照するマージ文が古いクラウドに対して `no such column` で失敗しない。バージョン 2 では、
> この引き上げ分岐（`migrateCloudIfOlder`）がバージョン 1 のクラウド DB に対して発火し、ダウンロードした
> コピーへ `1.sqm` を適用してから記事マージが `deleted_at` を参照できるようにする。

## FTS5 の扱い

**ライブ DB の `articles_fts` は決して DROP しない。** アップロードから索引を除外するのは、`VACUUM INTO` の
スナップショット（`DatabaseSnapshot.exportForUpload`）の**コピー側で DROP** することで行う。`VACUUM INTO` は
`user_version` を保全するので、受信側 `DatabaseMerger` のスキーマバージョン判定は正しく機能する。

索引の維持は 2 段構え:

- **hot path（フィード更新・同期マージ後）**: `FtsManager.indexMissing()` で未索引の新記事だけを増分投入
  （O(新着行)、索引を wipe しない）。全再構築（`'rebuild'`）は毎回だと O(索引テキスト総量) で重くスケールせず、
  実行中の検索を弾き得るため hot path では使わない。本文が更新された既存記事の索引は次の rebuild まで古いまま
  （許容。記事はなお旧トークンでヒットするので検索が 0 件に退行しない）。
- **healing 用の全再構築（`rebuildIndex()` = `'rebuild'`）**:
  `StartupTasks.kt` の日次アイドル pass（`maybeRebuildFtsIndex`、`local_settings.lastFtsRebuiltAt` の 24h ゲート +
  `ActivityCenter` アイドル）でのみ実行。増分投入以降に本文が更新されて古くなった既存行を作り直す。
  `'rebuild'` は単一文で原子的（読み手は再構築前後どちらかを見るだけ）＋ `busy_timeout` で待つため、
  実行中の検索も 0 件にならない。

2 つの索引ライタは**相互排他**とする: `FtsManager` は `indexMissing()` と `rebuildIndex()` を内部の
mutex で直列化する（このため両者は `suspend`）。日次 pass のアイドル判定は `ActivityCenter` のロックなしの
参照なので、その直後に始まったフィード更新は検知されず rebuild と重なり得る。重なりは常に無駄な作業
（rebuild は増分投入を包含する）であり、rebuild が `busy_timeout` を超えて書き込みロックを保持する規模では、
どの呼び出し元も catch しない生の `SQLiteException` になる。検索は**意図的に直列化しない**: 従来どおり
`'rebuild'` が単一のアトミックな文であることと `busy_timeout` の待機に依存する。

起動時に `FtsManager.ensureIndexed()`（初回作成 + 未索引行の増分投入）を呼ぶのは従来どおりだが、`main.kt` の
`runBlocking` から呼ぶ（索引不在のままウィンドウを開かせないため）。ライタ mutex はコルーチンベースで、この
時点では直前にディスパッチされた `.opml` インポートが短時間だけ保持し得るのみなので、メインスレッドで待って
もデッドロックしない。

## クラウド認証（OAuth PKCE + オフラインアクセス）

OAuth 2.0 authorization-code-with-PKCE のオーケストレーション（PKCE 生成・認可 URL 構築・ブラウザー起動・
state 検証・コード交換）はプロバイダー共通の `OAuthConnectFlow`（desktop）に集約する。プロバイダー差は
**リダイレクトの受け取り方（`OAuthRedirectTransport`）とエンドポイント/スコープ（`CloudAuthManager` 実装）**
だけで、`DropboxAuthManager` / `GoogleDriveAuthManager` / `OneDriveAuthManager` が `CloudAuthManager` を実装する。いずれも
オフラインアクセス（Dropbox: `token_access_type=offline`、Google: `access_type=offline` + `prompt=consent`、OneDrive: `offline_access` スコープ）を
指定し**リフレッシュトークンを取得・保存**する。

リダイレクト受信方式はプロバイダーごとに選ぶ（設計方針 `.claude/rules/cloud-oauth-transport.md` 参照——両方使える場合はカスタム URI スキームを優先）:

- **Dropbox / OneDrive — カスタム URI スキーム**（`CustomUriRedirectTransport`）: リダイレクト URI は
  `keryx://oauth2/callback`。両プロバイダーで共有し `state` で識別する。認可 URL は既定ブラウザーで開き、OS が URL を実行中インスタンスへ配送する
  （`main.kt` が `parseOAuthUri` して共有 `MutableSharedFlow<OAuthCallbackParams>` に流す）。OneDrive は Microsoft Identity platform（`common` テナント）と Microsoft Graph を使い、Google と違い**クライアントシークレット不要の PKCE パブリッククライアント**。同期 DB はアプリ専用フォルダー（`/me/drive/special/approot`、スコープ `Files.ReadWrite.AppFolder`）に保存する。Microsoft には標準のトークン失効エンドポイントが無いため `OneDriveAuthManager.revoke` は no-op で、連携解除はローカルトークンの破棄のみ。楽観的排他は DriveItem の `eTag` を `rev` として使い `If-Match` で送る（412→衝突）。`create` は `@microsoft.graph.conflictBehavior=fail`（409→衝突）。
- **Google Drive — ループバック**（`LoopbackRedirectTransport`）: Google の「デスクトップアプリ」クライアントは
  任意のカスタムスキームを許可せず、`http://127.0.0.1:<ポート>` のループバックのみを受け付ける。一時 HTTP
  サーバー（`com.sun.net.httpserver`。`jdk.httpserver` モジュール同梱済み）を立ててリダイレクトを受け、
  受信後に停止する。OS 側のスキーム登録は不要（`keryx://` の登録は Dropbox / OneDrive 用のまま）。**Dropbox とは異なり
  クライアントシークレット（`GOOGLE_DRIVE_CLIENT_SECRET`）もトークン交換・リフレッシュ両方に送る**——PKCE を
  使っていても、「デスクトップアプリ」タイプの Google OAuth クライアントは iOS/Android と違い完全な public
  client 扱いされず、省略すると Google のトークンエンドポイントが `invalid_request: client_secret is missing`
  で拒否する（詳細は [build.ja.md](build.ja.md)）。

OS へのスキーム登録方法はプラットフォームごとに異なる。macOS はパッケージング時に Info.plist
（`CFBundleURLTypes`）で宣言する。Windows / Linux は起動時に `registerCustomUriScheme()` が登録し、
Windows は `HKEY_CURRENT_USER\Software\Classes\keryx`（ユーザー単位のハイブなので管理者権限は不要）を、Linux は `LinuxUriSchemeRegistrar` がユーザーレベルの
`.desktop`（`$XDG_DATA_HOME/applications/keryx-url-handler.desktop`、既定
`~/.local/share/applications/keryx-url-handler.desktop`）と `$XDG_CONFIG_HOME/mimeapps.list`
（既定 `~/.config/mimeapps.list`）の関連付けを書き出す。Linux の `.desktop` は `Exec` 行が `%u` で終わっている必要がある——これが無いと
Desktop Entry 仕様上 URI がプロセスに渡らず、ブラウザーはスキームを解決できずに「不明なプロトコル」
エラーを出す。いずれも OS が URL をコマンドライン引数としてアプリを起動し、`main.kt` が
single-instance 経由で実行中インスタンスへ転送する。

> [!NOTE]
> **カスタム URI プロバイダーは全デスクトップ OS 共通**: `./gradlew :composeApp:run` では
> Dropbox / OneDrive 連携を完了できない。macOS では `keryx://` を LaunchServices がパッケージ版
> `Keryx.app` にルーティングするため、`gradlew run` のインスタンスにはリダイレクトが届かない。
> Windows / Linux では、起動時の登録がパッケージ版ランチャーからの起動でない限り意図的に何もしない
> （`packagedLauncherPath()`）——JDK の `java` バイナリを `keryx://` のハンドラーとして登録すると
> Gradle 実行終了後も残ってしまうため。連携を行う/確認する場合は `createDistributable` でビルドした
> アプリを起動する（詳細は [setup.ja.md](setup.ja.md)）。
> Google Drive はループバック受信のため、この制約はなく `gradlew run` でも連携を完了できる。

### トークン保存先

**プロバイダーごとに別インスタンス**の `TokenStorage` を DI（`platformModule`）で構築する（1 インスタンスを
複数プロバイダーで共有しない。`SecurityCliTokenStorage` は結果をインスタンスにキャッシュするため共有すると壊れる）。
Keychain のアカウント名とフォールバックファイル名は `CloudStorageType.id` から導出する（Dropbox は `"dropbox"` で
従来のハードコード値と一致するため既存トークンの移行は不要。Google Drive は `"google_drive"`）。`KEYCHAIN_SERVICE` は
両者共通。

- Windows/Linux: OS セキュアストレージ（java-keyring — Credential Manager / Secret Service, `KeyringTokenStorage`）。
- macOS: Apple 署名の `/usr/bin/security` CLI に委譲（`SecurityCliTokenStorage`）。java-keyring は共有 JVM
  から Keychain 書き込みに失敗するため、macOS のみ `security` 経由にしている。
- いずれも失敗時はデータディレクトリの `.{CloudStorageType.id}_tokens.json`（0600。Dropbox は `.dropbox_tokens.json`）へ
  フォールバック。DI が `isMacOs` で両者を切り替える。
- macOS は書き込み後に **read-back 検証**（login keychain を明示指定して読み戻し）を行い、永続化を確認できない
  場合は file フォールバックへ回す。**書き込みの永続性は起動セッション依存**: パッケージ版（GUI ログイン
  セッション）では login keychain に永続化されるが、`gradlew run`（launchd 直下の Gradle daemon 配下の
  切り離しセッション）では `security add` が成功を返しても永続化しないため file に保存される。**読み取りは
  どちらのセッションからでも可能**（一度パッケージ版で連携すれば以降 `gradlew run` でも接続を引き継げる）。

### 今後の課題（未対応）

- **file → Keychain 移行 heal**: `.dropbox_tokens.json` にトークンがあり Keychain が空の場合、
  `SecurityCliTokenStorage.load()` で file の値を Keychain へ書き戻し、**read-back 検証に成功した時のみ**
  file を削除する（検証失敗時は file を保持しデータ損失を防ぐ）。Phase 2.5 の検証ロジックを再利用できる。

## 同期対象の記事範囲

起動時のキャッシュ削除は保持期間超過の記事を物理削除せず**論理削除**（`deleted_at` / `deleted_updated_at`
を刻む）する。トゥームストーンはアップロードされマージ（`deleted_updated_at` の後勝ち）で伝播するため、
削除を取りこぼしたデバイスも記事を再追加せず収束する。行の物理回収はまだ行わない（古いトゥームストーンの
物理GCは将来対応）ので、それまで論理削除済み記事もアップロードのスナップショットに含まれ続ける。
