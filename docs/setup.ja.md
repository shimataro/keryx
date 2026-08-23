# 開発環境セットアップ

[English](setup.md)

## 前提

- **JDK 25 以上**（`JAVA_HOME`）。macOS なら Temurin / Homebrew の openjdk 等。
- IDE: IntelliJ IDEA / Android Studio（Kotlin Multiplatform プラグイン）推奨。
- **Android SDK Platform 37**（`compileSdk`）と build-tools。Android Studio の SDK Manager か
  `sdkmanager` で導入する。`local.properties` の `sdk.dir` に SDK の場所を指定する（AGP がこのキー
  自体を直接読むため、下記 OAuth キーで使う `-P`/環境変数/`local.properties` の解決チェーンとは別系統）か、
  環境変数 `ANDROID_HOME` を設定してもよい。`:composeApp:compileKotlinDesktop` や
  `:composeApp:desktopTest` のようなターゲット限定タスクは SDK が無くても動くが、ルートの
  `./gradlew build` は `:androidApp` を含む全サブプロジェクトを集約するため、SDK が解決できないと
  設定段階で即座に失敗する — 詳細は後述の「よくある問題」を参照。
- **実機または起動中の Android エミュレータ**は `androidDeviceTest` 計装スイート
  （`DatabaseMerger`/`DatabaseSnapshot` の Android 実装を実際のバンドル SQLite に対して検証する。
  [testing.ja.md](testing.ja.md) 参照）を実行する場合にのみ必要。ビルド・`./gradlew build`・その他の
  テストタスクはいずれも実機/エミュレータ無しで動く。

## 初回

```bash
git clone <repo>
cd kmp
cp local.properties.example local.properties   # sdk.dir を追記する（前提を参照）。OAuth キーは任意
./gradlew build
```

`build` が通れば SQLDelight / Compose Resources / BuildConfig のコード生成、コンパイル、テストまで
一通り確認できる — `build` は `:androidApp` のコンパイル・アセンブルも行うため、デスクトップと
Android の両ターゲットについて確認できる。

## データディレクトリ

アプリのローカルデータ（`keryx.db`, `local_settings.json`）は OS 標準の場所に作られる。

| OS | パス |
| --- | --- |
| macOS | `~/Library/Application Support/Keryx` |
| Windows | `%APPDATA%\Keryx` |
| Linux | `$XDG_DATA_HOME/Keryx`（既定 `~/.local/share/Keryx`） |

開発中にデータを初期化したい場合はこのディレクトリの `keryx.db` と `local_settings.json` を削除する。

## パッケージング前提条件

`:composeApp:run` は JDK 以外に何も要らない（ヘッドレスな Linux 環境での Xvfb は除く — 後述）。
ルートの `./gradlew build` は前提のとおり Android SDK の解決も追加で必要になる。
ネイティブパッケージング系タスク（`createDistributable`, `packageDmg`, `packageMsi`,
`packageDeb`, `packageRpm` — 詳細は [build.md](build.md)）は OS ごとに以下も必要。

- **Linux**
  - `fakeroot` — `packageDeb` に必要（jpackage が `.deb` 生成のために呼び出す）
  - `rpm`（`rpmbuild` を含む） — `packageRpm` に必要
- **macOS**
  - Xcode Command Line Tools（`xcode-select --install`。`SetFile` 用） — `packageDmg` に必要
    （DMG のボリュームアイコン設定。`hdiutil` 自体は OS 標準）
- **Windows**
  - WiX Toolset v3 / v4 / v5（`PATH` に追加） — `packageMsi` に必要
    （jpackage の Windows インストーラー生成）。GitHub ホストの `windows-latest` ランナーには
    WiX Toolset v3.14.1 がプリインストール済みのため、`ci.yml` と `release.yml` のどちらも
    追加のインストール手順なしでビルドできる。詳細は `build.md` 参照

`fakeroot`/`rpm` は `ubuntu-latest` に既定で入っていない。リリース
ワークフローはパッケージング直前に `apt-get` で `fakeroot rpm` をインストールしている
（`.github/workflows/release.yml`）。Xcode Command Line Tools と WiX Toolset は
`macos-latest` / `windows-latest` の各ランナーイメージにそれぞれプリインストール済み —
ローカルの開発機ではこの3つのうち足りないものを手動でセットアップする必要がある。

## よくある問題

- **`SDK location not found`（Gradle の設定段階）**: `composeApp` 自体が Android ライブラリ
  ターゲット（`com.android.kotlin.multiplatform.library`）を構成するようになったため、その
  `build` ライフサイクルに触れるタスク——ルートの `./gradlew build`、あるいは `:composeApp:build`
  単体でも——は `:androidApp` だけでなく Android SDK を必要とする。`local.properties` の
  `sdk.dir`（前提を参照）か環境変数 `ANDROID_HOME` を設定する。デスクトップだけの作業なら
  `:composeApp:compileKotlinDesktop` や `:composeApp:desktopTest` のような特定タスクに絞ることで
  Android SDK の解決を避けられる。
- **`UnsupportedClassVersionError`（実行時）**: `./gradlew` を起動した JVM が 25 未満。
  `JAVA_HOME` を JDK 25+ に設定する。
- **ツールチェーンのダウンロードがブロックされる**:
  `-Dorg.gradle.java.installations.auto-download=true` を付ける。
- **（Linux）ヘッドレス環境で `./gradlew build` が Compose UI テストで固まる/失敗する**:
  `runDesktopComposeUiTest` は実際の Skia/AWT 描画を行うためディスプレイが必要。CI は仮想 X サーバー
  （`sudo apt-get install -y xvfb` の上で
  `xvfb-run -a --server-args="-screen 0 1920x1080x24" ./gradlew build`）で実行している。ディスプレイの
  無い環境（SSH セッション、コンテナ等）ではローカルでも同様にする。
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
