# Keryx

[English](README.md)

**One reader. Every device.**

ローカルファースト・クロスプラットフォームな RSS リーダー

🌐 **ウェブサイト**: <https://keryx.merc.works/ja/>

## 特徴

- **マルチデバイス同期**: クラウドストレージ経由（Dropbox / Google Drive / OneDrive）
- **ローカルファースト**: 中央サーバーなし、同期機能を使わなければローカルで完全に動作
- **高速なローカル全文検索**: 記事本文・タイトルを対象に、キーワードで瞬時に検索
- **タグ・フォルダーによるフィード整理**: タグは横断的に複数付与、フォルダーは階層的に分類
- **RSS 2.0 / Atom 1.0 対応**: RSS 1.0/RDF も緩く解釈
- **OPML インポート・エクスポート**: 他の RSS リーダーとの相互移行・バックアップに対応
- **完全無料**: フリーミアムなし、試用期間なし、広告なし
- **オープンソース**: ソースコードが公開されており、誰でも検証・改変可能

## 対応プラットフォーム

現在 Windows / macOS / Linux に対応。Android・iOS/iPadOS/macOS のネイティブアプリは今後対応予定。

## ダウンロード

最新版は [リリースページ](https://github.com/shimataro/keryx/releases) から入手できます。

> [!IMPORTANT]
> **macOS**: 正式な署名付きリリースが提供されるまでの間、ダウンロードした `.dmg` / `.zip` は
> 未署名のため Gatekeeper にブロックされます。`.dmg` を開く・`.zip` を展開する前に、
> ダウンロードしたファイル自体の quarantine（検疫）属性を削除してください
> （こうすることで、開く・展開した後の App にも属性が伝播しません）。
>
> ```bash
> xattr -d com.apple.quarantine ~/Downloads/Keryx-*.dmg   # .dmg の場合
> xattr -d com.apple.quarantine ~/Downloads/Keryx-*.zip   # .zip の場合
> ```
>
> または、ダブルクリックの代わりに右クリックして「開く」を選択してください。背景は
> [署名・公証](docs/build.ja.md#署名公証将来対応)を参照。
>
> **Windows**: コード署名証明書を用意するまでの間、`.msi` は未署名のため、初回実行時に
> Windows SmartScreen が「WindowsによってPCが保護されました」という警告を表示します。
> 「詳細情報」→「実行」の順にクリックして続行してください。

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
  - [ ] Android
  - [ ] iOS
  - [ ] iPadOS
- 対応クラウドストレージ
  - [x] [Dropbox](https://www.dropbox.com/)
  - [x] [Google Drive](https://drive.google.com/)
  - [x] [OneDrive](https://onedrive.live.com/)
- 多言語対応
  - [ ] 英語
  - [x] 日本語

## その他

- [プライバシーポリシー](PRIVACY.ja.md)
- [利用規約](TERMS.ja.md)
- [ライセンス（MIT）](LICENSE)
