# DB スキーマ

[English](db-schema.md)

対象: ローカル SQLite（SQLDelight 管理）。`.sq` ファイルは
`composeApp/src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/` にある。

実ファイルの実体パスはプラットフォームによって異なる: デスクトップの `JdbcSqliteDriver` は
`AppDirs.appDataDir()/keryx.db` を直接開くが、Android の `AndroidSqliteDriver` は
`Context.getDatabasePath("keryx.db")` — `<dataDir>/databases/keryx.db` に置く。これは
`AppDirs.appDataDir()`（`Context.filesDir`、すなわち `<dataDir>/files`）とは別のディレクトリである。
`platform/DatabaseFile.kt` の `databaseFilePath()` がプラットフォームごとの実値を解決する唯一の
`expect` 関数であり、`DatabaseMerger`/`DatabaseSnapshot`（どちらもドライバ経由ではなくパスで動作する）
は自前で `AppDirs.appDataDir()` とファイル名を組み立てるのではなく、常にこれを経由しなければならない。

## 設計方針

- 全テーブルは SQLDelight（`.sq`）で管理。`articles_fts` のみ生 SQL（`FtsManager`）で別途作成する。
- 論理削除は `deleted_at`（NULL = 生存）。同期タイムスタンプは `updated_at`。
- 真偽値・タイムスタンプは **INTEGER（`Long`）**。真偽値は 0/1、時刻は Unix ミリ秒。
- スキーマバージョンは `PRAGMA user_version`（現在 2）。`DatabaseDriverFactory` が create/migrate を駆動。
  バージョン 2 は `1.sqm` で `articles.deleted_at` / `deleted_updated_at` を追加する（SQLDelight は最大の
  マイグレーションファイル + 1 でバージョンを導出）。スキーマを変える場合は `.sqm` ファイル
  （`<移行元バージョン>.sqm`）を追加すればバージョンは自動で上がる。あわせて
  `domain/MergeSchema.EXPECTED_SCHEMAS`（`DatabaseMerger.validateSchema` が参照する期待スキーマ）を
  新バージョンに追随させること。

## テーブル一覧

`feeds` / `articles` / `tags` / `feed_tags` / `folders` / `global_settings` / `sync_state` / `articles_fts`（仮想）。

### feeds

`id`(PK, UUID), `url`(UNIQUE), `site_url`, `title`, `description`, `favicon_url`, `etag`,
`last_modified`, `error_count`, `last_error`, `custom_title`, `folder_id`(FK→folders, NULL可),
`deleted_at`, `updated_at`, `created_at`, `sort_order`, `folder_updated_at`(NULL可),
`sort_order_updated_at`(NULL可), `custom_title_updated_at`(NULL可), `deleted_updated_at`(NULL可)。

- `url` は購読の一意キーだが主キーではない（URL が変わっても同一フィードとして扱うため）。
- `id` は新規購読時に（リダイレクト解決済みの）`url` から **UUIDv5** で決定的に生成する
  （`IdGenerator.feedId`）。同じフィードは全デバイスで同一 id になるため、同期マージ（feeds を `id` で
  照合）が独立購読されたフィードを収束でき、記事 id（`feed_id` から導出）も一致する。以前は購読時の
  ランダム UUIDv4 で、両デバイスが同じ URL を独立購読すると id が食い違い収束しなかった。v5 の
  バージョンニブルにより旧 v4 id とは決して衝突しない。既存 url の再購読は既存 id を再利用するため
  既存行は据え置き（新規のみ決定的）。
- `deleted_at` で論理削除。再購読時に NULL へ戻す。
- `error_count` が定数 `FEED_TIMEOUT_RETRY_COUNT` に達したらエラー扱い。
- `last_error` の用途は 2 つ: 直前の取得失敗の生のエラーテキストと、410 Gone のフィードに対する固定の内部マーカー `FEED_ERROR_REASON_GONE`（`"gone"`、`feeds.markGone` が書き込む）。410 は恒久的でリトライ対象ではないため意図的に `error_count` を増やさないので、このマーカーがフィード消失の唯一の目印であり、フィード一覧の目印（専用のローカライズ済みツールチップ付き。カラムの値そのものはユーザーに表示しない）の判定に使われる。次回の取得成功時に `resetErrorCount` がクリアする。
- `folder_id` はフィードが属するフォルダー（1フィード = 最大1フォルダー）。タグ（`feed_tags`、多対多）とは
  独立した分類軸。`feeds.upsert`（購読・リフレッシュ用）はこの列に一切触れない設計にしており、
  フォルダー割り当ての変更は `feeds.updateFolder` / `updateFolderAndSortOrder` 経由でのみ行う（購読・
  リフレッシュで上書きされない）。
- `folder_updated_at` / `sort_order_updated_at` / `custom_title_updated_at` / `deleted_updated_at` は
  **ユーザー編集フィールド（フォルダー割り当て・並び順・手動リネーム・購読状態）それぞれの後勝ち専用
  タイムスタンプ**（記事の `read_at` / `starred_at` と同じ考え方）。行全体の `updated_at`（内容リフレッシュで
  更新される）とは切り離すことで、他デバイスのリフレッシュがこれらの手動操作を同期マージで打ち消す不具合を
  防ぐ。各フィールドを変える操作クエリのみが対応する列を更新し、`upsert`（リフレッシュ）は一切触らない。
  NULL = そのイベント無し（「時刻 0 に発生」とは区別）。同期マージでは各フィールドをこれらの列で独立に
  後勝ち解決する（[sync-architecture.ja.md](sync-architecture.ja.md)）。購読解除は `softDelete`、再購読は
  `subscribeFeed` 内の `stampResubscribed` が `deleted_updated_at` をスタンプする。

### articles

`id`(PK), `feed_id`(FK→feeds), `guid`, `url`, `title`, `summary`, `content`, `author`,
`published_at`, `thumbnail_url`, `is_read`, `read_at`, `is_starred`, `starred_at`, `cached_at`,
`search_text`, `updated_at`, `created_at`, `deleted_at`, `deleted_updated_at`。`UNIQUE(feed_id, guid)`。
インデックス: `feed_id` / `is_read` / `is_starred` / `published_at DESC`。

- `id` は `(feed_id, guid)` から **UUIDv5** で決定的に生成する（`IdGenerator.articleId`）。同じ記事は
  全デバイスで同一 ID になるため、同期マージ（記事を `id` で照合する）が既読・スターを後勝ちで
  伝播できる。**理由**: 以前は記事 ID がフェッチ時のランダム UUIDv4 で、両デバイスが同じ記事を独立に
  取得すると別 ID になり、マージの guid 衝突ガードにスキップされて既読が伝播しない不具合があった。
  v5 のバージョンニブルにより旧 v4 ID とは決して衝突しない。ID 生成方式の変更は新規行のみに効き、
  既存行は `upsert` の `ON CONFLICT(feed_id, guid)` が旧 ID を保持する（＝既存はそのまま）。
- 既読・スターの競合解決は `read_at` / `starred_at` で後勝ち。
- `content` は `summary` より優先して表示。両方 NULL なら外部ブラウザーで開く。
- `search_text = COALESCE(content, summary, '')`。挿入・更新時に計算する。
- `deleted_at`（NULL = 生存）で論理削除する。`deleted_at` を書き込むのは**キャッシュ削除のみ**
  （`softDeleteExpired`）で、スター付き記事は削除しない。`deleted_updated_at` は削除/復活イベントの
  フィールド別後勝ちタイムスタンプ（`read_at` / `starred_at`、および `feeds.deleted_updated_at` と同様）で、
  コンテンツ更新・既読・スター変更が同期マージで削除を上書きしないよう `updated_at` とは分離する。
  マージでは `deleted_updated_at` の後勝ちで削除が伝播するが、削除より新しいスターがあれば記事を
  **復活**（`deleted_at` → NULL）させる（キャッシュ削除は非スター記事しか消さないため）。これにより、
  削除が次回同期でクラウドから復活せず、他デバイスへ伝播する。ここでは行を物理削除しない（古い
  トゥームストーンの物理GCは将来対応）。全 UI/一覧/検索クエリは `deleted_at IS NULL` で除外し、
  `upsert`（フィード更新）は `deleted_at` に触れないため、更新が削除済み記事を復活させることはない。

### tags / feed_tags

`tags`: `id`(PK), `name`(UNIQUE), `color`, `sort_order`, `deleted_at`, `updated_at`, `created_at`。
`feed_tags`: `feed_id` + `tag_id`（複合 PK）, `deleted_at`, `updated_at`。タグの付け外しは `deleted_at` で表現。

### folders

`id`(PK), `name`(UNIQUE), `sort_order`, `deleted_at`, `updated_at`, `created_at`。

- タグとは独立した分類軸。1フィード = 最大1フォルダー（`feeds.folder_id`、多対多ではない）ため、
  `feed_tags` のような中間テーブルは持たない。
- フォルダー削除時は `FolderRepository.deleteFolder` が同一トランザクションで、紐づく `feeds.folder_id`
  を NULL に戻してからフォルダー自体を論理削除する。ただしこれはローカルDBにしか効かないため、
  同期後に他デバイスの `feeds` が論理削除済み/存在しないフォルダーを指す状態になり得る。UI 側
  （`groupFeedsByFolder`）はこの状態を「フォルダーなし」として防御的に扱う。

### global_settings（KVS, 同期対象）

`key`(PK), `value`(JSON 文字列), `updated_at`。既知キー:

| キー | 型 | デフォルト |
| --- | --- | --- |
| `read_timeout_seconds` | INTEGER | 30 |
| `cache_retention_days` | INTEGER \| "null" | 30（"null" = 無期限） |
| `article_list_default_unread_only` | boolean | false |

### sync_state（KVS, 非同期）

`key`(PK), `value`。既知キー: `last_synced_at`（Unix ミリ秒）, `cloud_file_rev`（**このデバイスがマージ済みの**
クラウドファイルのリビジョン。Dropbox は `rev`、Google Drive は file resource の `version`、OneDrive は
DriveItem の `eTag`）, `last_uploaded_snapshot_digest`（このデバイスが最後にアップロードしたスナップショットの
SHA-256 を hex 化したもの）。

後者2つは「やることが無い同期が何も転送しない」ために存在する。`cloud_file_rev` が変わっていなければ
ダウンロードを、スナップショットのダイジェストが変わっていなければアップロードをスキップする
（[sync-architecture.ja.md](sync-architecture.ja.md) の「変更がないときの転送スキップ」参照）。

このテーブルは**アップロード用スナップショットから除外される**（`DatabaseSnapshot.exportForUpload` が
`articles_fts` と一緒に DROP する）。デバイスローカルな管理情報であり受信側が読むことは元々なく
（`MergeSql` にも `DatabaseMerger` の期待スキーマにも登場しない）、除外することでスナップショットが
同期対象データのみの関数になる — さもないと `last_synced_at` が同期成功のたびにバイト列を変え、
上記のダイジェスト比較が成立しなくなる。

> [!NOTE]
> このテーブルへの読み書きが未実装だった問題を修正し、現在の実装では実際に記録する。

### articles_fts（FTS5 仮想テーブル、SQLDelight 管理外）

```sql
CREATE VIRTUAL TABLE articles_fts USING fts5(
  title, search_text,
  content='articles', content_rowid='rowid',
  tokenize='trigram'
);
INSERT INTO articles_fts(articles_fts) VALUES('rebuild');
```

外部コンテンツ方式でインデックスのみ保持し、本文は `articles.search_text` を参照する。**ライブ DB の
`articles_fts` は決して DROP しない**（アップロードからの除外は `VACUUM INTO` スナップショットのコピー側で
DROP して行う。[sync-architecture.ja.md](sync-architecture.ja.md) の「FTS5 の扱い」）。フィード更新・
同期マージの後は `FtsManager.indexMissing()` で**未索引の新記事だけを増分投入**する（全 `'rebuild'` は毎回だと
重くスケールしないため使わない）。全再構築は日次アイドル pass（`local_settings.lastFtsRebuiltAt`
の 24h ゲート）でのみ行い、増分投入以降に本文が更新されて古くなった既存行の作り直しを担う。
**起動時に `FtsManager.ensureIndexed()` を呼び、テーブルが無ければ作成し、索引に未登録の記事があれば増分投入する**。

`tokenize='trigram'` は SQLite ≥3.34 を必要とするが、AOSP 自身の SQLite ビルドはこれを提供しない
（どの API レベルでも FTS5 自体を含んでいない）— Android の `DatabaseDriverFactory` actual はバンドル
SQLite を使う。理由と撤退条件は `.claude/rules/android-sqlite-bundling.md` を参照。これは Android の
実機で実際に `articles_fts` テーブルを作成・投入し、`MATCH` クエリを実行した DB ファイルを取り出して
検証済み。

**trigram トークナイザは 3 文字未満のクエリ文字列からトークンを一つも生成しない** — 1〜2 文字での
`MATCH` はエラーにならず無音で 0 件を返す。検索の最小文字数（`core/Constants.kt` の
`SEARCH_MIN_TERM_LENGTH`、現在 2）は trigram の下限（`TRIGRAM_MIN_TERM_LENGTH`、3）より小さいため、
2 文字の語は `articles_fts` を使わず `articles` 本体（title と `search_text`）への
`LIKE '%語%'` 走査（バインドパラメータ化・エスケープ済み。文字列連結はしない）にフォールバックする。
`FtsSearch`（`data/local/`）参照。2 文字語のみのクエリは FTS のランクを持たないため、結果は
`published_at DESC` にフォールバックし、`SEARCH_FALLBACK_RESULT_LIMIT`（200）で上限を切る。
3 文字以上の語と 2 文字の語が混在するクエリは、従来どおり `articles_fts MATCH`（ランク順）を実行し、
短い語はマッチした行に対する追加の `LIKE` フィルタとして AND で効かせる。

## local_settings.json（keryx.db 外・非同期）

保存先: アプリデータディレクトリ直下（`AppDirs.appDataDir()`。macOS: `~/Library/Application Support/Keryx`）。
セットアップ完了判定 = ファイル存在。

| キー | 型 | デフォルト |
| --- | --- | --- |
| `themeMode` | string | "system" |
| `fontSizeScale` | number | 1.0 |
| `refreshIntervalMinutes` | int | 30 |
| `startMinimized` | boolean | false |
| `cloudStorageType` | string\|null（`"dropbox"` / `"google_drive"` / `"onedrive"` / null=ローカルのみ。`CloudStorageType.id`） | null |
| `notificationEnabled` | boolean | true |
| `lastCacheCleanupAt` | int\|null | null |
| `lastFtsRebuiltAt` | int\|null | null（FTS 全再構築の 24h ゲート。日次 heal で更新） |
| `updateCheckIntervalHours` | int | 24 |
| `lastUpdateCheckAt` | int\|null | null |
| `windowWidth` / `windowHeight` | number\|null | null |
| `feedListPaneWidth` / `articleListPaneWidth` | number | 260 / 360 |
| `collapsedFolderIds` | string[] | `[]`（フォルダの既定は*展開*なので、畳まれている方だけを記録する） |
| `expandedTagIds` | string[] | `[]`（タグは逆に既定が*折り畳み*。このリストが無かった頃と同じだけサイドバーが短いままになるようにするため） |
| `lastFilter` | string\|null | null |
| `lastArticleId` | string\|null | null |
| `recentArticleScrollPositions` | `{articleId, scrollOffset}[]` | `[]` |
| `lastFocusedPane` | string\|null | null |
| `lastUnreadOnly` | boolean\|null | null |
| `lastUnreadOnlyStarred` | boolean\|null | null（スター付きフィルタ専用。`lastUnreadOnly` とは独立） |
| `lastUnreadOnlySearch` | boolean\|null | null（検索フィルタについて同上） |
| `lastNewestFirst` | boolean\|null | null |
| `appMenuBarVisible` | boolean\|null | null（Linux KDE Global Menu: null=自動（`RegisterWindow` が成功するまで表示し、その後非表示）。true/false は Ctrl+M またはエクスポートされた「メニューバーを表示」チェックボックスによる明示的な上書き。`com.canonical.AppMenu.Registrar` が存在しない環境では効果なし） |

## キャッシュ削除

`cache_retention_days` に基づき、起動時に前回削除から 24 時間以上経過していればバックグラウンドで実行。
スター付き記事とフィードごとの最新 10 件は保持期間に関わらず削除しない。`null`（無期限）なら実行しない。
削除は行の物理削除ではなく**論理削除**（`articles.deleted_at` / `deleted_updated_at` を刻む）で、削除が
クラウドから復活せず同期マージで伝播する。古いトゥームストーンの物理回収は将来対応。

## favicon・サムネイル

画像バイナリは DB に持たず URL のみ保持（同期対象外）。favicon は表示するが、記事サムネイル（`thumbnail_url`）の表示は α では省略している。
