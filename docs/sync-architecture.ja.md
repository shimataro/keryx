# 同期アーキテクチャ

[English](sync-architecture.md)

対象: クラウドストレージ同期（Dropbox / Google Drive / OneDrive）。実装は `domain/SyncRepository.kt`, `domain/MergeSql.kt`, `platform/DatabaseMerger`, `platform/DatabaseSnapshot`。

## 設計方針

- 同期ファイルは `keryx.db`（SQLite）をアップロードする。ただしライブ DB をそのまま読むのではなく、
  `VACUUM INTO` で一貫スナップショットを作り、その**コピー側で `articles_fts` を DROP**してアップロードする
  （ライブの索引は決して DROP しない → 同期中の検索が `no such table` にならない。[db-schema.ja.md](db-schema.ja.md) の `articles_fts` 節）。
- 競合解決はアップロード前にアプリ側でマージ（ATTACH DATABASE）する。
- FTS5 インデックスはクラウドに含めない（コピー側で DROP）。マージで入った新記事はマージ後に**増分投入**する。
- **DB をメモリに載せない。** `CloudStorage` はローカルのファイルパスで受け渡しする
  （`download(path, destPath)` はレスポンスボディをそのままディスクへ、
  `upload(path, sourcePath)` / `create` はファイルをそのまま送信へストリームする）。したがってピーク
  メモリは 64KB バッファ数個分の固定量で、DB のサイズに比例しない。転送の両端はもともとファイルである
  ——マージはダウンロードした DB をファイルとして ATTACH し、アップロード用スナップショットは
  `VACUUM INTO` がファイルとして生成する。共通のストリーミングヘルパーは
  `data/cloud/CloudFileTransfer.kt` にあり `kotlinx-io` で実装しているため、プラットフォーム固有コード
  なしで `commonMain` に置ける。アップロード要否を決めるダイジェスト（後述）と SQLite ヘッダ検証
  （`core/SqliteFile.kt` のパス版 `looksLikeSqliteFile`）もファイルから読む——ヘッダ検証は先頭 16 バイト
  しか読まない。

## クラウド上のファイル構成

```bash
/keryx.db                              ← 同期用 SQLite（articles_fts なし）
/keryx-YYYYMMDD-HHMMSS.db.bak          ← クラウドデータのリセットで退避される旧ファイル（自動削除されない）
```

競合防止はアップロード時のリビジョンチェックで行う — Dropbox: `rev`、サーバー側の compare-and-set（不一致時 409）。Google Drive: ファイルの `version`、クライアント側で比較後に書き込み（同期のリトライで担保）。lock ファイルは使わない。

## 同期フロー（`SyncRepository.sync()`）

1. `CloudStorage.metadata(CLOUD_DB_PATH)` でクラウドファイルのリビジョンを取得する（未作成なら `null` で、
   その場合はローカル DB を create-only でアップロードして終了＝初回）。これは従来の存在チェックを置き換える
   もので、**追加のネットワーク往復は発生しない** — 3 プロバイダとも同じリクエストでリビジョンを既に受け取り
   ながら捨てていた（Dropbox `get_metadata` の `rev` / Drive の名前検索が返す `version` / Graph のアイテムの
   `eTag`）。
2. クラウドの `keryx.db` を一時ファイルへストリームする。ただし**リビジョンが
   `sync_state.cloud_file_rev` と一致する場合はスキップ**する（このデバイスが既にマージ済みのファイルその
   もので、再ダウンロードしても定義上ローカルに入っているバイト列を再マージするだけのため）。
   - ダウンロードしたファイルは、マージャが開く前に SQLite の 16 バイトファイルヘッダと照合される
     （`core/SqliteFile.kt` のパス版 `looksLikeSqliteFile`。先頭 16 バイトしか読まない。アップロード側
     （手順5）と対称なチェック）。これに
     落ちるペイロード（ダウンロードの途中切断、HTML エラーページ、0 バイトなど非 SQLite ファイル）は、
     `DatabaseMerger` まで到達してマージ文の中で `no such table: cloud.folders` のような曖昧なエラーに
     なる前に、`CloudDataIncompatibleException` として即座に弾かれる。
3. **`DatabaseMerger.merge()` でマージ**（後述）。直後に `ftsManager.indexMissing()` でマージが入れた
   新記事を**増分投入**（ライブ索引は wipe しない）してから、`driver.notifyListeners(...)`（マージが触れる
   全テーブル）を呼ぶ。マージは `DatabaseMerger` 専用の生 JDBC コネクションで書き込み SQLDelight のクエリ通知を
   発火しないため、`watchAll` フロー（と索引済みの再検索）を再発火させて同期内容を再起動なしで UI に反映する。
4. `sync_state.cloud_file_rev` を記録。
5. `DatabaseSnapshot.exportForUpload()` で `VACUUM INTO` スナップショットを作り、その**コピー側で
   `articles_fts` と `sync_state` を DROP**（ライブ DB は不変）。そのファイルを `rev` を指定して
   ストリームアップロード。ただし**スナップショットの SHA-256 が `sync_state.last_uploaded_snapshot_digest` と一致し、
   かつ手順2でマージが走らなかった場合はスキップ**する（クラウドに既に同じバイト列があるため）。
   - `rev` 不一致（409 → `SyncConflictException`）なら再ダウンロードからリトライ（最大 3 回）。
6. 成功したら `last_synced_at`、アップロードしたスナップショットのダイジェスト、および**そのアップロードが
   生成したリビジョン**を記録する（`CloudStorage.upload` / `create` が返す）。これは書き込み自身のレスポンス
   から取らなければならず、後追いの `metadata()` で取り直してはいけない — その隙間に他デバイスがより新しい
   書き込みをしていると、そのリビジョンを記録してしまい、次回同期が**未マージの内容のダウンロードをスキップ**
   してしまうため。

デバウンス: 既読・スター等の変更後は `SyncScheduler.scheduleSync()` が最後の操作から一定秒後に
まとめて同期する。

### 変更がないときの転送スキップ

背景ループはタイマーで同期するため、圧倒的多数のケースは「前回から双方とも変更なし」である。そこで
サイクルの両半分をいずれも条件付きにしており、その状態の同期は**メタデータ取得1回・ペイロード0バイト**で済む。

- **ダウンロード**はリビジョンが変わっていなければスキップされる（手順2）。手順6でアップロードが生成した
  リビジョンを記録するので、単独のライターであるデバイスは自分自身のアップロードを認識し、それを
  ダウンロードし直すことがない。
- **アップロード**は、新たに作ったスナップショットのダイジェストが前回アップロード分と一致し、**かつ**
  そのサイクルでマージが走らなかった場合にスキップされる（手順5）。マージが走った場合はローカル側が
  last-write-wins で勝った行（クラウドに無い行）を保持している可能性があるため、他に変更が無くても必ず
  アップロードする。

スナップショットの**内容そのもの**を比較していることが、スキップを安全にしている。ローカルの変更が前回の
ダイジェストに一致することはあり得ないので、変更が黙って失われることはない。逆方向の誤判定（同一なのに
変更ありと見なす）は、従来どおりアップロードするだけで害がない。ダイジェストを安定させているのが手順5の
`sync_state` の除外である — `last_synced_at` は同期成功のたびに書き換わるため、これを含めたままだと毎回
バイト列が変化してこの判定が一度も成立しない。`sync_state` は設計上デバイスローカルであり
（[db-schema.ja.md](db-schema.ja.md) 参照）、`MergeSql` にも `DatabaseMerger` の期待スキーマにも登場しない
ので、受信側がアップロードファイルからこれを読むことは元々なかった。

ダイジェストは `sync_state` に保存する。これ自体がアップロードから除外されているのでデバイスごとの状態と
なり、まだ一度もアップロードしていないデバイスはダイジェストが見つからず単にアップロードする。

この2つのマーカーは**特定プロバイダのファイル**を指すものなので、接続の切断・切り替え時
（`SettingsViewModel.disconnect()` / `switchTo()`）に `clearSyncFailureState()` が失敗状態と一緒に
クリアする。各プロバイダのリビジョンはそれぞれ独自形式の不透明な文字列であり、古い値が次のプロバイダの
ものと一致することは実際上あり得ないが、万一一致すると**未マージの内容のダウンロードをスキップ**して
しまうため、偶然に委ねるべきリスクではない。同じプロバイダに再接続した場合は初回同期で両方とも
再確立される。

このクリア処理は **`sync()` が保持しているのと同じミューテックス配下**で実行する。`updateAutoSyncGate()` /
`emitErrorNotification()` をロックの外ではなく内側で呼んでいるのも同じ理由である — クリアが触れる4つの値
（リビジョン、ダイジェスト、`lastSyncError`、`autoSyncSuspended`）はいずれも同期側も書き込むため、実行中の
同期があると切断処理の**後**に完了して、いま破棄したばかりのプロバイダのマーカーを復活させてしまう。これは
まさにクリアが防ごうとしている「未マージの内容のダウンロードをスキップする」状態そのものである。代償として
切断は実行中の同期の完了を待つことになるが（待ち時間は HTTP タイムアウトで上限が決まり、通常の同期
スピナーとして見える）、順序としてはこちらが正しい。

### 自動同期の抑制

`SyncRepository.sync(trigger: SyncTrigger = MANUAL)` は「誰が呼んでいるか」を受け取る。
`SyncTrigger.AUTOMATIC`（デバウンス書き込みの消費者、`runStartupTasks`、`backgroundUpdateLoop`）は
ゲートの対象になる — `autoSyncSuspended`（`StateFlow<Boolean>`）が true の間、`AUTOMATIC` 呼び出しは
ダウンロード／マージ／アップロードのサイクルを一切実行せず `Result.Ok(Unit)` を返す。同期スピナーも
動かさず、通知センターにも触れないので、既に利用不能と分かっているクラウド DB が書き込みのたびに
再ダウンロード・再マージされることはない。`SyncTrigger.MANUAL`（デフォルト。UI から呼ばれる同期
——ツールバー／メニューの「今すぐ同期」、「すべて更新」、初回接続時の同期、`SettingsViewModel.connect()`
——はすべてこちら）は**常に実際に実行される**。ユーザーが明示的に同期を求めた場合、必ず本当の試行と
その失敗理由を受け取れる — 黙って何もしない、ということは起きない。

ゲートは `updateAutoSyncGate`（`sync()` と `resetCloudData()` の両方から、`emitErrorNotification` の
直前に呼ばれる）が設定する: 結果が `CloudDataIncompatibleException` を運んでいれば true に、
`Result.Ok` ならどんな場合でも false にクリアする。`SchemaVersionException` は意図的に除外している
——同じく永続的だが、その直し方は「アプリを更新する」であり、これでバックグラウンド同期をゲートすると、
新しいバージョンが導入されて動き始めた瞬間が隠れてしまう。`scheduleSync()` もデバウンス信号を積む前に
ゲートを確認するので、クラウドが利用不能と分かっている間は書き込みバーストがデバウンス待ちのループすら
起動しない。

`autoSyncSuspended` はあえて**インメモリで、永続化しない**: プロセス再起動は「他端末がクラウドの
データを直しているかもしれない」を確認できる無料の再試行であり、ゲートの本来の目的
——同じ壊れたファイルを1プロセス内で何度も再ダウンロード・再マージせず、同じ通知を何度も出さないこと
——にはそれ以上の永続性は不要である。成功した同期（手動・自動どちらでも）、`resetCloudData()` の成功、
そして `clearSyncFailureState()`（旧 `clearLastSyncError()` から改名 — ミラーされている失敗理由の
テキストと合わせてゲートもクリアするようになった。接続の解除・切り替え時（
`SettingsViewModel.disconnect()`/`switchTo()`）に呼ばれる）でクリアされる。

リセット／通知の UI 側はゲートの影響を一切受けない: 通知センターの `ResetCloudData` ボタンも設定画面の
リセットボタンも無条件（ゲートされない）で、スキップされた `AUTOMATIC` 呼び出しは `lastSyncError` に
一切触れないため、クラウド同期タブは同期が今なぜ壊れているかを表示し続ける。

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

### マージ失敗の分類

`DatabaseMerger.merge` は、`mergeUnclassified`（分類前の実装本体、private）からの失敗を SQLite の
**エラーコード**（ロケールや JDBC ドライバのバージョンに左右されやすいメッセージ文字列ではない）を使って分類する。
エラーコードは、cause チェーンを辿って `org.sqlite.SQLiteException` を探すことで得る（`findSqliteCause`、
cause の循環に備えて深さ上限あり）。`SchemaVersionException` は、分類する catch-all よりも**先に** catch して
再 throw する — これにより誤って再分類されることはない。分類は意図的に保守的で、認識できないエラー
（cause チェーンに `SQLiteException` が無い場合を含む）はそのまま再 throw される。そのため分類漏れが
挙動を後退させることはなく、`SyncRepository` 自身の catch-all が一時的な `CloudStorageException` として
扱う。

| SQLite の主エラーコード | 分類 |
| --- | --- |
| `SQLITE_NOTADB`、`SQLITE_CORRUPT`、`SQLITE_FORMAT`、`SQLITE_EMPTY` | **永続** → `CloudDataIncompatibleException`。ファイル自体が壊れている。 |
| `SQLITE_CONSTRAINT`（`_UNIQUE`／`_NOTNULL`／`_FOREIGNKEY` 等、全ての拡張 `SQLITE_CONSTRAINT_*` を含む — 拡張コードの下位バイトは常にその主コードに一致する） | **永続** → `CloudDataIncompatibleException`。`MergeSql` の `NOT EXISTS`/`EXISTS` ガード（前述）が main 側の行との衝突は既にすべて潰しているため、マージ文がそれでも制約に触れうるのはクラウド DB 自身の行集合がそれに違反している場合だけ（クラウド DB 内部の url 重複、クラウド側の（より緩い）スキーマが許していた NULL 等）。それはこのアプリのスキーマで表現できないデータであり、まさに `CloudDataIncompatibleException` の意味そのもの。 |
| `SQLITE_ERROR`（`no such table`／`no such column`） | **曖昧** — これは外部・レガシーなクラウドスキーマの見た目でもあり、（無関係なアプリのバグによる）ローカル側スキーマの破損の見た目でもある。ダウンロードしたクラウドファイルに対して `validateSchema` を呼んで解決する：`false` なら `CloudDataIncompatibleException`、`true` または `null`（判定不能）ならどちらもクラウド起因と確信できないためそのまま再 throw。 |
| それ以外（`SQLITE_CANTOPEN`、`SQLITE_IOERR`、`SQLITE_FULL`、`SQLITE_BUSY`、`SQLITE_LOCKED`、`SQLITE_READONLY`、`SQLiteException` が見つからない場合、等） | **一時的／アプリ側** — そのまま再 throw。 |

分類が `DatabaseMerger.merge`（`mergeUnclassified` だけをラップしており、呼び出し元がその後に行うことは
見えない）の内部に完全に閉じているため、`SyncRepository.mergeCloud` の post-commit の処理
——`ftsManager.indexMissing()` と `driver.notifyListeners(...)`。どちらもマージが**既に commit した後**に
走る——は構造的に見えない。ここでの失敗（例: ローカルの `articles_fts` テーブルが DROP されている、これも
`SQLITE_ERROR`）は `SyncRepository` 自身の catch-all で未分類のまま `CloudStorageException` として報告され、
`CloudDataIncompatibleException` には決してならない — マージ自体は既に成功しているので、そのために
破壊的なクラウドデータリセットを提示するのは誤りである。（これは `SyncRepository` 側の旧メッセージ文字列
マッチ方式では実際に起こりうるリスクだった。旧方式の `try` はこの post-commit の呼び出しも同じブロックに
含んでいたため。）

許容している残存リスクが一つある：マージのトランザクション中は `PRAGMA foreign_keys=ON` が有効なので、
main（ローカル）側に既に存在する不整合が、マージの `UPDATE` 文がそれに触れて初めて表面化し、
`SQLITE_CONSTRAINT_FOREIGNKEY` としてクラウド起因に誤分類される可能性は理論上ゼロではない。実際には
起きにくい（例えば `mergeFeedFolderId` は `folder_id` を常に既存の `main.folders` 行か `NULL` のどちらかに
解決する）うえ、誤分類してもデータは失われない — 下記のリセット経路は削除ではなく退避を行うため。
拡張エラーコードはログに残し、事後診断できるようにしている。

**将来対応**: ダウンロードしたクラウド DB に対する `PRAGMA quick_check`/`integrity_check` は、毎回の同期では
あえて実行しない — DB サイズに比例したコストがかかるうえ、SQLite はマージが触れた瞬間に破損ページを別個の
エラーコードとして返す（後述「マージ失敗の分類」参照）ので、マージが触れないページまで毎回全走査しても
得られるものが無い。またこの機能が本来検出したい失敗モード（クラウド DB 自身のスキーマとしては整合していても、
このアプリの制約には違反するデータ）も検出できない（`quick_check` は DB が自分自身のスキーマと整合しているかしか
見ない）。将来追加するとすれば、マージ失敗分類パスの第2段階（曖昧な `SQLITE_ERROR` でスキーマ不一致を除外した後）
に位置づけるべきで、同期のホットパスには置かない。

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

`DatabaseMerger.validateSchema(dbPath, schemaVersion)` は **nullable な** `Boolean` を返す —
登録済みのスキーマバージョンに対するテーブル・カラムの有無なら `true`/`false`、`schemaVersion` が
デスクトップ actual 側の `EXPECTED_SCHEMAS` マップに未登録なら `null`。これは意図的に安全側へ倒す
方向のフェイルセーフである — バージョンを上げた（`KeryxDatabase.Schema.version`）際に対応する
期待スキーマの登録を忘れると、`validateSchema` は `false` ではなく `true` から `null` へ*劣化*し、
呼び出し側はすべて `null` を `true` と同様に扱う — 判定不能な結果を使って破壊的なクラウドデータリセットを
提示してはならない。`SyncMergerTest.validateSchemaReturnsTrueForValidKeryxDb` が現行スキーマバージョンで
`true` になることを固定しているため、登録を忘れるとこのテストが即座に失敗する（本番での挙動劣化として
静かに埋もれることはない）。`schemaVersion` はただの `Long` なのでこれをコンパイラで強制する手段は無く
（sealed / enum の網羅性チェックは効かない）、このテストが実質的な歯止めになっている。

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

## クラウドデータのリセット（退避）

`SyncRepository.resetCloudData()` は、アプリが使用できないクラウドDBからの復旧手段である。クラウド上のファイルをいきなり削除はしない — `archiveCloudDb()` がまず `CloudStorage.rename()` でタイムスタンプ付きパスへリネームする（`core/CloudBackupPath.kt` の `cloudBackupPath(clock.nowMillis())`、例: `/keryx-20260811-103000.db.bak`。端末のタイムゾーンに関わらず同じ瞬間なら同じ名前になるよう UTC で整形）。その後 `createFresh()` がローカルDBのスナップショットを新しい `/keryx.db` として再アップロードする。退避ファイルは自動削除されない — `CloudStorage` に一覧取得APIが無いことに加え、この仕組みを取り除くと「誤判定によるリセット」や「破損に見えただけのマージバグ」からの復旧手段自体が失われてしまう。

- **`rename` の意味論**（`CloudStorage.rename`、プロバイダごとに実装 — Dropbox は `files/move_v2`（`autorename=false`）、Google Drive は名前検索で解決したファイルIDへのメタデータ `PATCH`、Microsoft Graph はアプリフォルダ内アイテムへのメタデータ `PATCH`）: リネーム元が既に存在しない場合は冪等に `Result.Ok`（クラウドフォルダが既にきれいな状態でのリセットも `createFresh` まで進む）。リネーム先が既に存在する場合は上書きせず失敗する。
- **削除へのフォールバック**: リネーム自体がストレージ側の理由（退避先が既に存在する等）で失敗した場合、`archiveCloudDb()` は `cloud.delete(CLOUD_DB_PATH)` にフォールバックし、リセットが恒久的に行き詰まることを防ぐ。`CloudAuthException` だけは削除へリトライしない — 同じ資格情報の欠如で削除も同様に失敗するため。
- **退避ファイル名**: あえて `CLOUD_DB_PATH` から派生させていない（`core/Constants.kt` の `CLOUD_DB_BACKUP_PREFIX`/`CLOUD_DB_BACKUP_SUFFIX`）。退避ファイルのベース名が Google Drive の `name = 'keryx.db'` 検索や OneDrive のベース名アドレッシングに一致してしまうと、`CloudStorage.exists(CLOUD_DB_PATH)` が退避ファイルまで拾ってしまうため。
