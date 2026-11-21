# DB スキーマ

[English](db-schema.md)

対象: ローカル SQLite（SQLDelight 管理）。`.sq` ファイルは
`shared/src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/` にある。

実ファイルの実体パスはプラットフォームによって異なる: デスクトップの `JdbcSqliteDriver` は
`AppDirs.appDataDir()/keryx.db` を直接開くが、Android の `AndroidSqliteDriver` は
`Context.getDatabasePath("keryx.db")` — `<dataDir>/databases/keryx.db` に置く。これは
`AppDirs.appDataDir()`（`Context.filesDir`、すなわち `<dataDir>/files`）とは別のディレクトリである。
`platform/DatabaseFile.kt` の `databaseFilePath()` がプラットフォームごとの実値を解決する唯一の
`expect` 関数であり、`DatabaseMerger`/`DatabaseSnapshot`（どちらもドライバ経由ではなくパスで動作する）
は自前で `AppDirs.appDataDir()` とファイル名を組み立てるのではなく、常にこれを経由しなければならない。

## 設計方針

- 全テーブルは SQLDelight（`.sq`）で管理。`articles_fts` のみ生 SQL（`FtsManager`）で別途作成する。
- **同期対象のテーブル**は `feeds`、`articles`、`tags`、`feed_tags`、`folders`、`global_settings` で、後述の端末ローカルの
  列（`articles.search_text`。フィードの取得状態は専用のテーブルにある）は除く。同期対象の行へのローカルの変更は
  すべてトリガーが `sync_outbox` に記録し、差分として送る——[sync-architecture.ja.md](sync-architecture.ja.md) 参照。
- 論理削除は `deleted_at`（NULL = 生存）。同期タイムスタンプは `updated_at`。
- 同じ行で、削除と、それを打ち消す操作に同じ時刻がつくことはない。`tags` / `folders` の `updated_at` と、`feeds` の
  `deleted_updated_at` は、論理削除と、作り直し・有効化（`upsertByName`）や再購読（`stampResubscribed`）のときに
  `MAX(今の時刻, 前の値 + 1)` で書くので、1 ミリ秒の中でも必ず増える。同期マージの last-write-wins が順序をつけられるのは
  時刻の違う操作だけで、同じ時刻だとタイブレーク規則（値で決める。[sync-architecture.ja.md](sync-architecture.ja.md)
  参照）で決まる。この規則はどちらが後の操作かを知らないので、同じ時刻だと先の操作が勝つことがあり、作り直した行も
  削除前の行と区別できなくなる。
  これらの時刻は実際の時刻より数ミリ秒先に進むことがあるので、その行へのほかのローカルの書き込みも同じように時刻を
  前に進め、直後に行った編集が必ず最新になるようにしている。
- **ほかの last-write-wins のタイムスタンプも、すべて同じように行の上で前にしか進まない**（`MAX(今の時刻, 前の値 + 1)`）。
  `feeds.custom_title_updated_at` / `folder_updated_at` / `sort_order_updated_at`（`updateCustomTitle`、`updateFolder`、
  `updateSortOrder`、`updateFolderAndSortOrder`）、`articles.read_at` / `starred_at`（`updateReadStatus`、
  `updateReadStatusByIds`、`updateStarStatus`、`markAllRead*` の各クエリ）と `deleted_updated_at`（`softDeleteExpired`）、
  `tags.name_updated_at` / `color_updated_at` / `sort_order_updated_at` と `folders.name_updated_at` /
  `sort_order_updated_at`（それぞれ自分のフィールドが変わったときだけ進める——`upsert`、`updateSortOrder`、
  `upsertByName`）、`feed_tags.updated_at` / `global_settings.updated_at`（`upsert`）がそれにあたる。こうしないと、行が
  すでに持つ値と同じ時刻の書き込み（1 ミリ秒の中の 2 回の書き込みや、マージがその時刻を取り込んだ直後の書き込み）は、
  どちらが後の書き込みかを知らない同着の規則によって、次のマージで古い値に負けうる。これで同着になるのは、2 台の端末で別々に行った書き込みの間だけになる。
- 真偽値・タイムスタンプは **INTEGER（`Long`）**。真偽値は 0/1、時刻は Unix ミリ秒。
- スキーマバージョンは `PRAGMA user_version`（現在 3）。desktop では `DatabaseDriverFactory` がこのプラグマを見て
  create/migrate を自前で駆動するが、Android では `AndroidSqliteDriver` が自身の `onCreate`/`onUpgrade` コールバック内で
  内部的に駆動する。バージョン 2 は `1.sqm` で `articles.deleted_at` / `deleted_updated_at` を追加する。バージョン 3
  （`2.sqm`）は、同期の管理情報（`sync_outbox`、`sync_capture`、`sync_merging`、`sync_applied`、`sync_cursor`、
  `ks_search_pending` と記録用トリガー）を追加し、`feeds` の `etag` / `last_modified` / `error_count` / `last_error` を
  `feed_fetch_state` に移し（`ALTER TABLE … DROP COLUMN`、SQLite 3.35 以上）、`tags` / `folders` のフィールドごとの
  スタンプを追加し（初期値は `updated_at`）、`search_text` が生の `content` / `summary` と同じままの記事（HTML 除去の
  導入前に書かれた行）を `ks_search_pending` に登録する。（SQLDelight は最大のマイグレーションファイル + 1 で
  バージョンを導出。）スキーマを変える場合は `.sqm` ファイル（`<移行元バージョン>.sqm`）を追加すればバージョンは自動で
  上がる。あわせて `domain/MergeSchema.EXPECTED_SCHEMAS`（`DatabaseMerger.validateSchema` が参照する期待スキーマ）、
  `domain/TransportSchema`（差分ファイルのスキーマとそのマイグレーション）、記録用トリガーが比較する列の一覧を
  一緒に更新すること。
  `user_version` が実行中ビルドのスキーマより新しいファイルは決して開かない：desktop は何も書き込む前に
  `DatabaseTooNewException`（`data/local/DatabaseSchemaGuard.kt`）を投げる。Apple も同じガードで同様に拒否する——
  `DatabaseDriverFactory.apple.kt` の `createDriver` が `NativeSqliteDriver` でファイルを開く前に、ファイルの
  `user_version` に対して `requireSupportedSchemaVersion` を呼び、`KeryxSdk.start()` が（desktop の `main.kt` と同じく）
  `findDatabaseTooNew` で Koin のラップから取り出して Swift にエラーとして投げる。Android の open helper は既定でダウングレードを拒否する。

## テーブル一覧

`feeds` / `feed_fetch_state` / `articles` / `tags` / `feed_tags` / `folders` / `global_settings` / `sync_state` /
同期の管理用テーブル / `articles_fts`（仮想）。

### feeds

`id`(PK, UUID), `url`(UNIQUE), `site_url`, `title`, `description`, `favicon_url`,
`custom_title`, `folder_id`(FK→folders, NULL可),
`deleted_at`, `updated_at`, `created_at`, `sort_order`, `folder_updated_at`(NULL可),
`sort_order_updated_at`(NULL可), `custom_title_updated_at`(NULL可), `deleted_updated_at`(NULL可)。

- `url` は購読の一意キーだが主キーではない（URL が変わっても同一フィードとして扱うため）。
- `id` は新規購読時に（リダイレクト解決済みの）`url` から **UUIDv5** で決定的に生成する
  （`IdGenerator.feedId`）。同じフィードは全デバイスで同一 id になるため、同期マージ（feeds を `id` で
  照合）が独立購読されたフィードを収束でき、記事 id（`feed_id` から導出）も一致する。既存 url の再購読は
  既存 id を再利用するため既存行は据え置き（新規のみ決定的）。
- `deleted_at` で論理削除。再購読時に NULL へ戻す。
- `folder_id` はフィードが属するフォルダー（1フィード = 最大1フォルダー）。タグ（`feed_tags`、多対多）とは
  独立した分類軸。`feeds.upsert`（購読・リフレッシュ用）はこの列に一切触れない設計にしており、
  フォルダー割り当ての変更は `feeds.updateFolder` / `updateFolderAndSortOrder` 経由でのみ行う（購読・
  リフレッシュで上書きされない）。
- `folder_updated_at` / `sort_order_updated_at` / `custom_title_updated_at` / `deleted_updated_at` は
  **ユーザー編集フィールド（フォルダー割り当て・並び順・手動リネーム・購読状態）それぞれの後勝ち専用
  タイムスタンプ**（記事の `read_at` / `starred_at` と同じ考え方）。行全体の `updated_at`（内容リフレッシュで
  更新される）とは切り離すことで、他デバイスのリフレッシュがこれらの手動操作を同期マージで打ち消す不具合を
  防ぐ。各フィールドを変える操作クエリのみが対応する列を更新し、`upsert`（リフレッシュ）は一切触らない——ただし
  フィードをグループの末尾に移す再購読は、新しい `sort_order` と一緒に `sort_order_updated_at` も打刻するので、新しい
  位置がほかの端末に届く。
  NULL = そのイベント無し（「時刻 0 に発生」とは区別）。同期マージでは各フィールドをこれらの列で独立に
  後勝ち解決する（[sync-architecture.ja.md](sync-architecture.ja.md)）。購読解除は `softDelete`、再購読は
  `subscribeFeed` 内の `stampResubscribed` が `deleted_updated_at` をスタンプする。

### feed_fetch_state（端末ローカル、非同期）

`feed_id`(PK, FK→feeds), `etag`, `last_modified`, `error_count`（既定 0）, `last_error`。

- この端末が最後にそのフィードを取得したときに得た情報。同期は決してしない: ほかの端末のバリデータ（`etag` /
  `last_modified`）を使うと、この端末が取得したことのない内容について条件付きリクエストを送ってしまい、エラーは
  それを見た端末のものだからである。行は最初の取得時に作る。行の無いフィードは「未取得、エラーなし」として扱う。
- フィード一覧は `error_count > 0`（またはフィードが gone、後述）でエラー扱いとしてフラグを立てる——つまり、
  **この端末で**取得に失敗したとき。`FEED_TIMEOUT_RETRY_COUNT` は無関係な別の定数で、`FeedFetcher` が1回の
  フェッチを諦めるまでのリトライ予算（`core/Constants.kt`）。
- `last_error` の用途は 2 つ: 直前の取得失敗の生のエラーテキストと、410 Gone のフィードに対する固定の内部マーカー `FEED_ERROR_REASON_GONE`（`"gone"`、`markGone` が書き込む）。410 は恒久的でリトライ対象ではないため意図的に `error_count` を増やさないので、このマーカーがフィード消失の唯一の目印であり、フィード一覧の目印（専用のローカライズ済みツールチップ付き。カラムの値そのものはユーザーに表示しない）の判定に使われる。次回の取得成功時に `resetErrorCount` がクリアする。

### articles

`id`(PK), `feed_id`(FK→feeds), `guid`, `url`, `title`, `summary`, `content`, `author`,
`published_at`, `thumbnail_url`, `is_read`, `read_at`, `is_starred`, `starred_at`, `cached_at`,
`search_text`, `updated_at`, `created_at`, `deleted_at`, `deleted_updated_at`。`UNIQUE(feed_id, guid)`。
インデックス: `feed_id`、`is_read`、`is_starred`、および一覧の並び順用の複合インデックス
`(published_at DESC, created_at DESC, id DESC)`。

- `id` は `(feed_id, guid)` から **UUIDv5** で決定的に生成する（`IdGenerator.articleId`）。同じ記事は
  全デバイスで同一 ID になるため、同期マージ（記事を `id` で照合する）が既読・スターを後勝ちで
  伝播できる。既存行は `upsert` の `ON CONFLICT(feed_id, guid)` が既存 ID を保持する
  （決定的生成は新規行のみに効く）。
- 既読・スターの競合解決は `read_at` / `starred_at` で後勝ち。状態が実際に変わったときだけ打刻される（`updateReadStatus` / `updateStarStatus` が変化を条件にしている）ため、既読の記事を再度開いても `read_at` は更新されない。
- `content` は `summary` より優先して表示。両方 NULL の場合、その場にローカライズされた「本文なし」の
  プレースホルダーを表示する（外部ブラウザーで開くリンク／ボタン付き）。自動で何かが開くことはない。
- `search_text` ＝ `content`（無ければ `summary`）の HTML タグを除去した平文。両方 NULL なら `""`。
  挿入・更新時に計算する（`ArticleRepository`、`HtmlText.toPlainText`）。**端末ローカル**で、派生データなので
  同期しない。同期マージが本文を埋めた、または変えた記事は `ks_search_pending` に登録し、マージの直後に
  `search_text` を再計算する（新しく挿入された記事は `""` から始まる）。それまで `FtsManager.indexMissing()` は
  登録された行を飛ばす。
- `deleted_at`（NULL = 生存）で論理削除する。削除を**ローカルで発生させる**のはキャッシュ削除
  （`softDeleteExpired`）のみで、スター付き記事は削除しない（同期マージも `deleted_at` を書き込み、
  他デバイスでの削除を伝播する。後述）。`deleted_updated_at` は削除/復活イベントの
  フィールド別後勝ちタイムスタンプ（`read_at` / `starred_at`、および `feeds.deleted_updated_at` と同様）で、
  コンテンツ更新・既読・スター変更が同期マージで削除を上書きしないよう `updated_at` とは分離する。
  マージでは `deleted_updated_at` の後勝ちで削除が伝播するが、削除より新しいスターがあれば記事を
  **復活**（`deleted_at` → NULL）させる（キャッシュ削除は非スター記事しか消さないため）。これにより、
  削除が次回同期でクラウドから復活せず、他デバイスへ伝播する。ここでは行を物理削除しない（古い
  トゥームストーンの物理GCは将来対応）。全 UI/一覧/検索クエリは `deleted_at IS NULL` で除外し、
  `upsert`（フィード更新）は `deleted_at` に触れないため、更新が削除済み記事を復活させることはない。

### tags / feed_tags

`tags`: `id`(PK), `name`(UNIQUE), `color`, `sort_order`, `deleted_at`, `updated_at`, `created_at`,
`name_updated_at`, `color_updated_at`, `sort_order_updated_at`。

- 名前・色・並び順は、それぞれ自分のスタンプで後勝ちにする（`feeds` の利用者が編集するフィールドと同じ）ので、
  2 台が同じタグの別々のフィールドを変えれば、両方の変更が残る。`updated_at` は作成・削除・再有効化を打刻する。
`feed_tags`: `feed_id` + `tag_id`（複合 PK）, `deleted_at`, `updated_at`。タグの付け外しは `deleted_at` で表現。

### folders

`id`(PK), `name`(UNIQUE), `sort_order`, `deleted_at`, `updated_at`, `created_at`, `name_updated_at`,
`sort_order_updated_at`。

- 名前と並び順は、それぞれ自分のスタンプで後勝ちにする。`updated_at` は `tags` と同じく作成・削除・再有効化を打刻する。
- タグとは独立した分類軸。1フィード = 最大1フォルダー（`feeds.folder_id`、多対多ではない）ため、
  `feed_tags` のような中間テーブルは持たない。
- フォルダー削除時は `FolderRepository.deleteFolder` が同一トランザクションで、紐づく `feeds.folder_id`
  を NULL に戻してからフォルダー自体を論理削除する。ただしこれはローカルDBにしか効かないため、
  同期後に他デバイスの `feeds` が論理削除済み/存在しないフォルダーを指す状態になり得る。UI 側
  （`groupFeedsByFolder`）はこの状態を「フォルダーなし」として防御的に扱う。

### global_settings（KVS, 同期対象）

`key`(PK), `value`（プレーンな文字列 — int/boolean は `toString()` でエンコードし、JSON ではない）,
`updated_at`。既知キー:

| キー | 型 | デフォルト |
| --- | --- | --- |
| `read_timeout_seconds` | INTEGER | 30 |
| `cache_retention_days` | INTEGER \| "null" | 30（"null" = 無期限） |
| `article_list_default_unread_only` | boolean | false |

### sync_state（KVS, 非同期）

`key`(PK), `value`。既知キー: `last_synced_at`（Unix ミリ秒）, `ks_device_id`（現在の世代でのこの端末の同期 ID）,
`ks_generation`, `ks_last_seq`（この端末が最後に確定した差分）, `ks_attempt`（送信中の差分: 番号・名前・outbox の
上限）, `ks_pointer_rev`, `ks_legacy_stub_rev`。端末ローカルで、クラウドとの接続を解除・切り替えると
（`last_synced_at` 以外）消す。

### 同期の管理情報（端末ローカル、非同期）

| テーブル | 内容 |
| --- | --- |
| `sync_outbox` | `seq`(PK, AUTOINCREMENT), `tbl`, `pk`, `heavy` — 同期対象の行へのローカルの変更 1 件につき 1 行。トリガーが追記する |
| `sync_capture` | 1 行だけ: `enabled`（接続中は記録する）, `full_pending`（全量送信が必要） |
| `sync_merging` | マージのトランザクションの中にだけ存在する印の行。記録を止める |
| `sync_applied` | `name`(PK) — マージ済みのクラウドファイル |
| `sync_cursor` | `device`(PK), `seq` — 端末ごとに、すべてをマージし終えている差分の最大番号 |
| `ks_search_pending` | `rowid`(PK) — `search_text` の再計算が必要な記事 |

これらも、`sync_state`、`feed_fetch_state`、`articles_fts` も、端末の外に出ることは決してない: 差分ファイルと
ベースは同期対象のテーブルだけから作る（[sync-architecture.ja.md](sync-architecture.ja.md) 参照）。

### articles_fts（FTS5 仮想テーブル、SQLDelight 管理外）

```sql
CREATE VIRTUAL TABLE articles_fts USING fts5(
  title, search_text,
  content='articles', content_rowid='rowid',
  tokenize='trigram'
);
INSERT INTO articles_fts(articles_fts) VALUES('rebuild');
```

外部コンテンツ方式でインデックスのみ保持し、本文は `articles.search_text` を参照する。

- **ライブ DB の `articles_fts` は決して DROP しない。** 索引が端末の外に出ることはない: 転送ファイルは同期対象の
  テーブルだけから作る — [sync-architecture.ja.md](sync-architecture.ja.md) の「FTS5 の扱い」参照。
- フィード更新・同期マージの後は `FtsManager.indexMissing()` で**未索引の新記事だけを増分投入**する。
  全 `'rebuild'` はホットパスでは決して使わない — `O(索引済みテキスト全体)` で重すぎるため。
- 全再構築（`rebuildIndex()` ＝ `'rebuild'`）は日次アイドル pass（`local_settings.lastFtsRebuiltAt` の
  24h ゲート ＋ `ActivityCenter` のアイドル）でのみ行い、増分投入以降に本文が更新されて古くなった既存行を
  作り直す。
- `'rebuild'` はアトミックかつ `busy_timeout` 待ちなので、実行中の検索が 0 件に後退することはない。
**起動時に `FtsManager.ensureIndexed()` を呼び、テーブルが無ければ作成し、索引に未登録の記事があれば増分投入する。**
Android はこれより軽い `ensureIndexedIfTableAbsent()` をプロセス起動のたびに呼ぶ（`WorkManager` の
ウェイクアップ含め1日最大約96回）— テーブルが既に存在すれば毎回 `indexMissing()` の `O(記事数)` スキャンを
再実行せず、即座に no-op で返る。
Apple の SDK は、UI の状態に関係なくテーブルがあるように、`KeryxSdk.start` でテーブルを作る
（`createTable()`、best effort）。`prepareSearchIndex()` はシーンが active になってからしか走らず、それより
前に行った購読にも索引を入れる先のテーブルが必要だからである。欠けている行の補完は、そのあとの
`prepareSearchIndex()` が行う。

`tokenize='trigram'` は SQLite ≥3.34 を必要とするが、AOSP 自身の SQLite ビルドはこれを提供しない
（どの API レベルでも FTS5 自体を含んでいない）— Android の `DatabaseDriverFactory` actual はバンドル
SQLite を使う。理由と撤退条件は `.claude/rules/android-sqlite-bundling.md` を参照。

**trigram トークナイザは 3 文字未満のクエリ文字列からトークンを一つも生成しない** — 1〜2 文字での
`MATCH` はエラーにならず無音で 0 件を返す。検索の最小文字数（`core/Constants.kt` の
`SEARCH_MIN_TERM_LENGTH`、現在 2）は trigram の下限（`TRIGRAM_MIN_TERM_LENGTH`、3）より小さいため、
2 文字の語は `articles_fts` を使わず `articles` 本体（title と `search_text`）への
`LIKE '%語%'` 走査（バインドパラメータ化・エスケープ済み。文字列連結はしない）にフォールバックする。
`FtsSearch`（`data/local/`）参照。2 文字語のみのクエリは FTS のランクを持たないため、結果は
`published_at DESC` にフォールバックし、`SEARCH_FALLBACK_RESULT_LIMIT`（200）で上限を切る。
3 文字以上の語と 2 文字の語が混在するクエリは、従来どおり `articles_fts MATCH`（ランク順）を実行し、
短い語はマッチした行に対する追加の `LIKE` フィルタとして AND で効かせる。

**検索は `ArticleFilter` でスコープされており、全記事に対して走るわけではない。**
`FtsSearch.search(rawQuery, scope)`（`data/local/FtsSearch.kt`）は現在選択中の `ArticleFilter` を
受け取り、`articleScopeSql` を介して、同じフィルタを普通の（検索でない）記事一覧として表示する場合に
対応する `articles.sq` のクエリ（`watchAll`/`watchStarred`/`watchByFeed`/`watchByTag`/
`watchByFolder`）とまったく同じ行集合になる `WHERE` 句フラグメントを付加する——これら5つのクエリが
`feeds` への JOIN について対称ではないことも含めて: `Starred` と `Feed` は JOIN しないため、購読解除
（論理削除）済みフィードに属するスター付き記事（や、そのフィードの任意の記事）もこの2つのスコープ
では引き続きマッチするが、`All`/`Tag`/`Folder` はそれを除外する。スコープ述語は `feed_id IN (...)`
ではなく `EXISTS (...)` として表現されており、多数のフィードにまたがるフォルダ/タグのスコープでも
バインドパラメータは最大1個で済む——これは上記の短語 `LIKE` 句と同じクエリのバインドパラメータ予算を
共有するため重要である。`SEARCH_FALLBACK_RESULT_LIMIT` はスコープの**内側**に適用される（`WHERE` が
先に行を絞り込んだ上で `LIMIT` がそれに対して数える）のであってその前ではない——そのため、1つの
フィードにスコープされた検索が、無関係な別フィードのヒットに上限を埋められて飢えることはない。
`HomeViewModel` がどのスコープを渡すかについては [app-architecture.ja.md](app-architecture.ja.md) の
「`ArticleFilter` から独立した検索」を参照。

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
| `windowWidth` / `windowHeight` / `windowX` / `windowY` | number\|null | null（ウィンドウの floating 状態の位置・サイズ。`windowPlacement` とは独立に保持し、最大化/フルスクリーン解除時の復帰先になる） |
| `windowPlacement` | string | "floating"（"floating" \| "maximized" \| "fullscreen"。未知の値は floating として復元） |
| `feedListPaneWidth` / `articleListPaneWidth` | number | 260 / 360 |
| `collapsedFolderIds` | string[] | `[]`（フォルダの既定は*展開*なので、畳まれている方だけを記録する） |
| `expandedTagIds` | string[] | `[]`（タグは逆に既定が*折り畳み*で、サイドバーを短く保つ） |
| `lastFilter` | string\|null | null |
| `lastArticleId` | string\|null | null |
| `lastFocusedPane` | string\|null | null |
| `lastUnreadOnly` | boolean\|null | null（記事一覧の「すべて」/「未読」モード。「スター付き」モードはセッション中のみで保存しない） |
| `lastUnreadOnlyStarred` | boolean\|null | null（スター付きフィルタ専用。`lastUnreadOnly` とは独立） |
| `lastNewestFirst` | boolean\|null | null |
| `appMenuBarVisible` | boolean\|null | null（Linux KDE Global Menu: null=自動（`RegisterWindow` が成功するまで表示し、その後非表示）。true/false は Ctrl+M またはエクスポートされた「メニューバーを表示」チェックボックスによる明示的な上書き。`com.canonical.AppMenu.Registrar` が存在しない環境では効果なし） |
| `syncOnMeteredNetwork` | boolean | false（従量制回線 — モバイルデータ通信、テザリング、Android ではユーザーが従量制に設定した Wi-Fi — でも自動クラウド同期を行うか。OS のデータセーバー / 低データモードより優先されることはない。Android と iOS でのみ設定できる。[sync-architecture.ja.md](sync-architecture.ja.md) の「ネットワークゲート」参照） |
| `faviconMissedAt` | object（フィード ID → Unix ms） | `{}`（更新時の favicon 探索がそのフィードで最後に何も見つけられなかった時刻。そのフィードは `FAVICON_RETRY_INTERVAL_MS`（24 時間）の間は探し直さない。それより古いエントリは、追加のたびに削除する。端末ローカルで同期対象外） |

## キャッシュ削除

`cache_retention_days` に基づき、起動時に前回削除から 24 時間以上経過していればバックグラウンドで実行。
スター付き記事とフィードごとの最新 10 件は保持期間に関わらず削除しない。`null`（無期限）なら実行しない。
削除は行の物理削除ではなく**論理削除**（`articles.deleted_at` / `deleted_updated_at` を刻む）で、削除が
クラウドから復活せず同期マージで伝播する。古いトゥームストーンの物理回収は将来対応。

キャッシュ削除は `cached_at`（その記事がフィード内に最後に見えた時刻）で判断する。同期が `cached_at` の変化を
運ぶのは日付が変わったときだけなので、自分ではそのフィードを更新しない端末には、ほかの端末の `cached_at` が最大で
約 1 日遅れて届くことがあり、保持期間の境目にあるスターなしの記事を、毎回の同期が正確な値を運んでいた頃より最大
1 日早く削除することがある。スター付きと最新 10 件の保護は変わらない。

## favicon・サムネイル

画像バイナリは DB に持たず URL のみ保持（同期対象外）。favicon は Coil3 の `AsyncImage` で表示するが、記事サムネイル（`thumbnail_url`）の表示は α では省略している。
