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
- **日本語・英語 UI**: システムの言語設定に合わせて表示
- **完全無料**: フリーミアムなし、試用期間なし、広告なし
- **オープンソース**: ソースコードが公開されており、誰でも検証・改変可能

## ダウンロード

<a href="https://f-droid.org/packages/works.merc.keryx/"><img src="https://f-droid.org/badge/get-it-on-ja.svg" alt="F-Droid で入手" height="80"></a>
<a href="https://snapcraft.io/keryx"><img src="https://snapcraft.io/static/images/badges/jp/snap-store-black.svg" alt="Snap Store から入手" height="56"></a>

| プラットフォーム | 動作要件 | 入手先 |
| --- | --- | --- |
| Windows | x86_64 | [リリースページ][releases] の `.msi`／ポータブル版 ZIP |
| macOS（ネイティブアプリ） | Apple Silicon 搭載の Mac、macOS 14（Sonoma）以降 | [リリースページ][releases] の `.dmg` |
| Linux | x86_64 / arm64 | [Snap Store](https://snapcraft.io/keryx)、または [リリースページ][releases] の `.deb`／`.rpm`／ポータブル版 ZIP |
| Android | Android 8.0 以降 | [F-Droid](https://f-droid.org/packages/works.merc.keryx/)、または [リリースページ][releases] の `.apk` |

[releases]: https://github.com/shimataro/keryx/releases

iOS/iPadOS への対応は今後の予定です。F-Droid 版は Google Drive 同期に対応していません（Dropbox・OneDrive は通常どおり使えます）。

> [!IMPORTANT]
> **Windows**: コード署名証明書を用意するまでの間、`.msi` は未署名のため、初回実行時に
> Windows SmartScreen が「WindowsによってPCが保護されました」という警告を表示します。
> 「詳細情報」→「実行」の順にクリックして続行してください。
>
> **Android**: F-Droid からインストールする場合、追加の手順は不要です。リリースページの `.apk` は
> 直接配布するサイドロード版のため、初回インストール時に「提供元不明のアプリ」の許可を求められます。

Keryx は起動した後、通知ベル・タスクトレイ・設定の「アップデート」から（macOS では、メニューバーの
**Keryx →「アップデートを確認…」**から、または設定の「一般」に従って自動的に）、新しいリリースの
確認・ダウンロード・インストールを自分自身で行えます。そのため、上記の Windows と Android の手順が
必要なのは最初のインストールのときだけです。Keryx 自身がダウンロードしたファイルはブラウザ経由の
ダウンロードではないため、Windows SmartScreen の「未署名ファイル」プロンプトは出ません。Android の
「提供元不明のアプリを許可する」設定も最初の一度だけで、アップデートのたびに許可し直す必要はありません。

アプリ内アップデートに対応するのは、macOS 版、Windows の `.msi`／ポータブル版、Linux の
ポータブル版、そして Android の `.apk`（リリースページ配布分）です。Linux の `.deb`／`.rpm` や Snap で
インストールした場合は、代わりにリリースページを開きます——新しいバージョンがあることは Keryx が
教えてくれますが、その場でのインストールまでは行いません。`.deb`／`.rpm` はパッケージマネージャで
更新してください。Snap は Snap Store によって自動で更新されるので、リリースページから何かを
ダウンロードする必要はありません。F-Droid 版はアップデートを F-Droid クライアントに任せており、
アプリ内のアップデート機能そのものを持ちません。

## 開発用ドキュメント

- [設計ドキュメント一覧](docs/README.ja.md)
- [ビルド方法・開発環境のセットアップ](docs/setup.ja.md)
- [パッケージング（配布用アプリの作成）](docs/build.ja.md)

## その他

- [プライバシーポリシー](PRIVACY.ja.md)
- [利用規約](TERMS.ja.md)
- [ライセンス（MIT）](LICENSE)
