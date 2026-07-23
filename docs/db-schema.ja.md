# DB スキーマ

[English](db-schema.md)

対象: ローカル SQLite（SQLDelight 管理）。`.sq` ファイルは
`composeApp/src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/` にある。

## 設計方針

- 全テーブルは SQLDelight（`.sq`）で管理。`articles_fts` のみ生 SQL（`FtsManager`）で別途作成する。
- 論理削除は `deleted_at`（NULL = 生存）。同期タイムスタンプは `updated_at`。
- 真偽値・タイムスタンプは **INTEGER（`Long`）**。真偽値は 0/1、時刻は Unix ミリ秒。
- スキーマバージョンは `PRAGMA user_version`（現在 1）。`DatabaseDriverFactory` が create/migrate を駆動。
  現時点ではマイグレーション履歴を持たず、基底 `.sq` が単一の現行スキーマ（バージョン 1）。
  将来スキーマを変える場合は `.sqm` ファイル（`<移行元バージョン>.sqm`）を追加してバージョンを上げる。

> **旧版（前身実装）との互換性は考慮しない**（ユーザーの決定）。スキーマは合理的な最良形とする。

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
`search_text`, `updated_at`, `created_at`。`UNIQUE(feed_id, guid)`。
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
- 論理削除しない。フィードが論理削除されても `feed_id` を保持したまま残す（スター記事の参照維持）。

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

`key`(PK), `value`。既知キー: `last_synced_at`（Unix ミリ秒）, `cloud_file_rev`（クラウド上ファイルのリビジョン。
Dropbox は `rev`、Google Drive は file resource の `version`）。

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
の 24h ゲート）でのみ行い、増分で古くなった既存行の作り直しとキャッシュ削除で残った索引エントリの一掃を担う。
**起動時に `FtsManager.ensureIndexed()` を呼び、テーブルが無ければ作成し、索引に未登録の記事があれば増分投入する**。

## local_settings.json（keryx.db 外・非同期）

保存先: アプリデータディレクトリ直下（`AppDirs.appDataDir()`。macOS: `~/Library/Application Support/Keryx`）。
セットアップ完了判定 = ファイル存在。

| キー | 型 | デフォルト |
| --- | --- | --- |
| `themeMode` | string | "system" |
| `fontSizeScale` | number | 1.0 |
| `refreshIntervalMinutes` | int | 30 |
| `startMinimized` | boolean | false |
| `cloudStorageType` | string\|null（`"dropbox"` / `"google_drive"` / null=ローカルのみ。`CloudStorageType.id`） | null |
| `notificationEnabled` | boolean | true |
| `lastCacheCleanupAt` | int\|null | null |
| `lastFtsRebuiltAt` | int\|null | null（FTS 全再構築の 24h ゲート。日次 heal で更新） |
| `windowWidth` / `windowHeight` | number\|null | null |
| `feedListPaneWidth` / `articleListPaneWidth` | number | 260 / 360 |

## キャッシュ削除

`cache_retention_days` に基づき、起動時に前回削除から 24 時間以上経過していればバックグラウンドで実行。
スター付き記事とフィードごとの最新 10 件は保持期間に関わらず削除しない。`null`（無期限）なら実行しない。

## favicon・サムネイル

画像バイナリは DB に持たず URL のみ保持（同期対象外）。favicon は表示するが、記事サムネイル（`thumbnail_url`）の表示は α では省略している。
