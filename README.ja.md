# Keryx

[English](README.md)

**One reader. Every device.**

ローカルファースト・クロスプラットフォームな RSS リーダー

🌐 **ウェブサイト**: <https://keryx.merc.works/ja/>

## 特徴

- **マルチデバイス同期**: クラウドストレージ経由（Dropbox / OneDrive / Google Drive — Android の
  Google Drive は Google Play 開発者サービスが必要なため、それが無い端末では Dropbox と OneDrive に対応）
- **ローカルファースト**: 中央サーバーなし、同期機能を使わなければローカルで完全に動作
- **高速なローカル全文検索**: 記事本文・タイトルを対象に、キーワードで瞬時に検索
- **タグ・フォルダーによるフィード整理**: タグは横断的に複数付与、フォルダーは階層的に分類
- **RSS 2.0 / Atom 1.0 対応**: RSS 1.0/RDF も緩く解釈
- **OPML インポート・エクスポート**: 他の RSS リーダーとの相互移行・バックアップに対応
- **完全無料**: フリーミアムなし、試用期間なし、広告なし
- **オープンソース**: ソースコードが公開されており、誰でも検証・改変可能

## 対応プラットフォーム

現在 Windows / macOS / Linux / Android に対応。macOS 版はネイティブな Mac アプリで、Apple Silicon 搭載の Mac と
macOS 14（Sonoma）以降が必要です。iOS/iPadOS への対応は今後の予定。

## ダウンロード

最新版は [リリースページ](https://github.com/shimataro/keryx/releases) から入手できます。
Linux は x86_64・arm64 の両方に対応しています。

> [!IMPORTANT]
> **macOS**: Apple による署名・公証済みのため、ほかのダウンロードしたアプリと同じように開けます
> ——追加の手順は不要です。Apple Silicon 搭載の Mac と macOS 14 以降が必要です。
>
> **Windows**: コード署名証明書を用意するまでの間、`.msi` は未署名のため、初回実行時に
> Windows SmartScreen が「WindowsによってPCが保護されました」という警告を表示します。
> 「詳細情報」→「実行」の順にクリックして続行してください。
>
> **Android**: この `.apk` は Google Play を経由せず直接配布するサイドロード版のため、
> 初回インストール時に「提供元不明のアプリ」の許可を求められます。

Keryx は起動した後、通知ベル・タスクトレイ・設定の「アップデート」から（macOS では、メニューバーの
**Keryx →「アップデートを確認…」**から、または設定の「一般」に従って自動的に）、新しいリリースの
確認・ダウンロード・インストールを自分自身で行えます。上記の手動手順が必要なのは、この最初の
インストールのときだけです。Keryx 自身がダウンロードしたファイルは、ブラウザがディスクに保存
したものではないため、ブラウザ経由のダウンロードのように Gatekeeper の検疫警告や Windows
SmartScreen の「未署名ファイル」プロンプトが出ることはありません。Android では、上記の
「提供元不明のアプリを許可する」設定は最初の一度だけ必要で、以降のアップデートのたびに許可し
直す必要はありません。

これが当てはまるのは、macOS 版、Windows の `.msi`／ポータブル版、Linux のポータブル版、
そしてサイドロードした Android の `.apk` です。Linux の `.deb`／`.rpm` または Snap インストールと、
Google Play 経由の Android インストールについては、代わりにリリースページを開くので、そこから
いつもの経路（パッケージマネージャ、あるいは Play 自身の自動更新）で更新してください——新しい
バージョンがあることは Keryx が教えてくれますが、その場でのインストールまでは行いません。

## 開発用ドキュメント

- [設計ドキュメント一覧](docs/README.ja.md)
- [ビルド方法・開発環境のセットアップ](docs/setup.ja.md)
- [パッケージング（配布用アプリの作成）](docs/build.ja.md)

## 今後の予定

- 対応プラットフォーム
  - [x] Windows
  - [x] macOS
  - [x] Linux ( `.deb` )
  - [x] Linux ( `.rpm` )
  - [x] Linux ( Snap )
  - [x] Android
  - [ ] iOS
  - [ ] iPadOS
- 対応クラウドストレージ
  - [x] [Dropbox](https://www.dropbox.com/)
  - [x] [Google Drive](https://drive.google.com/)
  - [x] [OneDrive](https://onedrive.live.com/)
- 多言語対応
  - [x] 英語
  - [x] 日本語

## その他

- [プライバシーポリシー](PRIVACY.ja.md)
- [利用規約](TERMS.ja.md)
- [ライセンス（MIT）](LICENSE)
