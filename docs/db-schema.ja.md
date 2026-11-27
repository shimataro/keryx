# DB スキーマ

[English](db-schema.md)

対象: ローカル SQLite（SQLDelight 管理）。`.sq` ファイルは
`shared/src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/` にある。

実ファイルの実体パスはプラットフォームによって異なる: デスクトップの `JdbcSqliteDriver` は
`AppDirs.appDataDir()/keryx.db` を直接開くが、Android の `AndroidSqliteDriver` は
`Context.getDatabasePath("keryx.db")` — `<dataDir>/databases/keryx.db` に置く。これは
`AppDirs.appDataDir()`（`Context.filesDir`、すなわち `<dataDir>/files`）とは別のディレクトリである。
`platform/DatabaseFile.kt` の `databaseFilePath()` がプラットフォームごとの実値を解決する唯一の
`expect` 関数であり、`DatabaseMerger`/`DeltaExporter`（どちらもドライバ経由ではなくパスで動作する）
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
  `updateReadStatusByIds`、`updateStarStatus`、`markAllRead*` の各クエリ）と `deleted_updated_at`（`softDeleteExpired`）
  （と、`updateStarStatus` のスター解除が打刻する削除の取り消し）——スターと削除はマージで別々のグループなので、
  それぞれ自分の前の値より大きく打刻する——、`articles.body_updated_at`（記事の upsert。本文の列が変わったときだけ進める）、
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
  `ks_search_pending`、`ks_search_awaiting_body`、`ks_feed_folder_raw` とそこから `feeds.folder_id` を解決するビューと
  トリガー、記録用トリガー）を追加し、`feeds` の `etag` / `last_modified` / `error_count` / `last_error` を
  `feed_fetch_state` に移し（`ALTER TABLE … DROP COLUMN`、SQLite 3.35 以上）、**`tags` / `folders` を作り直す**——
  フィールドごとのスタンプの追加（初期値は `updated_at`）、`UNIQUE(name)` の廃止、そして各行の移行前 id を
  `v2_id` に保持することを 1 回のテーブル差し替えで行い、
  その間に子を退避・復帰させる（外部キーは有効のまま: `feeds.folder_id` は参照先のフォルダーが存在するときだけ
  退避して NULL にし、タグが存在する `feed_tags` 行を作業表へ移す。親がもともと存在しない子は触らずに残るので、
  `foreign_key_check` に新しい違反は現れない。フィードが存在しないリンクは復帰できないため捨てられる）——、
  `articles.body_updated_at` を追加し（初期値は `updated_at`）、スター付きかつ削除済みの記事のスターを、その削除の時点で
  外れたものとして記録し（`is_starred` を 0、`starred_at` を 2 つのスタンプの新しいほうにする。後述の「articles」参照）、`search_text` が生の `content` / `summary` と同じままの記事（HTML 除去の
  導入前に書かれた行）を `ks_search_pending` に登録し、さらにすべての移行が走らせる `AfterVersion(2)` コールバック
  （`domain/TagFolderIdRekey.kt`。各プラットフォームのドライバーと `DatabaseMerger` のクラウドファイル移行に
  `KERYX_SCHEMA_CALLBACKS` として配線済み）で、**すべてのタグとフォルダーの id を名前から導かれた id に振り直す**
  （`IdGenerator.tagId` / `folderId`）: 行を新しい id でコピーし（`v2_id` も引き継ぎ）、`feed_tags.tag_id` / `feeds.folder_id` を
  付け替え、旧行を削除し、付け替えを `ks_id_remap` に記録する。`SettingsRepository` は起動後のバックグラウンドの読み込みで
  `local_settings.json` の同じ id（`lastFilter`、`collapsedFolderIds`、`expandedTagIds`）を書き換える（後述の `ks_id_remap`
  を参照。表は保持するのでこの書き換えはべき等）。旧 id は行の `v2_id` にも残り、その組は同一性の辺として `ks_v2_edge` に記録される。同期マージは、移行前に
  別々の名前へリネームした 2 台の端末の行をこの辺で結び付ける（後述の「tags / feed_tags」を参照）。べき等で、2 度走らせても何も変わらない。
  （SQLDelight は最大のマイグレーションファイル + 1 で
  バージョンを導出。）スキーマを変える場合は `.sqm` ファイル（`<移行元バージョン>.sqm`）を追加すればバージョンは自動で
  上がる。あわせて `domain/MergeSchema.EXPECTED_SCHEMAS`（`DatabaseMerger.validateSchema` が参照する期待スキーマ）、
  `domain/TransportSchema`（差分ファイルのスキーマとそのマイグレーション）、記録用トリガーが比較する列の一覧を
  一緒に更新すること。同期対象のテーブルや列を新しく足すときは、同じ変更でそのマージのフィールドグループとスタンプも
  定める：どのグループにも属さない列は、明示した畳み込み規則（`RowFold`：最も早い `created_at`、記事の最も遅い
  `cached_at` / `updated_at`、NULL 以外で最大の `v2_id`）でしか変わらず、グループにも畳み込み規則にも属さない列は
  端末間で食い違う。（同一性のマージは、行の id と、それが書き換える参照（`feed_tags.feed_id` /
  `tag_id`、`articles.feed_id`）を正規の id へ移す。これは畳み込み規則ではないので、畳み込み規則を持たないほかの
  列——`feeds.url` のような `UNIQUE` の列や、フォルダー割り当てのような値としての参照——には、なおフィールドグループが
  要る。）
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
  既存 id を再利用するため既存行は据え置き（新規のみ決定的）。導いた id が使えないとき（`feeds.isIdTaken`）は、
  新規購読はランダムな id（`IdGenerator.newId`）を取る: 別の URL の行が持っている——恒久的なリダイレクトで URL が
  変わったフィードで、upsert するとその行の URL を書き換えてしまう——か、`ks_id_remap` が別の id へ写しているときで、
  書き込みが別のフィードへ解決してしまう id を持つ行は決して生じない。この判断は購読のトランザクション自身の読み取りで行う。
- **フィードの id は動くことがある。** 恒久的なリダイレクトは id を保つので、移動したフィードの URL で新たに購読された
  フィードは、その URL を別の id で持つ。1 つの URL を持った 2 つのフィード id は同一性の辺として `ks_feed_edge` に
  記録し、同期マージ——または、同じホストへの恒久的なリダイレクトが別のフィードの URL に着いたフィード更新——が、
  その同一性のフィードをすべて正規の id（最小の id）へ、記事・タグの結び付き・取得状態・生のフォルダー割り当てと
  ともに移す。移動は `ks_id_remap`（kind `feed`）に記録し、古い行は削除する（[sync-architecture.ja.md](sync-architecture.ja.md)
  の「フィードと記事の同一性」参照）。移動の前から保持されている id でもその行に届くよう、**フィード id を受け取る
  書き込みはすべて**、タグ・フォルダーの書き込みと同じく、文の中でそれを解決する——`feeds.sq`: `softDelete`、
  `stampResubscribed`、`reviveAfterMerge`、`updateCustomTitle`、`updateFaviconUrlIfMissing`、`updateContent`、`updateUrl`、
  `recordRedirectEdge`、`updateFolder`、`updateSortOrder`、`updateFolderAndSortOrder`。`feed_fetch_state.sq`:
  `upsertValidators`、`incrementErrorCount`、`resetErrorCount`、`markGone`。`feed_tags.sq`: `upsert`。`articles.sq`:
  `insert`、`insertIfAbsent`、`markAllReadByFeed`（と一覧のクエリ `watchByFeed`）。`feeds.resolveId` は同じ解決を
  読み取りとして行い（`FeedRepository.resolveFeedId`）、公開された振り直しの対応表が読み込まれる前に復元する保存済みの
  フィードのフィルターや、移動の前に描画された行からのフィード更新に使う。行も要る読み取りは、同じ文の中で解決する——
  間にコミットされた merge が、削除したばかりの行の id を返さないようにするため: `feeds.getByIdResolved`
  （`FeedRepository.getFeedByIdResolved`、id 指定の更新）と `feeds.groupForMove`（移動先のグループ。移動するフィードと
  目標の id を、その行の中で解決する——`moveFeed`）。`feeds.reviveAfterMerge` は、利用者が
  どちらかのフィードをまだ購読していたのにリダイレクトの統合で削除済みになったフィードを、打刻した再購読として復活させる。
- `updated_at` は内容グループの後勝ちのスタンプで、capture のトリガーはこれを見ないので、既存の行では記録される
  変更と一緒にしか動かさない: `upsert` は書く列が変わったときだけこれを動かし、`updateUrl` は URL が変わるとき
  だけ（かつ別のフィードがそれを持っていない間だけ）書き、`updateContent`（リフレッシュの内容の書き込み）はサイトの URL、
  タイトル、説明のどれかが変わるときだけ書く。送られずに動いたスタンプは、ほかの端末の後の内容の変更に、
  この端末では勝ち、向こうでは負けてしまう。
- `deleted_at` で論理削除。再購読時に NULL へ戻す。
- `folder_id` はフィードが属するフォルダー（1フィード = 最大1フォルダー）。タグ（`feed_tags`、多対多）とは
  独立した分類軸。`feeds.upsert`（購読・リフレッシュ用）はこの列に一切触れない設計にしており、
  フォルダー割り当ての変更は `feeds.updateFolder` / `updateFolderAndSortOrder` 経由でのみ行う（購読・
  リフレッシュで上書きされない）。
- **`folder_id` / `folder_updated_at` はキャッシュである。** フィードのフォルダー割り当ては `ks_feed_folder_raw`
  （下の「同期の管理情報」参照）に生のまま——勝った版が書いたままのフォルダー id とスタンプで、そのフォルダーが論理
  削除されていても——保存し、その行が正である: `updateFolder` / `updateFolderAndSortOrder` がそれを書き、同期マージが
  それに記録し、送信がそれを運ぶ。生の値は書いた後に書き換えない——マージがフォルダーの同一性を移すときも正規の id へ
  読み替えず、この端末が行を持たないときも置き換えない——ので、2 つの割り当ての同着はどこでも同じ値で決まる
  （[sync-architecture.ja.md](sync-architecture.ja.md) の「マージ」参照）。差分同期以前のファイルの孤立した参照だけは
  NULL として記録する。`feeds.folder_id` はビュー `ks_feed_folder_resolved`——生のフォルダー id を `ks_id_remap` で
  1 段読み替えたフォルダーが存在して生きていればそれ、そうでなければ NULL——で解決したもので、`folder_updated_at` は
  生のスタンプである。生の行が変わったとき、フォルダーが追加・削除・復活したとき、または `ks_id_remap` のフォルダーの
  項目が変わったとき、トリガーが同じ文の中で両方を書き直すので、削除されたフォルダーに割り当て
  られたフィードは「フォルダーなし」と表示され、そのフォルダーが（マージで、または同じ名前で作り直して）復活すると
  そこに戻る。ただし、フォルダーを削除すると、どの端末で削除しても、そのフォルダーのフィードは「フォルダーなし」に
  移される（他の移動と同じく同期される生の値の変更。「folders」の `FolderRepository.deleteFolder` 参照）ので、
  復活で戻るのは、生の割り当てがまだそのフォルダーを指すフィードだけである——たとえば、削除と同時に別の端末でその
  フォルダーへ移したもの。この 2 列はトリガーが導くものなので、記録用トリガーはこれらを見ず、生の行の変更を記録する。`updateFolder` は要求された
  フォルダーで feeds の行を先に書くので、存在しないフォルダーは引き続きその FOREIGN KEY が拒む。
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
- バリデータは取得元の URL に属するので、**フィードの `url` が変わると破棄する**（トリガー
  `ks_fetch_state_validators_url_change`。`error_count` と `last_error` は残す）。url を変えるものが何であれ——
  同期の merge が他端末の url を取り込む、フィードの identity merge が正規のフィードに他方の url を持たせる
  （または他方の fetch state を移す）、恒久リダイレクト——次の取得は無条件になり、新しい url から取得したことの
  ない内容に対して `304` が返りうる条件付きリクエストにはならない。refresh は自分の url の書き込みとリダイレクトの
  merge の後にレスポンスのバリデータを保存するので、リダイレクト先のものは、残った行に残る——ただしその行が、バリデータを
  取得した url をまだ持っている間だけ（`upsertValidators` は url を受け取り、文の中で確かめる）: refresh は snapshot で
  動くので、フェッチ中に同期の merge がフィードを別の url へ移していたら、バリデータは書かれず、次の取得は無条件になる。
- フィード一覧は `error_count > 0`（またはフィードが gone、後述）でエラー扱いとしてフラグを立てる——つまり、
  **この端末で**取得に失敗したとき。`FEED_TIMEOUT_RETRY_COUNT` は無関係な別の定数で、`FeedFetcher` が1回の
  フェッチを諦めるまでのリトライ予算（`core/Constants.kt`）。
- `last_error` の用途は 2 つ: 直前の取得失敗の生のエラーテキストと、410 Gone のフィードに対する固定の内部マーカー `FEED_ERROR_REASON_GONE`（`"gone"`、`markGone` が書き込む）。410 は恒久的でリトライ対象ではないため意図的に `error_count` を増やさないので、このマーカーがフィード消失の唯一の目印であり、フィード一覧の目印（専用のローカライズ済みツールチップ付き。カラムの値そのものはユーザーに表示しない）の判定に使われる。次回の取得成功時に `resetErrorCount` がクリアする。

### articles

`id`(PK), `feed_id`(FK→feeds), `guid`, `url`, `title`, `summary`, `content`, `author`,
`published_at`, `thumbnail_url`, `is_read`, `read_at`, `is_starred`, `starred_at`, `cached_at`,
`search_text`, `updated_at`, `created_at`, `deleted_at`, `deleted_updated_at`, `body_updated_at`(NULL可)。
`UNIQUE(feed_id, guid)`。
インデックス: `feed_id`、`is_read`、`is_starred`、および一覧の並び順用の複合インデックス
`(published_at DESC, created_at DESC, id DESC)`。

- `id` は `(feed_id, guid)` から **UUIDv5** で決定的に生成する（`IdGenerator.articleId`）。同じ記事は
  全デバイスで同一 ID になるため、同期マージ（記事を `id` で照合する）が既読・スターを後勝ちで
  伝播できる。既存行は `upsert` の `ON CONFLICT(feed_id, guid)` が既存 ID を保持する
  （決定的生成は新規行のみに効く）。
- **記事の id は動くことがある。** フィードがその同一性の正規の id へ移るとき、その各記事は
  `(正規のフィード id, guid)` をキーとし、そのキーの最小の id へ移る。1 つのキーの行が複数あれば rowid が最大の行が
  残り（ほかはそれに畳んで削除する）、残る行は rowid を保つ。移動は `ks_id_remap`（kind `article`）に記録する。記事 id を
  受け取る書き込みは文の中でそれを解決し（`updateReadStatus`、`updateReadStatusByIds`——集合として——、`updateStarStatus`）、
  `insert` / `insertIfAbsent` はフィード id を解決して記事 id は渡されたままにする。離れた id は、移った行が今そのキーを
  持つ記事のものなので、挿入は衝突の対象に当たる。`resolveId` / `getByIdResolved` は時間をまたいで保持された id を
  解決し、`movedIdsIn` / `rekeysOnto` は、id の集合のうちどれが移ったか、移動がどの id に着いたかを答える
  （`HomeViewModel`。[app-architecture.ja.md](app-architecture.ja.md) の「id の振り直し」参照）。
- 既読の競合解決は `read_at` で後勝ち。スターは `starred_at` で後勝ちの独立したグループで、削除（後述）とは別に決める。どちらも状態が実際に変わったときだけ打刻される（`updateReadStatus` / `updateStarStatus` が変化を条件にしている）ため、既読の記事を再度開いても `read_at` は更新されない。
- `body_updated_at` は本文自身の後勝ちタイムスタンプで、マージが 1 つの版からまとめて取る `content`・`summary`・
  `title`・`url`・`author`・`published_at`・`thumbnail_url` に対するもの。記事の upsert（`insert`、`insertIfAbsent`）は
  新しい行では書き込みの時刻を入れ、既存の行では本文の列が実際に変わったときだけ前に進める（`MAX(今の時刻, 前の値 + 1)`）。
  既読・スター・削除の書き込みは決して触れない。したがって、記事を開いただけの端末が、自分の古い本文で別の端末の
  新しい本文に勝つことはない。`updated_at` は行の汎用のタイムスタンプ（どの書き込みでも進む）のままで、どのマージ
  グループのキーでもない。列が NULL 可なのは `2.sqm` が追加する（初期値は `updated_at`）からにすぎず、どの書き込みも
  値を入れる。
- `content` は `summary` より優先して表示。両方 NULL の場合、その場にローカライズされた「本文なし」の
  プレースホルダーを表示する（外部ブラウザーで開くリンク／ボタン付き）。自動で何かが開くことはない。
- `search_text` ＝ `content`（無ければ `summary`）の HTML タグを除去した平文。両方 NULL なら `""`。
  挿入・更新時に計算する（`ArticleRepository`、`HtmlText.toPlainText`）。**端末ローカル**で、派生データなので
  同期しない。同期マージが本文を埋めた、または変えた記事は `ks_search_pending` に登録し、次の
  `FtsManager.indexMissing()` が索引を作る前に `search_text` を再計算する（新しく挿入された記事は `""` から始まる）。
  `indexMissing()` は登録されたままの行を飛ばす。
- `deleted_at`（NULL = 削除されていない）で論理削除する。削除を**ローカルで発生させる**のはキャッシュ削除
  （`softDeleteExpired`）のみで、スター付き記事は削除しない（同期マージも `deleted_at` を書き込み、
  他デバイスでの削除を伝播する。後述）。`deleted_updated_at` は削除/復活イベントの
  フィールド別後勝ちタイムスタンプ（`read_at` / `starred_at`、および `feeds.deleted_updated_at` と同様）で、
  コンテンツ更新・既読の変更が同期マージで削除を上書きしないよう `updated_at` とは分離する。
  マージではスター（`is_starred` を `starred_at` で）と削除（`deleted_at` を `deleted_updated_at` で）を
  **独立した 2 つのグループ**とし、グループをまたぐ規則は持たない。そのため、行がスター付きかつ削除済みになることが
  ある——ある端末のスターと別の端末のキャッシュ削除をどちらの順序でマージしても、またフィードや記事の同一性が 2 行を
  1 つに寄せても。バージョン 2 もこの状態の行——他の端末のキャッシュ削除より古いスター——を書き、`deleted_at IS NULL`
  の行しか表示しなかったので隠していた。`2.sqm` はその各行のスターを、削除の時点で外れたものとして記録する（ローカル
  DB でも、マージが移行する pre-delta のクラウドファイルでも同じ）ので、アップグレード後も表示されない。
  **削除されていないか、スター付きの記事は表示される**: `(deleted_at IS NULL OR is_starred = 1)` で、
  読むときに決める。一覧・未読数・検索・`aliveFlagsIn` / `idsByFeeds` の照会・すべて既読にする、のすべてがちょうど
  この判定を使う（`articles.sq`。生の SQL と Kotlin には `data/local/ArticleVisibility.kt`）ので、スターがキャッシュ
  削除に負けることはない。スターはスターのグループにしか触れない（`updateStarStatus`）。この端末で既に削除済みの行の
  スターを外すと削除の取り消し（`deleted_at` を NULL にし、`deleted_updated_at` を進める）も打刻するので、その記事は
  次のキャッシュ削除で再び削除されるまで表示されたままになる。削除されていない行のスターを外しても削除には触れない
  ので、他の端末がそれより前に行った削除があとから届けば適用され、スターの外れた記事は表示されなくなる（バージョン 2
  と同じ）。これにより、
  削除が次回同期でクラウドから復活せず、他デバイスへ伝播する。ここでは行を物理削除しない（古い
  トゥームストーンの物理GCは将来対応）——フィードの同一性の統合だけが、1 つの記事の余った行を残る行に畳んで削除する（上述）。`getByFeedAndGuid` / `getGuidsByFeed` は削除済みの行も意図的に含める
  （フィード更新が削除済みの記事を追加し直さないため）。`upsert`（フィード更新）は `deleted_at` に触れないため、更新が削除済み記事を復活させることはない。

### tags / feed_tags

`tags`: `id`(PK), `name`, `color`, `sort_order`, `deleted_at`, `updated_at`, `created_at`,
`name_updated_at`, `color_updated_at`, `sort_order_updated_at`, `v2_id`（NULL 可）。

- `id` は作成時の名前から **UUIDv5** として決定的に生成される（`IdGenerator.tagId`。大小区別あり、完全一致）。
  同じ名前のタグは全デバイスで同じ id を持つので、id で照合する同期マージは、各端末で独立に作成された同名タグを
  1 行に収束させる。リネームしても id は変わらない——つまり id は「元の名前」から導かれたものであり、その元の名前で
  後から作成すると派生 id が使用中になるため、ランダム UUIDv4 にフォールバックする（`tags.insertIfNameAbsent` が
  1 文の中で両者を選ぶ）。既存のランダム id は v2→v3 移行で派生 id に振り直された（後述）。
- **タグの id は変わりうる。** v2→v3 移行は行の id を振り直し、同期マージも同じ同一性の別の id へ行を移すことがある。
  こうした移動はすべて `ks_id_remap`（後述）に記録され、旧行は残らない。移動前から保持していた id でもその行に
  届くよう、**タグ id を受け取る書き込みはすべて**、書き込み文そのものの中で **id を解決する**——行がその id を
  持っていればそのまま、なければ `ks_id_remap` が写す先の id（`tags.sq` の `updateName`・`updateColor`・`softDelete`、
  `feed_tags.upsert`、`articles.markAllReadByTag`）。そのため、古い id で発行されたリネーム・色の変更・削除・付け外し・
  すべて既読（開いていたリネーム欄、キューに積まれたフィードリストの書き込み、ドラッグ）は、何にも当たらずに
  終わることも外部キー違反になることもなく、移った先の行に適用される。解決は文の一部であって文の前の読み取りでは
  ないので、どのトランザクションにも最初の書き込みより前の読み取りが増えず、その間に振り直しが割り込むこともない。
  `insertIfNameAbsent` は `ks_id_remap` が写す id を使用中として扱うので、新しい行がそうした id を取ることはなく、
  解決が新しく作った行へ逸れることもない。
- `v2_id` はその振り直しの前に行が持っていた id で、v2→v3 移行が設定し、移行後に作られた行では NULL。v2 の id を
  共有していた 2 台が、アップグレード前にその行を別々の名前にリネームしていると、それぞれ別の名前由来 id を導く。
  組 `(v2_id, id)` は同一性の辺（`ks_v2_edge`）であり、同期マージは 1 つの同一性のすべての行を——両側とも——その
  正規の id、つまりその同一性の最小の行 id（v2 時代の id は候補にならない）へ移す（`domain/V2IdentitySql.kt`。
  [sync-architecture.ja.md](sync-architecture.ja.md) の「タグとフォルダーの同一性」）。列そのものは何も決めない:
  辺の供給源であり、差分・base も辺を届けられるよう transport ファイルが運ぶ。マージや畳み込みは、まとめる行のうち
  非 NULL で最大の値を残す（もう一方の辺は `ks_v2_edge` に残る）ので、どの端末も同じ値に行き着く。一時的な列であり、削除できる条件は [known-issues.ja.md](known-issues.ja.md) の
  「`tags.v2_id` / `folders.v2_id` は一時的で、いずれ削除する必要がある」を参照。
- `name` は **UNIQUE ではない**（スキーマバージョン 3 で制約を廃止）: リネームが同名の作成と交差すれば同名の行が
  併存し、UI は序数で区別する。名前による検索は非一意インデックス `tags_name` が支える。名前でタグを解決する
  すべての経路（作成・再有効化）は、その名前の 1 つの**正規の行**（正規の行 = 生きている行を優先し、次に
  `created_at`、最後に `id` の順で最初の行。`tags.getByName`）に着地する。UI は生きている行どうしの重複を
  引き続き警告する（`NameValidation`）ので、2 つ目の同名行はリネームと作成の交差によって作られるのであって、
  作成ダイアログによってではない。
- 名前・色・並び順は、それぞれ自分のスタンプで後勝ちにする（`feeds` の利用者が編集するフィールドと同じ）ので、
  2 台が同じタグの別々のフィールドを変えれば、両方の変更が残る。`updated_at` は作成・削除・再有効化を打刻する。
`feed_tags`: `feed_id` + `tag_id`（複合 PK）, `deleted_at`, `updated_at`。タグの付け外しは `deleted_at` で表現。

### folders

`id`(PK), `name`, `sort_order`, `deleted_at`, `updated_at`, `created_at`, `name_updated_at`,
`sort_order_updated_at`, `v2_id`（NULL 可）。

- `id` は作成時の名前から **UUIDv5** として決定的に生成される（`IdGenerator.folderId`）。仕組みは `tags.id`
  と同じ（名前空間は別なので、同名のタグとフォルダーが同じ id を持つことはない）。派生 id が使用中（または
  `ks_id_remap` が写す id）のときのランダム id へのフォールバックも同様で、書き込みに渡されたフォルダー id も
  同じように解決される（`folders.sq` の `updateName`・`softDelete`・`updateSortOrder`、`feeds.sq` の
  `updateFolder`・`updateFolderAndSortOrder`・`getByFolder`・`nextSortOrderInGroup`、`articles.markAllReadByFolder`）。
  そのため、古いフォルダー id での移動・リネーム・並べ替え・削除は、移った先のフォルダーに適用される。
- `v2_id` は `tags.v2_id` と同じ移行前 id（上記参照）: v2→v3 移行が設定し、移行後に作られた行では NULL。
  同期マージは、アップグレード前に別々の名前へリネームされたフォルダーを、この値から得る同一性の辺で 1 つの正規の
  id へ移す。
- `name` は **UNIQUE ではない**（スキーマバージョン 3 で制約を廃止）。理由は `tags.name` と同じ: リネームが同名の
  作成と交差すれば同名の行が併存し、序数で表示される。非一意インデックス `folders_name` が名前による検索を支え、
  名前による検索はその名前の正規の行に着地する（上の `tags` と `folders.getByName` を参照）。
- 名前と並び順は、それぞれ自分のスタンプで後勝ちにする。`updated_at` は `tags` と同じく作成・削除・再有効化を打刻する。
- タグとは独立した分類軸。1フィード = 最大1フォルダー（`feeds.folder_id`、多対多ではない）ため、
  `feed_tags` のような中間テーブルは持たない。
- フォルダー削除時は `FolderRepository.deleteFolder` が同一トランザクションで、紐づくフィードを「フォルダーなし」へ
  移し（各フィードの raw なフォルダー割り当ての、タイムスタンプ付きの通常の変更として。`feeds.updateFolderAndSortOrder` が
  `ks_feed_folder_raw` を書き、`folder_updated_at` / `sort_order_updated_at` を刻む）、そのあとフォルダー自体を論理削除する。
  この移動は他の移動と同じように同期されるので、他デバイスでもそれらのフィードは割り当て解除される。削除と並行して
  別デバイスがそのフォルダーへ移したフィードは raw な割り当てを保ち、フォルダーが復活すれば戻る（上の `feeds` を参照）。
  UI 側（`groupFeedsByFolder`）は、同期が届くまでの間のために、削除済み/存在しないフォルダーを指すフィードを
  今も「フォルダーなし」として防御的に扱う。

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
`ks_generation`, `ks_last_seq`（この端末が最後に確定した差分）, `ks_attempt`（送信中のファイル: 番号・名前・outbox の
上限・全量送信かどうか）, `ks_pointer_rev`, `ks_received_at`（この端末が最後に受信を終えた時刻）, `ks_bases`（JSON:
この端末が自分の世代のベースについて知っていること）, `ks_legacy_stub_rev`, `sync_index_pending`（同期のマージのコミットから、
検索インデックスがそれに追いつくまで存在し、索引付けの失敗を次の同期で再試行させる）。端末ローカルで、クラウドとの接続を
解除・切り替えると（`last_synced_at` と `sync_index_pending` 以外）消す。

### 同期の管理情報（端末ローカル、非同期）

| テーブル | 内容 |
| --- | --- |
| `sync_outbox` | `seq`(PK, AUTOINCREMENT), `tbl`, `pk`, `pk2`（`feed_tags` のみ: タグ id）, `heavy` — 同期対象の行へのローカルの変更 1 件につき 1 行。トリガーが追記する。capture 有効のまま行うマージ（差分同期以前のマージ）が `ks_v2_edge` に加えた同一性の辺も 1 件につき 1 行（`tbl` は `folder_v2_edges` / `tag_v2_edges`、`pk` は移行前の id、`pk2` は行 id）、capture 有効の間に——マージの中も含めて——`ks_feed_edge` に加わったフィードの同一性の辺も 1 件につき 1 行（`tbl` は `feed_edges`、`pk` は `a`、`pk2` は `b`） |
| `sync_capture` | 1 行だけ: `enabled`（接続中は記録する）, `full_pending`（全量送信が必要） |
| `sync_merging` | マージのトランザクションの中にだけ存在する印の行。記録を止める |
| `sync_applied` | `name`(PK) — マージ済みのクラウドファイル |
| `sync_cursor` | `device`(PK), `seq` — 端末ごとに、すべてをマージし終えている差分の最大番号 |
| `ks_search_pending` | `rowid`(PK) — `search_text` の再計算が必要な記事 |
| `ks_search_awaiting_body` | `id`(PK) — マージが本文なし（軽量行）で挿入した記事。本文が届くか、日次 rebuild が索引するまで検索索引に入れない |
| `ks_feed_folder_raw` | `feed_id`(PK), `folder_updated_at`, `folder_id` — フィードごとの生のフォルダー割り当て（勝った版が書いたままのフォルダー id とスタンプ。この端末で生きているかは問わない）: `feeds.folder_id` / `folder_updated_at` の解決元となる正（ビュー `ks_feed_folder_resolved`）であり、マージが生と生で比べる値、送信が運ぶ値でもある。フィードごとに 1 行で、フィードと一緒に（トリガーで）作られ、既存のフィードの分は `2.sqm` が埋める。`folder_id` は書かれたまま保つので、この端末が行を持たないフォルダーの同一性の別名を指すことがあり（`ks_id_remap` で解決する）、NULL になるのはフォルダーなしか、差分同期以前のファイルの孤立した参照だけである。参加でも接続解除でも残す |
| `ks_id_remap` | `kind` + `old_id`（複合 PK）, `new_id` — 振り直しすべての記録: 行が持っていた id と、移った先の id。`kind` `tag` / `folder`: v2→v3 移行による名前由来 id への移動（`domain/TagFolderIdRekey.kt`）と、同期マージによる同じ同一性の別の id への移動（同一性の別名すべてについて記録し、他の端末のファイルだけが使った別名や、同一性の辺だけが指す v2 時代の id も含む。フィードの生のフォルダー割り当てがそれを指すことがあり、`ks_feed_folder_resolved` はこの表で解決する）。`kind` `feed` / `article`: 同期マージ、またはフィード更新のリダイレクトによる統合が、フィードと、統合されたフィードの各記事を正規の id へ移したもの（「feeds」と「articles」参照。他の端末のファイルだけ、またはフィードの辺だけが指す別名も含めて記録する）。記録は必ず `domain/IdRemapSql.kt` を通し、移動を足すたびに種類の中で連鎖を畳む（`a → b` の後に `b → c` なら `a → c`・`b → c` が残る。元へ戻す移動では、再び生きた id の項目を消す）ので、引くのは 1 回で済み、旧 id を持つ行は存在しない（`insertIfNameAbsent` と `feeds.isIdTaken` は写される id を使用中として扱う）。データベースの外で保持される id を最新に保つために使う: `domain/IdRemaps.kt` が、起動後にバックグラウンドでタグ・フォルダー・フィードの移動（`idRemapPublished`。記事の移動は含めない——フィードの統合は、データベースの外で保持されるものよりはるかに多くの記事を移しうるため）を読み込み、行の id を振り直したマージのたびに、畳んだ対応表全体の `StateFlow` として再公開する。そこから `SettingsRepository` は `local_settings.json`（`lastFilter`、`collapsedFolderIds`、`expandedTagIds`、`faviconMissedAt` のキー）を、`HomeViewModel` は選択・展開状態・保留中の削除フォールバックを書き換える。移動をまたいで保持される記事 id（選択、そのピン、保存済みの `lastArticleId`）は、代わりにこの表から 1 つずつ解決する（`articles.resolveId`）。タグ・フォルダー・フィード・記事の id を受け取る書き込みはすべて、この表を通して id を解決する（「feeds」「articles」「tags / feed_tags」「folders」を参照）。無期限に保持するので、再適用しても何も変わらない。同期マージはこの表の移動も同一性の辺として読む（ふつうは `ks_v2_edge` / `ks_feed_edge` にある辺と重なる）ので、この端末が離れた id も、移った先の同一性に残る。新規作成 DB では、何かが振り直すまで空 |
| `ks_v2_edge` | `kind` + `v2_id` + `id`（複合 PK）— タグとフォルダーの同一性の辺（`kind` は `tag` / `folder`）: 移行前の id と、それを持つ行が振り直された先の行 id の組で、辺でつながる行は 1 つの識別になる。増えるだけの集合: v2→v3 の振り直しが `v2_id` を持つすべての行について書き、同期マージのたびに（クラウドファイルの辺の表と、クラウドと main の行のうち `v2_id` を持つものの `(v2_id, id)` を）加える。削除はせず、参加時にも接続解除時にも消さない——辺は 2 つの id についての事実であり、接続についてのものではない。全量送信は、これを転送ファイルの `folder_v2_edges` / `tag_v2_edges` として運び、capture 有効でマージ用マーカーが無いときに加わった辺は `sync_outbox` にも記録され（トリガー `ks_capture_v2_edge_insert`）、次の通常の差分が運ぶ（[sync-architecture.ja.md](sync-architecture.ja.md) の「転送用スキーマ」参照）。マージの同一性の処理は、辺がつなぐ id を 1 つの同一性とし、そのうち最小の行 id を正規の id とする（同「タグとフォルダーの同一性」参照） |
| `ks_feed_edge` | `a` + `b`（複合 PK、`WITHOUT ROWID`）— フィードの同一性の辺: ある時点で同じ `url` を持った 2 つのフィード id の組で、`a < b` に正規化したもの。辺でつながるフィードは 1 つのフィードで、その正規の id は連結成分のうち（バイト順で）最小の id（[sync-architecture.ja.md](sync-architecture.ja.md) の「フィードと記事の同一性」参照）。増えるだけの集合: 同期マージのたびに（main とファイルの間、ファイルの中で気付いた URL の衝突と、転送ファイルの `feed_edges` を）書き、同じホストへの恒久的なリダイレクトが別のフィードの URL に着いたフィード更新も書く（`feeds.recordRedirectEdge`）。削除はせず、`ks_v2_edge` と同じく参加時にも接続解除時にも消さない。`a < b` の正規化は書く側が行う。全量送信は、これを転送ファイルの `feed_edges` として運び、capture 有効の間に加わった辺はすべて `sync_outbox` にも記録され（トリガー `ks_capture_feed_edge_insert`。ほかの capture のトリガーと違い、マージの中でも記録する——端末が衝突に気付くかどうかは到着順に左右されるため）、次の通常の差分が運ぶ |

`ks_v2_edge` と `ks_feed_edge` を除くこれらも、`sync_state`、`feed_fetch_state`、`articles_fts` も、端末の外に出ることは決してない:
差分ファイルとベースは同期対象のテーブルだけから作る（[sync-architecture.ja.md](sync-architecture.ja.md) 参照）。
ただしフィードの `folder_id` / `folder_updated_at` は、その `ks_feed_folder_raw` の行——この表が持つための値——から
書き、全量送信とベースにはそれに加えて `ks_v2_edge` と `ks_feed_edge` の同一性の辺を入れる。通常の差分は outbox に
記録された辺を運び、送る生のフォルダー割り当てが別名を指すときは、その別名の連結成分のフォルダーの辺も運ぶ（[sync-architecture.ja.md](sync-architecture.ja.md) の
「送信」参照）。

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
- `'rebuild'` はアトミックかつ `busy_timeout` 待ちなので、実行中の検索が 0 件に後退することはない——ただし再構築が
  `busy_timeout` 以内に書き込みロックを手放す場合に限る。それより長く待った検索は `SQLITE_BUSY` で失敗し、
  `ArticleRepository.search` は結果なしを返す（[sync-architecture.ja.md](sync-architecture.ja.md) の「FTS5 の扱い」を参照）。
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
スター付き記事とフィードごとの最新 10 件は保持期間に関わらず削除しない（別の端末のキャッシュ削除が削除したスター付き記事も、そのまま表示される。上の `articles` 参照）。`null`（無期限）なら実行しない。
削除は行の物理削除ではなく**論理削除**（`articles.deleted_at` / `deleted_updated_at` を刻む）で、削除が
クラウドから復活せず同期マージで伝播する。古いトゥームストーンの物理回収は将来対応。

キャッシュ削除は `cached_at`（その記事がフィード内に最後に見えた時刻）で判断する。同期が `cached_at` の変化を
運ぶのは日付が変わったときだけなので、自分ではそのフィードを更新しない端末には、ほかの端末の `cached_at` が最大で
約 1 日遅れて届くことがあり、保持期間の境目にあるスターなしの記事を、毎回の同期が正確な値を運んでいた頃より最大
1 日早く削除することがある。スター付きと最新 10 件の保護は変わらない。

## favicon・サムネイル

画像バイナリは DB に持たず URL のみ保持（同期対象外）。favicon は Coil3 の `AsyncImage` で表示するが、記事サムネイル（`thumbnail_url`）の表示は α では省略している。
