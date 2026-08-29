# 開発環境セットアップ

[English](setup.md)

## 前提

### システム要件

- **対応OS**: Windows / macOS / Linux。JVM 上で動作するため、JDK 25 以上が動く環境であれば
  基本的に開発できる。
- **Android の作業を伴う場合**（`androidApp` のビルドや実機/エミュレータでの動作確認）は、
  Android Studio の公式システム要件（64-bit OS、RAM 8GB 以上、空きディスク 8GB 以上が目安。
  エミュレータを併用する場合はさらに余裕を見る）に準拠する。正確な最新の値は
  [Android Studio のシステム要件](https://developer.android.com/studio/install)を参照。
- **デスクトップターゲットのみ**を触る場合は、JDK と Gradle Wrapper が動く程度の環境で十分
  （Android SDK やエミュレータ分の追加リソースは不要）。

### 推奨IDE

- **[Android Studio](https://developer.android.com/studio)**:
  IntelliJ ベースで Android 開発をフルサポートするのはもちろん、Compose Multiplatform の
  **デスクトップターゲットも Run Configuration から直接実行できる**。デスクトップと Android
  の両方を1つの無料 IDE でカバーできるため、本プロジェクトでは第一候補。SDK Manager が同梱
  されているため、後述の Android SDK もここから導入できる。
- **[IntelliJ IDEA Community Edition](https://www.jetbrains.com/idea/download/)**: Gradle ベースの Kotlin
  Multiplatform プロジェクトとして問題なく開閲・ビルド・実行でき、デスクトップターゲットの
  開発には十分。JetBrains の
  [「Kotlin Multiplatform」プラグイン](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform)
  は Community Edition でも導入できるが、その主眼は iOS 向けのプレビュー/実行/デバッグ支援
  であり、本プロジェクトは iOS 未対応（[external-spec.ja.md](external-spec.ja.md) §2 参照）
  のため現時点では必須ではない。Android 実機/エミュレータへのデプロイやレイアウトプレビュー
  など Android 特化のツールは Android Studio に比べて弱いので、Android 側の作業が中心なら
  Android Studio を使う方がよい。
- **[Visual Studio Code](https://code.visualstudio.com/)**: JetBrains 公式の "Kotlin by JetBrains" 拡張は2026年に Alpha 公開されたが、
  Kotlin Multiplatform プロジェクトは現時点で非対応と明言されている。本プロジェクトの開発
  には現状推奨できない。

### ビルドに必要なソフトウェア

どのターゲットでも共通して必要なものと、Android ターゲット固有のものに分かれる。

#### 共通

| ソフトウェア | 用途 | 導入方法 |
| --- | --- | --- |
| **JDK 25 以上**（`JAVA_HOME`） | `./gradlew` を起動する JVM | macOS: `brew install temurin@25`。Windows: `winget install EclipseAdoptium.Temurin.25.JDK` または[公式インストーラー](https://adoptium.net/installation/)。Linux: ディストリのパッケージ（例: Adoptium の apt リポジトリ経由で `sudo apt install temurin-25-jdk`）や[SDKMAN!](https://sdkman.io/)（`sdk install java 25-tem`）など |
| **Git** | リポジトリの取得 | 各 OS 標準の方法（[git-scm.com](https://git-scm.com/downloads)、macOS の Xcode Command Line Tools、ディストリのパッケージ等） |
| Gradle | ビルド実行 | 同梱の Wrapper（`./gradlew`、Gradle 9.6.1）を使うため**別途インストール不要** |

#### Android ターゲット向け

- **Android SDK Platform 37**（`compileSdk` / `targetSdk`）と build-tools。Android Studio の
  SDK Manager（推奨。現在のパッケージ名を自動で解決してくれる）、またはコマンドラインツール単体
  （[`cmdline-tools`](https://developer.android.com/tools/sdkmanager)）で導入する。API レベル37
  以降、Google はプラットフォームをマイナーリビジョン単位で公開するようになった
  （`platforms;android-37.0`、`.1` など）ため、フラットな `platforms;android-37` というパッケージ
  ID はもう存在せず、`sdkmanager platforms;android-37` は "Failed to find package" で失敗する。
  `sdkmanager --list | grep android-37` で現在の ID を確認するか、初回ビルド時に AGP 自身の
  SDK 自動ダウンロードに解決させればよい。`build-tools;36.0.0` はこの影響を受けず、そのまま
  導入できる（`sdkmanager "build-tools;36.0.0"` — 36.0.0 は AGP 9.3.2 が既定で選択するバージョン）。
- 初期設定: `local.properties` の `sdk.dir` に SDK の場所を指定する（AGP がこのキー自体を
  直接読むため、下記 OAuth キーで使う `-P`/環境変数/`local.properties` の解決チェーンとは
  別系統）か、環境変数 `ANDROID_HOME` を設定してもよい。`:composeApp:compileKotlinDesktop` や
  `:composeApp:desktopTest` のようなターゲット限定タスクは SDK が無くても動くが、ルートの
  `./gradlew build` は `:androidApp` を含む全サブプロジェクトを集約するため、SDK が解決できない
  と設定段階で即座に失敗する — 詳細は後述の「よくある問題」を参照。
- **Android リリース署名キーストア（任意）**: Gradle の既定 `build` ライフサイクルは
  `:androidApp` の `assembleRelease` を含んでおり（App Bundle は含まれない —
  `:androidApp:bundleRelease` は `release.yml` のように明示的に叩く必要がある）、
  `androidApp/build.gradle.kts` は署名情報が無いときに debug 署名へフォールバックしない設計に
  なっている（debug 署名の release 成果物はインストール可能で本物に見えてしまうため、これこそ
  危険なケース）。その代わり、**キーストアを用意していなくてもルートの `./gradlew build` は成功する**
  が、`:androidApp` の release APK は**未署名**になる（ビルド警告が出る） — その APK は実機に
  インストールも Google Play へのアップロードもできない。実機で動かす/配布するつもりがある
  場合にのみ用意すればよく、ローカル検証には JDK 同梱の `keytool` でその場限りのキーストアを
  作れば十分:

  ```bash
  keytool -genkeypair -v -keystore "$PWD/keryx-dev.keystore" \
    -alias keryx-dev -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Dev, OU=Dev, O=Dev, L=Dev, S=Dev, C=US" \
    -storepass changeit -keypass changeit
  ```

  生成したファイルの**絶対パス**を `local.properties` の `android.release.keystore.path` /
  `android.release.keystore.password` / `android.release.key.alias` /
  `android.release.key.password` に設定する（相対パスは `androidApp` モジュール基準に解決
  される）。4つは常にセットで必要 — 一部だけ設定するのは常に設定ミスであり、未署名で
  静かに進んだり、不完全な署名情報のまま進んだりせず、即座にビルドが失敗する。`.gitignore` は
  `*.keystore` / `*.jks` を除外済みなので、リポジトリ直下に置いても誤ってコミットされることは
  ない。Google Play 配布用の本番キーストアの発行手順は [build.md](build.md) を参照。
- **実機または起動中の Android エミュレータ**: `androidDeviceTest` 計装スイート
  （`DatabaseMerger`/`DatabaseSnapshot` の Android 実装を実際のバンドル SQLite に対して検証
  する。[testing.ja.md](testing.ja.md) 参照）を実行する場合にのみ必要。ビルド・
  `./gradlew build`・その他のテストタスクはいずれも実機/エミュレータ無しで動く。Linux で
  エミュレータを実用的な速度で動かすには **KVM**（ハードウェアアクセラレーション）が必要 —
  設定方法は[公式ガイド](https://developer.android.com/studio/run/emulator-acceleration)を
  参照。
- **NDK は不要**（プロジェクト内でネイティブコードのビルドは行っていない。誤って導入しない
  よう注意）。

#### Linux 固有

- **Xvfb**: ヘッドレス環境（SSH セッション、コンテナ、CI 等）で `./gradlew build` を通すのに
  必須。`runDesktopComposeUiTest` が実際の Skia/AWT 描画を行うためディスプレイが必要になる。
  例: `sudo apt-get install -y xvfb` の上で
  `xvfb-run -a --server-args="-screen 0 1920x1080x24" ./gradlew build`。

### アプリの実行に必要なソフトウェア

ビルド自体は通っても、パッケージ版アプリや `:composeApp:run` を実際に**起動**するときにだけ
必要になるランタイム。

- **Linux: WebKitGTK**（`libwebkit2gtk-4.1-0`。Debian/Ubuntu 系。Fedora 系は
  `webkit2gtk4.1`）: 記事リーダーのネイティブ WebView（[app-architecture.ja.md](app-architecture.ja.md)
  参照）が使用する。ビルドやテストには不要 — `ArticleDetailPaneTest` は実際の WebView を
  スタブに差し替えているため、この依存が無くてもビルド・テストは通る。
- **Windows: WebView2 ランタイム**: Windows 11、および更新済みの Windows 10 には Microsoft
  Edge 経由で標準搭載済み。無い場合のみ
  [Evergreen ランタイム](https://developer.microsoft.com/microsoft-edge/webview2/)を個別に
  導入する。
- **Linux: D-Bus セッションバス**（任意）: トレイ（StatusNotifierItem）とデスクトップ通知に
  使う。無い環境では自動的に AWT ベースのトレイにフォールバックするため必須ではない。
- **macOS**: 追加ソフトウェア不要（WebView は OS 標準の WebKit を使う）。

### パッケージングに必要なソフトウェア

`:composeApp:run` は JDK に加えて前述「アプリの実行に必要なソフトウェア」に挙げた各プラット
フォームのランタイムが必要（ヘッドレスな Linux 環境での Xvfb は除く）。ルートの
`./gradlew build` は前述のとおり Android SDK も追加で必要になる。ネイティブパッケージング系
タスク（`createDistributable`, `packageDmg`, `packageMsi`, `packageDeb`, `packageRpm` —
詳細は [build.md](build.md)）は OS ごとに以下も必要。

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

## 初回

```bash
git clone <repo>
cd keryx
cp local.properties.example local.properties   # sdk.dir を追記する（前提を参照）。OAuth キーは任意

# Android のリリース署名キーストア（任意 — 無くても ./gradlew build は成功するが、
# :androidApp の release APK は未署名になる。実機で動かす/配布する場合のみ必要。
# 詳細は前提の「ビルドに必要なソフトウェア」参照）
keytool -genkeypair -v -keystore "$PWD/keryx-dev.keystore" \
  -alias keryx-dev -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Dev, OU=Dev, O=Dev, L=Dev, S=Dev, C=US" \
  -storepass changeit -keypass changeit
# local.properties に android.release.keystore.path（絶対パス）/
# android.release.keystore.password / android.release.key.alias /
# android.release.key.password を追記する

./gradlew build
```

`build` が通れば SQLDelight / Compose Resources / BuildConfig のコード生成、コンパイル、`build`
が実行するテストまで一通り確認できる — `build` は `:androidApp` のコンパイル・アセンブルも行う
ため、デスクトップと Android の両ターゲットについて確認できる。ただし別途実行する
`androidDeviceTest` 計装スイート（実機/エミュレータが必要。前提を参照）はこれに含まれない。

デスクトップの作業だけであれば、`./gradlew :composeApp:desktopTest` のようなターゲット限定
タスクを使うことで、Android SDK を用意せずに済む。

## データディレクトリ

アプリのローカルデータ（`keryx.db`, `local_settings.json`）は OS 標準の場所に作られる。

| OS | パス |
| --- | --- |
| macOS | `~/Library/Application Support/Keryx` |
| Windows | `%APPDATA%\Keryx` |
| Linux | `$XDG_DATA_HOME/Keryx`（既定 `~/.local/share/Keryx`） |

開発中にデータを初期化したい場合はこのディレクトリの `keryx.db` と `local_settings.json` を削除する。

## よくある問題

### `SDK location not found`（Gradle の設定段階）

`composeApp` 自体が Android ライブラリターゲット（`com.android.kotlin.multiplatform.library`）
を構成するようになったため、その `build` ライフサイクルに触れるタスク——ルートの
`./gradlew build`、あるいは `:composeApp:build` 単体でも——は `:androidApp` だけでなく
Android SDK を必要とする。

`local.properties` の `sdk.dir`（前提を参照）か環境変数 `ANDROID_HOME` を設定する。デスクトップ
だけの作業なら `:composeApp:compileKotlinDesktop` や `:composeApp:desktopTest` のような特定
タスクに絞ることで Android SDK の解決を避けられる。

### Android のリリースビルドが未署名になる

Gradle の既定 `build` ライフサイクルは `:androidApp` の `assembleRelease` を含んでおり、
release APK が生成される（App Bundle は生成されない — `:androidApp:bundleRelease` を明示的に
叩く必要がある）。Android リリース署名キーストアを設定していない場合、
`androidApp/build.gradle.kts` はビルド警告を出したうえで**未署名**の release APK
（`androidApp-release-unsigned.apk`）を生成する — これはインストール可否だけに関わる話なので、
`./gradlew build` 自体は（デスクトップの作業しかしていなくても）成功する。未署名 APK は実機に
インストールすることも Google Play にアップロードすることもできない。

実機にインストールできる本物の release ビルドが必要なら、前提の「ビルドに必要な
ソフトウェア」に沿って開発用キーストアを生成し、`local.properties` に4つの値
（`android.release.keystore.path` / `android.release.keystore.password` /
`android.release.key.alias` / `android.release.key.password`）を設定する。**4つのうち一部だけ
設定するのは常に設定ミス** — 未署名で静かに進んだり不完全な署名情報のまま進んだりせず、
どの値が足りないかを示して即座にビルドが失敗する。

### `UnsupportedClassVersionError`（実行時エラー）

`./gradlew` を起動した JVM が 25 未満。`JAVA_HOME` を JDK 25+ に設定する。

### ツールチェーンのダウンロードがブロックされる場合

`-Dorg.gradle.java.installations.auto-download=true` を付ける。

### （Linux）ヘッドレス環境で `./gradlew build` が Compose UI テストで固まる/失敗する

`runDesktopComposeUiTest` は実際の Skia/AWT 描画を行うためディスプレイが必要（前提の
「ビルドに必要なソフトウェア」も参照）。

CI は仮想 X サーバー（`sudo apt-get install -y xvfb` の上で
`xvfb-run -a --server-args="-screen 0 1920x1080x24" ./gradlew build`）で実行している。ディスプレイ
の無い環境（SSH セッション、コンテナ等）ではローカルでも同様にする。

### Dropbox 連携が表示されない

`DROPBOX_APP_KEY` が未設定（仕様どおり非表示）。`build.md` を参照。

### （Android エミュレータ）Dropbox / OneDrive 連携で画面は開くがタップに反応しない

AVD に実用的なブラウザーが入っておらず、暗黙的な `ACTION_VIEW` インテントが Chrome ではなく
**WebView Browser Tester**（WebView の動作確認用アプリで、実用的なブラウザーではない）に解決
されてしまっている。OAuth のページ自体は表示できるが、操作には正しく反応しない。

AVD を**Google Play** 搭載のシステムイメージ（"Google APIs" のみのものは Play Store を含まず
Chrome も無いため不可）で作り直すか、既存の AVD に実ブラウザーの APK を追加インストールする。

### `./gradlew :composeApp:run` で Dropbox / OneDrive 連携が完了しない（全デスクトップ OS 共通）

これらのリダイレクト URI はカスタムスキーム `keryx://` で、接続ボタンが disabled のまま
タイムアウトする。理由は OS ごとに異なる:

- **macOS**: LaunchServices が `keryx://` を**パッケージ版 `Keryx.app`**（Info.plist の
  `CFBundleURLTypes`）にルーティングし、`gradlew run` のインスタンスには届かない。
- **Windows / Linux**: スキーム登録は起動時に行われるが、パッケージ版ランチャーからの起動時
  のみ——JDK の `java` バイナリを OS に登録すると、Gradle 実行終了後も壊れたハンドラーが
  残ってしまうため。

**連携を確認・実施する場合は、`./gradlew :composeApp:createDistributable` でビルドしたアプリ
（`composeApp/build/compose/binaries/main/app/` 以下）を起動して行う**こと（gradle 実行中
インスタンスは先に終了しておく）。連携で保存したトークンは keychain またはデータディレクトリ
の `.dropbox_tokens.json` に格納される。Google Drive はループバック受信なので `gradlew run`
でも連携できる。

### （Linux）ブラウザーが `keryx://` で「不明なプロトコル」エラーを出す

スキームがデスクトップ環境に登録されていない。パッケージ版は初回起動時に登録し、
`$XDG_DATA_HOME/applications/keryx-url-handler.desktop`（既定
`~/.local/share/applications/keryx-url-handler.desktop`）と
`$XDG_CONFIG_HOME/mimeapps.list`（既定 `~/.config/mimeapps.list`）の関連付けを書き出す。

確認手順:

1. `xdg-mime query default x-scheme-handler/keryx` を実行し、`keryx-url-handler.desktop` が
   返ることを確認する。
2. Keryx 起動中に `xdg-open 'keryx://oauth2/callback?code=test&state=test'` を実行し、
   ウィンドウが前面に来ればエンドツーエンドで動いている。

なおこの2ファイルはユーザーのホーム配下にあり、**deb/rpm をアンインストールしても削除されず**、
アンインストールフックも無いため除去できない。実害が無いわけではない: `mimeapps.list` に
残った `[Default Applications]` の関連付けは、もう存在しないランチャーのパスを `keryx://` の
既定ハンドラーとして指し続けるため、除去するまで `xdg-open`（やブラウザーのスキーム解決）が
失敗しうる。

除去は手動で行う必要があり、アプリケーションディレクトリの `keryx-url-handler.desktop` を
削除し、`mimeapps.list` から `x-scheme-handler/keryx` の行を取り除く。
