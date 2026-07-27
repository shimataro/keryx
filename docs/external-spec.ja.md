# 外部仕様書

[English](external-spec.md)

外部仕様。

## 1. プロダクト概要

複数デバイスで同じ RSS 購読体験を提供する、軽量でシンプルな RSS リーダー。

- シンプル・スタイリッシュ・高速な UI/UX
- ローカルファースト（アカウント不要、データは手元に）
- クラウドストレージ（Dropbox / Google Drive / OneDrive）経由でのデバイス間同期

## 2. 対応プラットフォーム

| プラットフォーム | 対応 |
| --- | --- |
| Windows / macOS / Linux | ✅（Compose Multiplatform、現行） |
| Android | 予定（Jetpack Compose） |
| iOS / iPadOS / macOS | 予定（最初は Compose、その後 SwiftUI ネイティブ UI） |

## 3. 対応フォーマット

RSS 2.0 / Atom 1.0（RSS 1.0/RDF も緩く解釈）。JSON Feed は α 以降。

## 4. 同期方式

- Keryx へのアカウント登録なし。ユーザー自身のクラウドストレージ（Dropbox / Google Drive / OneDrive）を同期バスとして
  利用する。有効な接続は常に 1 つで、どのプロバイダーを使うかはユーザーが選択・切替する（同時接続はしない）。
- 同期ファイルは SQLite（`keryx.db`）をそのままアップロードする。
- 同期対象: 購読リスト・既読状態・スター・タグ構造・グローバル設定。
- 非同期対象: デバイスローカル設定・クラウド認証情報。
- インポート / エクスポートは OPML。

> **DB・同期ファイルは旧版（前身実装）と互換にしない**（ユーザーの決定）。

## 5. 競合解決ポリシー

| データ | ポリシー |
| --- | --- |
| 既読・未読、スター | タイムスタンプ後勝ち（`read_at` / `starred_at`） |
| 記事本文 | OR マージ（どちらかにあれば保持） |
| 購読リスト（追加） | OR マージ |
| 購読リスト（削除）、タグ・フォルダー、グローバル設定 | タイムスタンプ後勝ち（論理削除で伝播） |
| デバイスローカル設定 | 同期しない |

詳細は [sync-architecture.ja.md](sync-architecture.ja.md)。

## 6. セットアップフロー

初回起動でローカルのみ / クラウド同期（Dropbox・Google Drive・OneDrive）を選択する。クラウド選択時は OAuth 認証後、
クラウドに既存データがあれば初回同期で自動的にマージ（インポート）される。

## 7. 基本機能

- URL 指定でのフィード購読、タグによる分類、OPML インポート/エクスポート
- フィード健全性管理: 301/308 は購読 URL を自動更新（通知）、410 Gone は通知センターに警告、
  連続エラーはフィード一覧にインジケータ表示
- 記事一覧・記事ビュー（リーダービュー）。**記事を選択した瞬間に既読**。未読に戻す操作あり。
- スター（永続）、外部ブラウザーで開く
- SQLite FTS5（trigram）によるローカル全文検索（3 文字以上）
- デスクトップ通知・タスクトレイ常駐（閉じるとトレイに収納）・通知センター。
  Linux ではトレイに D-Bus の `org.kde.StatusNotifierItem` + `com.canonical.dbusmenu`、通知に
  `org.freedesktop.Notifications` を使い、StatusNotifierItem ホストが居ない環境では AWT の
  システムトレイにフォールバックする。

### フィード URL 変更・消滅時の挙動

| HTTP | 挙動 |
| --- | --- |
| 301 / 308（恒久） | 購読 URL を自動更新（通知） |
| 302 / 303 / 307（一時） | 追従するが購読 URL は変更しない |
| 410 Gone | 通知センターに警告（自動削除はしない） |
| タイムアウト | 定数回リトライ後にエラー通知 |

> 301 のみ対応・リダイレクトループガードなしだったバグを修正し、全リダイレクトコード
> 対応 + 最大 5 回のループガードを実装している。

## 8. アクセシビリティ・国際化

- UI 文言はすべて Compose Resources（`values/strings.xml`）で管理。システムロケールに応じて選択し、
  対応言語がなければデフォルト（日本語）へフォールバック。現状は日本語のみ同梱。
- 文字サイズ設定（`LocalDensity` の fontScale に反映）。

## 9. UI 方針

Material 3 ベース + カスタムテーマ（teal）。ライト/ダーク/システム対応。3ペインレイアウト
（フィード一覧・記事一覧・記事詳細）+ キーボードナビゲーション。

Compose が描画していない面 — アプリケーションメニューバー・コンテキストメニュー・ダイアログの
ボタン列 — は実際の Swing/AWT ウィジェットなので、プラットフォームの Look & Feel に従う。
macOS と Windows はシステム標準を使い、Linux は Keryx 自身の teal テーマに合わせて配色した
FlatLaf を使う（Java の Linux 向けシステム L&F は GTK2 世代のエミュレーションで、現代の
デスクトップでは古く見えるため）。ライト/ダークはアプリのテーマ設定に再起動なしで追従する。
コンテキストメニューは macOS / Windows では `java.awt.PopupMenu`（本物の `NSMenu` /
Win32 メニュー）、Linux では `javax.swing.JPopupMenu`（AWT のポップアップは Look & Feel を
完全に無視するため）。UI フォントは OS 標準で、macOS は SF Pro、Windows は Segoe UI、
Linux は Look & Feel が解決したフォント、次にデスクトップの設定フォント（XSettings から取得）、
取得できなければ Adwaita Sans / Cantarell / Ubuntu / Noto Sans / DejaVu Sans の順にフォールバックする。

## 10. プライバシー・セキュリティ

- サーバーへのデータ送信なし、アカウント登録不要、通信は HTTPS のみ。
- Dropbox トークンは OS のセキュアストレージ（Keychain / Credential Manager / Secret Service、
  java-keyring 経由）に保存。利用不可時はデータディレクトリのファイルにフォールバック。

## 11. 技術選定

| レイヤー | 採用技術 |
| --- | --- |
| UI | Compose Multiplatform（Material 3） |
| 状態管理 | androidx.lifecycle ViewModel + Koin |
| DB | SQLDelight（SQLite）+ FTS5（生 SQL） |
| HTTP | Ktor client（CIO） |
| RSS/HTML/XML パース | ksoup |
| シリアライズ / 日時 | kotlinx-serialization / kotlinx-datetime |
| クラウド同期 | Ktor + Dropbox / Google Drive / OneDrive（Microsoft Graph）REST API（OAuth PKCE + リフレッシュトークン） |
| i18n | Compose Resources |
| テスト | kotlin-test + kotlinx-coroutines-test + Ktor MockEngine |
| ビルド | Gradle 9.6（Kotlin 2.4 / Compose 1.11 / JDK 25 toolchain） |
| 画像ロード | Coil3（favicon 表示。SVG デコード対応、既存 HttpClient 共有、ディスクキャッシュあり） |

フィード一覧・記事一覧の両方で、favicon（`feeds.favicon_url`）を Coil3 の `AsyncImage` で表示する。
未取得・読み込み失敗時はレター（頭文字）アバターまたは汎用アイコンにフォールバックする。
