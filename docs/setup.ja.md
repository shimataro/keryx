# 開発環境セットアップ

[English](setup.md)

## 前提

- **JDK 25 以上**（`JAVA_HOME`）。macOS なら Temurin / Homebrew の openjdk 等。
- IDE: IntelliJ IDEA / Android Studio（Kotlin Multiplatform プラグイン）推奨。

## 初回

```bash
git clone <repo>
cd kmp
cp local.properties.example local.properties   # 任意: Dropbox App Key を設定
./gradlew build
```

`build` が通れば SQLDelight / Compose Resources / BuildConfig のコード生成、コンパイル、テストまで
一通り確認できる。

## データディレクトリ

アプリのローカルデータ（`keryx.db`, `local_settings.json`）は OS 標準の場所に作られる。

| OS | パス |
| --- | --- |
| macOS | `~/Library/Application Support/Keryx` |
| Windows | `%APPDATA%\Keryx` |
| Linux | `$XDG_DATA_HOME/Keryx`（既定 `~/.local/share/Keryx`） |

開発中にデータを初期化したい場合はこのディレクトリの `keryx.db` と `local_settings.json` を削除する。

## よくある問題

- **`UnsupportedClassVersionError`（実行時）**: `./gradlew` を起動した JVM が 25 未満。
  `JAVA_HOME` を JDK 25+ に設定する。
- **ツールチェーンのダウンロードがブロックされる**:
  `-Dorg.gradle.java.installations.auto-download=true` を付ける。
- **Dropbox 連携が表示されない**: `DROPBOX_APP_KEY` が未設定（仕様どおり非表示）。`build.md` を参照。
- **`./gradlew :composeApp:run` で Dropbox / OneDrive 連携が完了しない（全デスクトップ OS 共通）**:
  これらのリダイレクト URI はカスタムスキーム `keryx://` で、接続ボタンが disabled のまま
  タイムアウトする。理由は OS ごとに異なる。macOS: LaunchServices が `keryx://` を
  **パッケージ版 `Keryx.app`**（Info.plist の `CFBundleURLTypes`）にルーティングし、`gradlew run` の
  インスタンスには届かない。Windows / Linux: スキーム登録は起動時に行われるが、パッケージ版
  ランチャーからの起動時のみ——JDK の `java` バイナリを OS に登録すると、Gradle 実行終了後も
  壊れたハンドラーが残ってしまうため。**連携を確認・実施する場合は、
  `./gradlew :composeApp:createDistributable` でビルドしたアプリ
  （`composeApp/build/compose/binaries/main/app/` 以下）を起動して行う**こと
  （gradle 実行中インスタンスは先に終了しておく）。連携で保存したトークンは keychain またはデータ
  ディレクトリの `.dropbox_tokens.json` に格納される。Google Drive はループバック受信なので
  `gradlew run` でも連携できる。
- **（Linux）ブラウザーが `keryx://` で「不明なプロトコル」エラーを出す**: スキームがデスクトップ
  環境に登録されていない。パッケージ版は初回起動時に登録し、
  `$XDG_DATA_HOME/applications/keryx-url-handler.desktop`（既定 `~/.local/share/applications/keryx-url-handler.desktop`）と
  `$XDG_CONFIG_HOME/mimeapps.list`（既定 `~/.config/mimeapps.list`）の関連付けを
  書き出す。確認は `xdg-mime query default x-scheme-handler/keryx`（`keryx-url-handler.desktop` が
  返れば OK）。Keryx 起動中に
  `xdg-open 'keryx://oauth2/callback?code=test&state=test'` を実行してウィンドウが前面に来れば
  エンドツーエンドで動いている。なおこの 2 ファイルはユーザーのホーム配下にあり、**deb/rpm を
  アンインストールしても削除されず**、アンインストールフックも無いため除去できない。実害が無いわけではない：
  `mimeapps.list` に残った `[Default Applications]` の関連付けは、もう存在しないランチャーのパスを
  `keryx://` の既定ハンドラーとして指し続けるため、除去するまで `xdg-open`（やブラウザーのスキーム解決）が
  失敗しうる。除去は手動で行う必要があり、アプリケーションディレクトリの `keryx-url-handler.desktop` を
  削除し、`mimeapps.list` から `x-scheme-handler/keryx` の行を取り除く。
