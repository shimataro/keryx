# アプリアーキテクチャ

[English](app-architecture.md)

## 設計方針

- レイヤードアーキテクチャ（UI → ViewModel → Repository → DataSource）
- Koin で依存性注入、androidx.lifecycle ViewModel で状態管理
- SQLDelight でローカル DB を型安全に管理
- 同期処理は Repository 層に閉じ込め、UI 層は同期の存在を意識しない
- プラットフォーム固有コードは `commonMain` の `expect` + `desktopMain` の `actual` に集約

## ディレクトリー構成

```text
composeApp/src/
  commonMain/kotlin/works/merc/keryx/app/
    core/      Constants, Result, KeryxException, ArticleFilter, AppNotification, Clock, DateTimeParser, CloudStorageAvailability(expect)
    data/local/   DatabaseDriverFactory(expect), FtsManager, FtsSearch, LocalSettings(Store)
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, CloudSession, NotificationCenter, MergeSql, IdGenerator, CloudConnectFlow
    di/           AppModule（+ expect platformModule）
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot（すべて expect）
    ui/           theme/, navigation/, setup/, home/（3ペイン + 検索 + 通知センター）, article/, settings/, i18n/
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq（7 テーブル）
  commonMain/composeResources/  values/strings.xml, drawable/
  desktopMain/kotlin/…/  main.kt + 各 expect の actual（DatabaseDriverFactory, AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, Pkce, PlatformModule）+ OAuthConnectFlow, OAuthRedirectTransport（CustomUri/Loopback）, OAuthUriParser, SingleInstanceCoordinator, TokenStorage 実装（Keyring/File/SecurityCliTokenStorage）, DesktopOs（isMacOs/isLinux）, DesktopLookAndFeel（Swing L&F: Linux は FlatLaf）
  commonTest/ + desktopTest/
```

パッケージルートは `works.merc.keryx.app`（`keryx.merc.works` の逆順 DNS）。

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

### FtsManager / FtsSearch

`articles_fts`（FTS5 trigram, `content='articles'`）を生 SQL で管理する。SQLDelight のスキーマには含めない。
**ライブ DB では決して DROP しない**（アップロードからの除外はスナップショットのコピー側で行う。`DatabaseSnapshot`）。
`ensureIndexed()`（起動時、初回作成 + 未索引行の増分投入）、`indexMissing()`（hot path＝フィード更新・同期マージ後の
増分投入）、`rebuildIndex()`（日次アイドルの全再構築 heal のみ）を持つ。検索は `FtsSearch` が `MATCH` クエリを
実行し、記事 ID をランク順に返す。

### DatabaseMerger（expect / actual）— 同期マージの要

ATTACH DATABASE マージは**専用の JDBC コネクション 1 本**で行う。SQLDelight の JVM ドライバは
ファイル DB に対してステートメントごとに新しいコネクションを開くため、`ATTACH` が後続のマージ文に
見えない。`DatabaseMerger` が attach → バージョン確認 → マージ（トランザクション）→ detach を
1 コネクションで完結させる。

### CloudSession / SyncRepository

`CloudSession` が現在の `CloudStorage`（Dropbox / Google Drive）を提供し、アクセストークンの自動リフレッシュを担う。
`SyncRepository` はダウンロード → マージ（`DatabaseMerger`）→ 新記事の増分索引（`indexMissing`）→
`VACUUM INTO` スナップショット生成（`DatabaseSnapshot`、コピー側で `articles_fts` を除外）→ アップロード
（rev チェック）、のフローとデバウンス（`SyncScheduler`）を実装する。ライブ DB の FTS は触らない。

### Provider / DI（Koin）

`appModule`（commonMain）にリポジトリ・サービス・ViewModel を登録。`platformModule`（desktop）に
HttpClient・TokenStorage・CloudSession・CloudConnectFlow を登録。ViewModel は単一ウィンドウの
デスクトップアプリのためアプリスコープの `single` として登録し、`koinInject()` で取得する。

## ドメインモデルの方針

SQLDelight の生成クラス（`Feeds` / `Articles` / …）をそのまま各層で使う。列名は snake_case のまま
プロパティになる（例: `feed.site_url`）。真偽値・タイムスタンプは `Long`（0/1・Unix ミリ秒）で保持し、
表示時に kotlinx-datetime で変換する。別途ドメインモデルクラスは定義しない。

## ナビゲーション

`ui/navigation/Navigator.kt` の単純なスタック型ナビゲータで Setup / Home / Settings を切り替える。
記事ビューは Home 内のペイン（ルートではない）。
