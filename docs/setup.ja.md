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
- **（macOS）`./gradlew :composeApp:run` で Dropbox 連携が完了しない**: 連携のリダイレクト URI は
  カスタムスキーム `keryx://` で、macOS の LaunchServices はこれを**パッケージ版 `Keryx.app`**（Info.plist の
  `CFBundleURLTypes`）にルーティングする。`gradlew run` で起動したインスタンスには届かず、接続ボタンが
  disabled のままタイムアウトする（Windows/Linux は URL がコマンドライン引数で渡るため問題ない）。
  **macOS で連携を確認・実施する場合は、`./gradlew :composeApp:createDistributable` でビルドした
  `Keryx.app`（`composeApp/build/compose/binaries/main/app/` 以下）を起動して行う**こと
  （gradle 実行中インスタンスは先に終了しておく）。連携で保存したトークンは keychain またはデータ
  ディレクトリの `.dropbox_tokens.json` に格納される。
