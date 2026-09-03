# ビルド・パッケージング

[English](build.md)

## 必要環境

- **JDK 25 以上**（`JAVA_HOME` が指す、`./gradlew` を起動する JVM）。
  コンパイル用ツールチェーン（JDK 25）は Gradle の foojay-resolver が自動取得する。
  ただし `:composeApp:run` などの JavaExec タスクは Gradle を起動した JVM で実行されるため、
  それが 25 未満だと実行時に `UnsupportedClassVersionError` になる。
- Gradle は同梱の wrapper（`./gradlew`, Gradle 9.6.1）を使う。
- **Android SDK**（`local.properties` の `sdk.dir`、または環境変数 `ANDROID_HOME`） —
  `:composeApp` 自体が Android library ターゲットを構成しているため、デスクトップ側だけの変更
  であってもルートの `./gradlew build` には SDK の解決が必要。インストールと AVD の作成は
  [setup.ja.md](setup.ja.md) を参照。`:composeApp:compileKotlinDesktop`/`:composeApp:desktopTest`
  のようなデスクトップ限定タスクはこの要件を回避できる。

サンドボックス等でツールチェーンの自動ダウンロードが必要な場合:
`./gradlew -Dorg.gradle.java.installations.auto-download=true ...`。

## ビルド・実行

```bash
./gradlew build                       # 全ソースセットのコンパイル + テスト
./gradlew :composeApp:desktopTest     # テストのみ
./gradlew :composeApp:run             # デスクトップアプリを起動

./gradlew :androidApp:assembleDebug        # デバッグ APK をビルド
./gradlew :androidApp:installGithubDebug   # ビルドして接続中の実機/エミュレータへインストール
```

`:androidApp` には `distribution` プロダクトフレーバー次元があるため（後述「Android（APK / AAB）」）、
インストールはバリアント単位のタスクになる —— `installDebug` は存在せず、デバッグバリアントは
`githubDebug` だけ（`playDebug` は無効化済み）。`assembleDebug` は有効なデバッグバリアントを束ねる
集約タスクとして残っているので、上記のままで動く。

## クラウドストレージとの連携

ビルド時にAPIキーを指定することで、クラウドストレージと連携（同期）ができるようになる。

プロパティーの値は[local.properties.example](../local.properties.example)を参照。
ビルド時にはこのファイルを `local.properties` にコピーして編集すること。

APIキーが指定されていないクラウドサービスは連携機能が表示されず、どのサービスにも指定されなければ（設定ダイアログのタブなどに）連携機能自体が表れない。
**連携できるクラウドストレージは同時に1つのみ**であり、複数のストレージに分散保存はできない。

Gradle のカスタムタスク（`generateBuildConfig`）で実現している。

以下に各サービスでのAPIキーの取得方法を示す。

### Dropbox

1. [DBX Platform](https://www.dropbox.com/developers/apps/create)で連携先アプリを作成
  - すでに作成済みの場合は[App Console](https://www.dropbox.com/developers/apps)から検索
  - "Choose an API": `Scoped access`
  - "Choose the type of access you need": `App folder`
  - これはドライブ内の任意のファイルではなく、アプリ専用フォルダーへのみアクセスを許可する
2. "Settings" で以下を設定
  - "Redirect URIs": `keryx://oauth2/callback`
  - "Allow public clients (Implicit Grant & PKCE)": `Allow`
3. "Permissions" で以下をチェック
  - `files.content.write`
  - `files.content.read`
4. "Settings" 内の "App key" を `local.properties` に指定

### Google Drive

1. [Google Cloudコンソール](https://console.cloud.google.com)でプロジェクトを作成
2. メニューの「API とサービス」→「ライブラリ」と辿り、「Google Drive API」を探す
  - 検索ボックスに "drive" と入れるか、メニューの「カテゴリ」から「ストレージ」で絞り込み
  - 「有効にする」をクリック
3. メニューの「Google Auth プラットフォーム」→「データアクセス」と辿る（旧「OAuth 同意画面」から名称・構成が変更された）
  - 「スコープを追加または削除」をクリック
  - "Google Drive API" の `.../auth/drive.appdata` をチェック
  - 「更新」をクリックして選択を確定し、データアクセス画面で「保存」をクリックして反映する
  - これはドライブ内の任意のファイルではなく、アプリ専用フォルダーへのみアクセスを許可する
4. メニューの「Google Auth プラットフォーム」→「クライアント」と辿り、クライアントを作成
  - 上部の「クライアントを作成」
  - アプリケーションの種類: 「デスクトップアプリ」
  - 同画面内の「クライアント ID」と「クライアント シークレット」を `local.properties` に指定

※OAuth2認可後のリダイレクト先はDropboxのように任意に決められないため、 `http://127.0.0.1:<ポート>` のループバックで受ける（アプリ側は `LoopbackRedirectTransport` で一時 HTTP サーバーを立てて受信する）。
※フローはPKCE（`code_verifier`）を使うが、**クライアントシークレットは別途必要**。
※開発中は「オーディエンス」タブで公開ステータスを「テスト」にしてテストユーザーを登録すれば事足りる。

> [!IMPORTANT]
> **「テスト」ステータスのままだとリフレッシュトークンが7日で失効する。** OAuth同意画面の公開ステータスを
> 「テスト」のままにしていると、Googleは発行から7日で失効するリフレッシュトークンを発行するため、Google
> Driveの同期接続はおよそ週1回の再連携が必要になる（アプリ側は `CloudAuthException` の通知センター表示として
> 表面化し、サイレントに失敗するわけではない）。長期運用する場合は「オーディエンス」タブで公開ステータスを
> 「本番環境」に切り替える必要がある（`drive.appdata` は非機微スコープなので、公開にあたってGoogleの
> 機微・制限付きスコープの審査は一切不要。同意画面にアプリ名やロゴを表示したい場合のみ、任意で
> 軽量な「ブランド確認」を行えばよい）。

### OneDrive

1. [Azure Portal](https://portal.azure.com) →「Microsoft Entra ID」→「アプリの登録」→「新規登録」でアプリを登録
   - 「サポートされているアカウントの種類」は「個人用 Microsoft アカウントのみ」を選ぶ。
     これは `core/Constants.kt` の `ONEDRIVE_AUTHORIZE_ENDPOINT`/`ONEDRIVE_TOKEN_ENDPOINT` に
     ハードコードされた **`consumers` テナントセグメントと対になっている**。Microsoft は
     `Consumer` audience の登録による `/common` エンドポイント利用を拒否し、しかもその拒否は
     ユーザーがメールアドレスを送信した後に返るため、不一致は汎用の「認証に失敗しました」として
     現れる。一方だけを変更しないこと。職場・学校アカウントは意図的に非対応で、下記の
     `Files.ReadWrite.AppFolder` が個人用アカウント限定の Graph 権限であるため
     （[sync-architecture.ja.md](sync-architecture.ja.md) 参照）。
2. 「認証」→「プラットフォームを追加」→ **「モバイル アプリケーションとデスクトップ アプリケーション」**:
   - 「カスタム リダイレクト URI」に `keryx://oauth2/callback` を追加する。
   - 「パブリック クライアント フローを許可する」を **はい** にする（OneDrive は PKCE パブリッククライアントで、クライアントシークレットは不要）。
3. 「API のアクセス許可」→「アクセス許可の追加」→「Microsoft Graph」→「委任されたアクセス許可」で **`Files.ReadWrite.AppFolder`** を追加する（ドライブ内の任意のファイルではなく、アプリ専用フォルダーへのみアクセスを許可する）。リフレッシュトークン用の `offline_access` は実行時に要求する。
4. 「概要」の「アプリケーション (クライアント) ID」を `local.properties` の `onedrive.client.id` に指定する。

OneDrive は Dropbox と同じカスタム URI スキーム（`keryx://oauth2/callback`、`state` で識別）を再利用するため、追加の OS 登録は不要。**クライアントシークレットは不要**（Google と異なり、Microsoft は「モバイル/デスクトップ」登録を PKCE の完全なパブリッククライアントとして扱う）。同期 DB は OneDrive のアプリ専用フォルダー（`/me/drive/special/approot`）に保存される。Dropbox 同様、macOS では `keryx://` がパッケージ済みアプリへルーティングされるため `./gradlew :composeApp:run` では連携が完了しない。macOS で検証するには `createDistributable` で `Keryx.app` をビルドして起動する。

### Android

Android が対応するのは **Dropbox と OneDrive のみ** — 設定方法は上記と同じく、`local.properties`
の `dropbox.app.key` / `onedrive.client.id`（またはそれぞれの環境変数 `DROPBOX_APP_KEY` /
`ONEDRIVE_CLIENT_ID`）を設定する。Google Drive のキーは Android ビルドには
影響しない。**Google Drive が Android で提供されないのは**、そのデスクトップ用 OAuth 構成
（loopback リダイレクト + `client_secret`）を Android では再利用できないため —
背景となる調査は `external-spec.md` §4 と `sync-architecture.md` の "Google Drive on Android"
を参照。

デスクトップでは `keryx://` の受け口に OS レベルの登録手順が必要だった（上記の各サービスの
説明を参照）のに対し、Android は `androidApp/src/main/AndroidManifest.xml` 内のマニフェスト
宣言だけで `keryx://oauth2/callback` のリダイレクトを受け取れる（`scheme="keryx"`
`host="oauth2"` の `ACTION_VIEW` インテントフィルター）。そのため、上記デスクトップの
`./gradlew :composeApp:run` のようなパッケージ済み/未パッケージの区別は無い。エミュレータで
連携を検証するには、OAuth フローを完了させる実用的なブラウザーが必要——それを得る推奨手段が
Google Play イメージ（Chrome 入り）— [setup.ja.md](setup.ja.md) を参照。

## パッケージング

[`composeApp/build/compose/binaries/main`](./composeApp/build/compose/binaries/main)以下に作成される

実行するプラットフォームで動くもののみ作成可（クロスコンパイル不可）

```bash
# 実行プラットフォーム依存の実行フォルダ
./gradlew :composeApp:createDistributable

# macOS
./gradlew :composeApp:packageDmg

# Windows
./gradlew :composeApp:packageMsi

# Linux
./gradlew :composeApp:packageDeb
./gradlew :composeApp:packageRpm
```

### Linux Snap パッケージ

`.deb`/`.rpm`（上記タスクでjpackage経由でビルド）と異なり、Snapは`snap/snapcraft.yaml`から
`snapcraft`で直接ビルドする。同じ`createDistributable`のアプリイメージ
（`composeApp/build/compose/binaries/main/app/Keryx`）を再ビルドせずそのまま`dump`するため、
先に`createDistributable`を実行しておく必要がある:

```bash
./gradlew :composeApp:createDistributable
sudo snap install snapcraft --classic   # 未インストールの場合
sudo env "PATH=$PATH" snapcraft pack --destructive-mode
```

`--destructive-mode`はサンドボックスなしでホスト上に直接ビルドするため、ホスト自体が
`snap/snapcraft.yaml`の`base: core24`（Ubuntu 24.04）に一致している必要があり、
root権限も必要になる——さらにホスト環境を変更してしまう可能性がある。CI（`release.yml`）は
既に一致する`ubuntu-latest`ランナー上でこれを実行している。別のホストでローカルビルドする
場合は、代わりに分離されたLXDコンテナ内でビルドする`snapcraft pack --use-lxd`を使うこと——
事前にLXDをインストール・初期化し、現在のユーザーからアクセスできる状態にしておく必要がある:

```bash
sudo snap install lxd
sudo usermod -a -G lxd "$USER" && newgrp lxd   # newgrpの代わりにログアウト・再ログインでも可
sudo lxd init --auto
snapcraft pack --use-lxd
```

`confinement: strict`（UbuntuのSnap Store配布時の既定）は、`snap/snapcraft.yaml`の
`apps.keryx.plugs`で宣言したプラグのみをアプリに与える —
`network`、`password-manager-service`（Secret Service、`java-keyring`のトークン保存用 —
snapdのポリシー上**自動接続されない**ため、Secret Serviceに実際にアクセスできるように
なるには利用者が事前に`snap connect keryx:password-manager-service`を実行する必要がある。
接続するまでは、OSのセキュアストアが使えない場合に他のプラットフォームでもすでに使っている
権限制限付きの平文フォールバックファイルへ`java-keyring`がフォールバックする。`SECURITY.md`
参照）、`desktop`/`desktop-legacy`/`wayland`/`x11`（ウィンドウ・トレイ・通知の統合）、`opengl`
（Compose DesktopのSkiaレンダリング）、`home`。

`home`は、OPMLインポート/エクスポートのファイル選択ダイアログ（`JFileChooser`、
`app-architecture.md`参照）がユーザーのホームディレクトリ配下の非隠しファイルへ
アクセスするためのものである — ただし隠しファイル・隠しディレクトリへのアクセスは
明示的に除外されるため、上述の`keryx://` URIスキームと`.opml`関連付けの自己登録
（`LinuxUriSchemeRegistrar`/`LinuxOpmlAssociationRegistrar`。実際には
`~/.local/share/applications`と`~/.config/mimeapps.list`に書き込む）は、strict confinement下
では**機能しない**。これらの書き込みは拒否され、クラッシュはせずに警告としてログに
握りつぶされるだけである。Snap版のホスト側登録は代わりに`snap/gui/keryx.desktop`自体が
`x-scheme-handler/keryx`と`.opml`のMIMEタイプ両方に対する`MimeType=`と`Exec=keryx %u`という
フィールドコードを宣言することで行っている — これはsnapdがインストール時に処理する仕組みである。
一部のデスクトップ環境がローカルファイルを`%u`経由で`file://` URIとして渡してくる場合に備え、
`main()`内で分類前にプレーンなパスへ正規化している（`normalizeFileUriArg`）。これらのプラグが
strict confinement下で実際に十分か（特にトレイのD-Bus所有権）、およびこのデスクトップエントリ
による登録が実機のsnapd環境で実際に機能するかは、まだ検証していない。

### Android（APK / AAB）

上記のデスクトップパッケージと違い、APK/AAB は**どの OS からでも**ビルドできる —
クロスコンパイルの制約は無い。

`androidApp` は `distribution` という次元で `github` と `play` という 2 つの product flavor に
分かれている（`applicationId` は同一）。両者が異なるのはただ 1 点だけ:
`androidApp/src/github/AndroidManifest.xml` が `REQUEST_INSTALL_PACKAGES` を宣言しており、これは
アプリ内アップデートのインストーラーが `PackageInstaller` セッションを張るために必要
（[background-update.ja.md](background-update.ja.md) の「アプリ内アップデート」参照）。`play`
flavor のマニフェストはこの権限を含まない — Play は既にアプリ自身を更新してくれるうえ、Play の
ポリシーはこの権限を「他のアプリのインストールを主目的とするアプリ」に限定しているため。
`composeApp`（KMP ライブラリモジュール）には flavor 次元が無く、両 flavor から同一に消費される。

```bash
./gradlew :androidApp:assembleGithubRelease -PappVersion=1.2.3   # APK（GitHub Releases）
./gradlew :androidApp:bundlePlayRelease     -PappVersion=1.2.3   # AAB（Play Store 提出用の形式）
```

出力先はそれぞれ `androidApp/build/outputs/apk/github/release/` と
`androidApp/build/outputs/bundle/playRelease/`（上記デスクトップパッケージの
`composeApp/build/compose/binaries/main` とは別の場所）。`assembleGithubRelease` は既定の `build`
ライフサイクルの集約タスク `assembleRelease`/`build` 経由で到達できる（両 flavor の release
variant をビルドする）が、`bundlePlayRelease` はどの集約ライフサイクルタスクにも含まれず明示的に
実行する必要がある — 両方の使われ方は後述の「リリース（CD）」を参照。`androidApp/build.gradle.kts`
の `flavorDimensions` を触った後は、`release.yml` を書き換える前に
`./gradlew :androidApp:tasks --all | grep -i release` でこれらのタスク名と出力パスを確認すること
— これらは AGP が flavor／buildType 名から導出するものなので、リネームするとリリースタグを打った
瞬間に初めてワークフローが壊れる。

debug バリアントの `versionCode` は `appVersion` 由来ではない: `androidApp/build.gradle.kts` が
debug の出力すべてを固定値 `debugVersionCode`（2,000,000,000 — Play の上限未満で、
`MAJOR*10000 + MINOR*100 + PATCH` の畳み込みが到達し得ない大きさ）に固定している。ローカル
ビルドは `-PappVersion` を渡さないため、そのままだと `versionCode` が 1 になり、実バージョン付きの
APK が入っている端末では `installGithubDebug` がダウングレードとして拒否される。release バリアントは
従来どおり。これでも残る失敗（リリース署名の APK と debug 署名の APK は互いに置き換えられない）は
[setup.ja.md](setup.ja.md) の「よくある問題」を参照。

リリース署名は 3 つのソースから、この優先順で解決される — Gradle プロジェクトプロパティ、
環境変数、`local.properties` の順 — 4 つの値はすべて揃って初めて有効になる（一部だけの設定は
未署名/半端な署名結果へフォールバックせず即座にビルド失敗する）。ローカル用のキーストア
生成方法は [setup.ja.md](setup.ja.md) を参照:

| `local.properties` のキー | `-P` プロパティ | 環境変数 |
| --- | --- | --- |
| `android.release.keystore.path` | `androidReleaseKeystorePath` | `ANDROID_RELEASE_KEYSTORE_PATH` |
| `android.release.keystore.password` | `androidReleaseKeystorePassword` | `ANDROID_RELEASE_KEYSTORE_PASSWORD` |
| `android.release.key.alias` | `androidReleaseKeyAlias` | `ANDROID_RELEASE_KEY_ALIAS` |
| `android.release.key.password` | `androidReleaseKeyPassword` | `ANDROID_RELEASE_KEY_PASSWORD` |

3 つのソースのどれも未設定の場合、ビルド自体は成功するが release APK は**未署名**になる
（ビルド警告のみで、debug 署名へのフォールバックは無い）— CI での署名の扱いは後述の
「リリース（CD）」、この設計の理由は setup.ja.md の「ビルドに必要なソフトウェア」を参照。

アプリアイコンは `composeApp/icons/{keryx.icns, keryx.ico, keryx.png}`。トレイアイコンは
`composeApp/src/commonMain/composeResources/drawable/tray_icon*.png`。`tray_icon_outlined.png`
（白グリフ + 黒フチ）は macOS のメニューバーと Linux の SNI パネル用、`tray_icon.png`（フルカラー）は
Windows の通知領域・Linux の AWT フォールバック・ウィンドウ自身のタイトルバーアイコン用。これらは
`design/icons/make_desktop_icons.sh` で共有アートワークから生成する
（生成済みファイルはコミットしておくのが望ましい）。

アプリのストア/メニューカテゴリーは `nativeDistributions` 内でプラットフォームごとに設定している。
macOS は `appCategory = "public.app-category.news"`（`LSApplicationCategoryType`）を使う
（Apple の App Store 分類には単純な「インターネット」に相当する項目が無いため）。Linux は
`menuGroup = "Network;News;Feed;"` を使い、これは生成される `.desktop` ファイルの `Categories=`
にそのまま書き込まれる — `Network` が freedesktop.org のデスクトップメニュー仕様における該当メイン
カテゴリーで、`News` と `Feed` はそれに対応する登録済みの追加カテゴリー。Windows / jpackage には
カテゴリーの概念自体が無い（`menuGroup` はスタートメニューのフォルダ名でしかない）ため、
Windows 側は何も設定していない。

`keryx://` のカスタム URI スキームは deb/rpm パッケージでは**登録されない**。jpackage はショートカットか
ファイル関連付けを指定しない限り `.desktop` を生成せず、そのテンプレートの `Exec` 行には `%u` が付かないため、
URI がプロセスに届かないからである。代わりにアプリが初回起動時に自身を登録し（`LinuxUriSchemeRegistrar`）、
`$XDG_DATA_HOME/applications/keryx-url-handler.desktop`（既定 `~/.local/share/applications`）と
`$XDG_CONFIG_HOME/mimeapps.list`（既定 `~/.config/mimeapps.list`）の関連付けを書き出す。
これにより `createDistributable` の app image や tarball 配置もカバーされる。この 2 ファイルはユーザーの
ホーム配下にあり、**パッケージをアンインストールしても削除されず**、アンインストールフックも無い。実害が無い
わけではない：`mimeapps.list` に残った `[Default Applications]` の関連付けは、もう存在しないランチャーの
パスを `keryx://` の既定ハンドラーとして指し続けるため、除去するまで `xdg-open`（やブラウザーのスキーム解決）
が失敗しうる。除去は手動で行う必要があり、アプリケーションディレクトリの `keryx-url-handler.desktop` を削除し、
`mimeapps.list` から `x-scheme-handler/keryx` の行を取り除く。

> [!IMPORTANT]
> **カスタム URI 連携の確認**: `./gradlew :composeApp:run` ではどのデスクトップ OS でも Dropbox / OneDrive
> 連携が完了しない（macOS は `keryx://` がパッケージ版アプリにルーティングされ、Windows / Linux は起動時の
> 登録がパッケージ版ランチャー以外を意図的にスキップするため）。連携の動作確認は `createDistributable` で
> ビルドしたアプリを起動して行う（詳細は [setup.ja.md](setup.ja.md) の「よくある問題」）。

### `.opml` ファイル関連付け

`.opml` ファイルをダブルクリック（または「Keryx で開く」）すると Keryx が起動し、購読を
インポートする（`FeedRepository.importOpml`、結果は通知センターに表示 — 詳細は
[app-architecture.ja.md](app-architecture.ja.md)）。登録の仕組みは上記の `keryx://` スキームと
同様で、プラットフォームごとに以下のとおり:

- **macOS**: `CFBundleURLTypes` と同じ `infoPlist { extraKeysRawXml }` ブロック内の
  `CFBundleDocumentTypes` でビルド時に宣言する。`LSHandlerRank` は `Alternate` ではなく
  `Default` にしており、単純なダブルクリックで（「このアプリケーションで開く」サブメニューに
  追加されるだけでなく）Keryx が直接起動するようにしている。macOS には OPML 用の組み込み
  システム UTI が存在せず、サードパーティ製フィードリーダーのエコシステムでも統一されていない —
  NetNewsWire は `org.opml.opml`（OPML 自体が Apple の UTI システムより古いため、事実上の標準に
  最も近い）、Reeder は `com.reederapp.opml`、Overcast は `unofficial.opml` を使う。以前のバージョンの
  本アプリは独自の UTI（`works.merc.keryx.opml`）をエクスポートしていたが、これだと他のアプリが
  既に `.opml` 拡張子をこれらいずれかの識別子に紐付け済みの Mac では、Finder の「このアプリケーションで
  開く」メニューに Keryx が現れなくなってしまう — ファイルはその拡張子に既に紐付いている UTI の
  ほうに解決され、後から競合するエクスポート宣言をしてもその紐付けには勝てない。そのため
  `LSItemContentTypes` には既知の3識別子すべてを列挙し、（Keryx はこれらの識別子の所有者ではなく
  利用者であるため）`UTExportedTypeDeclarations` ではなく `UTImportedTypeDeclarations` で宣言する —
  これにより、ユーザーのマシン上で `.opml` に紐付いている識別子が3つのうちどれであっても
  Keryx がハンドラーとして提示される。
- **Windows**: 起動時に（`registerWindowsOpmlAssociation`）専用の `Keryx.opml` ProgID
  （`HKEY_CURRENT_USER\Software\Classes\.opml` → `Keryx.opml` → `shell\open\command`）で登録する。
  URI スキームと同じ、ユーザー単位で管理者権限不要の仕組み。
- **Linux**: 起動時に（`LinuxOpmlAssociationRegistrar`）*2つ目の* ユーザーレベル `.desktop`
  エントリ（`keryx-opml-handler.desktop`、`Exec=... %f` — URI ではなく素のローカルパス）と、
  `*.opml` グロブを `application/x-opml+xml` に対応付ける shared-mime-info パッケージ XML
  （`$XDG_DATA_HOME/mime/packages/keryx-opml.xml`）を書き出す。このMIMEタイプがディストリビューションの
  `shared-mime-info` パッケージにあらかじめ定義されている保証が無いため。macOS と同様、Linux の
  フィードリーダー間でも OPML の MIME タイプは統一されていないため、`.desktop` エントリの
  `MimeType=` にはもう一つの候補である `text/x-opml`（`OPML_MIME_TYPE_ALT`）も列挙する —
  ただしこちらは `.desktop` エントリのみで、Keryx 自身の shared-mime-info パッケージには含めない。
  こうすることで、既にインストール済みの別のリーダーのパッケージが `.opml` をこちらの MIME タイプに
  紐付けている場合でも、Keryx 自身が `.opml` に対する2つ目の競合するグロブ対応付けを主張することなく、
  Keryx を候補として使えるようにしている。URI スキームと同じゲート: パッケージ版ランチャーからのみ
  登録するため、`./gradlew :composeApp:run` ではこれらのファイルは作成されない。`keryx://` スキームの
  `keryx-url-handler.desktop` と `mimeapps.list` エントリと同様、これらのファイルもユーザーのホーム
  配下にあり、**パッケージをアンインストールしても削除されない** — 同じ「残存関連付け」のリスク
  （存在しないランチャーを指したままのエントリ）があり、同じ手動クリーンアップが必要:
  `keryx-opml-handler.desktop` と `keryx-opml.xml` を削除し、`mimeapps.list` から
  `application/x-opml+xml` と `text/x-opml` の行を取り除く。加えて `$XDG_DATA_HOME/mime`
  （既定 `~/.local/share/mime`）に対して `update-mime-database` を再実行すること —
  `keryx-opml.xml` を削除しただけでは、データベースを再構築するまでコンパイル済みの MIME
  キャッシュが削除済みのタイプを指したままになる。
- **Android**: 上記デスクトップ3OSと異なり起動時の登録処理は一切無く、
  `androidApp/src/main/AndroidManifest.xml` 内の `MainActivity` に対する、さらに2つの
  `ACTION_VIEW` intent-filter として宣言するだけで完結する — このマニフェスト宣言だけで、
  システムの「アプリで開く」選択画面に Keryx が現れるようになる。macOS や Linux と同様、OPML には
  標準化された単一の MIME タイプが存在せず、Android のコンテンツプロバイダーは素の `.opml`
  ファイルを XML 系のタイプではなく `application/octet-stream` として報告することが多い —
  そのため MIME だけで絞り込むと実際のファイルの大半を取りこぼす。MIME ベースのフィルター
  （`application/x-opml+xml` / `text/x-opml` / `text/xml` / `application/xml` — 上記 Linux 節と
  同じ識別子）と、拡張子ベースのフォールバックフィルター（`scheme="content"` + `host="*"` +
  `mimeType="*/*"` + `pathPattern=".*\\.opml"`、報告される MIME タイプに関わらず `content://`
  URI のパスで判定する）は、**2つの独立した intent-filter** として宣言している（1つのフィルター内に
  `<data>` タグをまとめてはいない）: Android は同一 `<intent-filter>` 内にある複数の `<data>`
  要素の scheme / host / mimeType / pathPattern を、それぞれ1つの共有マッチ集合にまとめてしまう
  （`IntentFilter.matchData`）ため、いずれか1つの `<data>` タグに `pathPattern` を宣言すると、
  同じフィルター内の他の `<data>` タグの単純な MIME タイプ指定にまで、その `pathPattern` が
  暗黙に適用されてしまう — その結果、MIME タイプは一致していても `content://` のパスが
  `.opml` という拡張子で終わっていない場合（SAF のドキュメント ID は不透明な値であることが多く、
  これはむしろ一般的なケースである）にマッチ自体が失敗し、MIME ベースのタグが実質的に無意味に
  なってしまう。フィルターを分離しておくことで、MIME タイプだけでのマッチが `.opml` という
  拡張子の有無に左右されなくなる。フォールバック側フィルターの `host="*"` は
  飾りではなく必須の指定である — `IntentFilter.matchData` は、フィルターに host が宣言されている
  場合に限って `pathPattern` を評価するため、host が無いとこのフォールバックは実際の
  `content://` URI に対して一切マッチしなくなる（その URI の本当の authority は配信元の
  プロバイダー次第で、例えば `com.android.externalstorage.documents` のように事前には列挙できない）
  — `"*"` は `IntentFilter` が公式にドキュメント化している「任意の host」を表すワイルドカードである。
  `AndroidOpmlOpen.kt` の
  `handleOpmlOpenIfPresent` が着信した `content://` `Uri` を `ContentResolver` 経由で読み取り、
  同じ `MainActivity`/`ACTION_VIEW` の処理を共有するが別の intent-filter を持つ `keryx://` の
  OAuth リダイレクトは除外する。`text/xml`/`application/xml` を受け入れることで、無関係な XML
  ファイルの「開く」候補にも Keryx が並んでしまうが、これは上記 Linux 節の `text/x-opml`
  フォールバックが既に受け入れているのと同じトレードオフである。不正な入力の扱いも他プラットフォーム
  と同様: `OpmlImporter.import` の失敗は伝播させず、その場で握りつぶす。

## リリース（CD）

`.github/workflows/release.yml` がパッケージをビルドし、GitHub Release に添付する。
**現状は macOS・Linux・Windows (x86_64、加えて macOS は arm64)、および Android (ユニバーサル APK/AAB)**（クロスコンパイル非対応のため、
プラットフォームごとにランナーが必要）。

フロー:

1. `vMAJOR.MINOR.PATCH` 形式のタグ（例: `v0.1.0`）で GitHub Release を公開する。SemVer 風の
   プレリリース接尾辞を任意で付けられる（例: `v1.2.0-beta.1`）。
2. `release: published` で起動し、先頭の `v` を除去して `-PappVersion` に渡す。
3. 4つの独立したジョブが並行して実行される:

   - macOS ランナーで `:composeApp:packageDmg` を実行し、`Keryx-<version>-macos-arm64.dmg` に加えて **`Keryx-<version>-macos-arm64.zip`** としても添付する。**プレリリースタグの場合は `packageDmg` をスキップし、`.zip` のみを添付する**（後述の Windows MSI と同じ理由）。
   - Linux ランナーで（jpackage 用に `fakeroot`/`rpm` をインストールした上で）`:composeApp:packageDeb :composeApp:packageRpm` を実行し、`Keryx-<version>-linux-x86_64.deb` と `Keryx-<version>-linux-x86_64.rpm` に加えて **`Keryx-<version>-linux-x86_64.zip`** としても添付する。**プレリリースタグの場合は `packageDeb`/`packageRpm` をスキップし、`.zip` のみを添付する**（後述の Windows MSI と同じ理由）。同じジョブで（`sudo snap install snapcraft --classic` の後）`snap/snapcraft.yaml` に対して `snapcraft pack --destructive-mode` も実行し、`Keryx-<version>-linux-x86_64.snap` を添付する — `.deb`/`.rpm` と異なり、snapcraftの`version:`フィールドはjpackageのパッケージメタデータのような `MAJOR.MINOR.PATCH` 限定ではないため、**プレリリースタグでもスキップせず添付する**。
   - Windows ランナーで `:composeApp:createDistributable :composeApp:packageMsi` を実行し（`windows-latest` には WiX Toolset v3.14.1 がプリインストール済みのため、別途 WiX のセットアップ手順は不要）、`Keryx-<version>-windows-x86_64.msi` に加えて **`Keryx-<version>-windows-x86_64.zip`** としても添付する。**プレリリースタグの場合は `packageMsi` をスキップし、`.zip` のみを添付する** — MSI の `ProductVersion`（後述）は数値のみでなければならず、同一の対象バージョンに属するプレリリースはすべて同じ `ProductVersion` に潰れてしまうため、固定の `upgradeUuid` の下では WiX が後続のプレリリースや最終的な正式版を「アップグレード」として認識できない。
   - Ubuntu ランナーで `:androidApp:assembleGithubRelease` と `:androidApp:bundlePlayRelease` を実行し、`Keryx-<version>-android-universal.apk` と `Keryx-<version>-android-universal.aab` として添付する。APK は `github` flavor（`REQUEST_INSTALL_PACKAGES` を持つ——アプリ内アップデートがこの上に上書きインストールするため。上記「Android（APK / AAB）」参照）から、AAB は `play`（Play Console 提出用の成果物で、この権限を持ってはならない）から生成する。Android 版はデスクトップのインストーラーとは異なり、プレリリースタグでもビルド・添付する — Android には該当するバージョンメタデータ制約が無く、テスターが署名済み APK を必要とするため。**ワークフローが出力するプレリリースの APK/AAB は、GitHub 用のテストアーティファクトに過ぎない。** `androidApp/build.gradle.kts` は `versionCode` を `appVersion.substringBefore('-')` から導出しているため、`v1.2.0-beta.1` のようなプレリリースタグと最終的な `v1.2.0` は同じ `versionCode`（例: `10200`）になる。Google Play に提出する際は、`androidApp/build.gradle.kts`（またはそれを駆動するリリースタグ）を調整し、厳密に増加した `versionCode` で再ビルドすること — この値はビルド時に署名済みアーティファクトへ焼き込まれるため、ビルド後に書き換えることはできない。

   `.zip` ファイルは `:composeApp:createDistributable` が出力する、インストーラ不要のアプリバンドル／イメージを圧縮したものである。パッケージを経由せずに使いたいユーザー向け。`deploy-pages` ジョブ（Cloudflare Pages のデプロイフックを叩く）は、4つのパッケージングジョブすべての完了を待ってから実行される。

**バージョンはタグを正とする**。`composeApp/build.gradle.kts` の `appVersion` は
`-PappVersion` > 環境変数 `APP_VERSION` > ファイル内のリテラル、の順に解決し、`BuildConfig.VERSION`
（About 画面表示・更新チェックで使用）を決める — プレリリース接尾辞を含む完全なタグそのもの。
`composeApp/build.gradle.kts` は別途、プレリリース接尾辞を除去した `appPackageVersion` を導出し、
これがすべてのネイティブターゲット向けの `packageVersion` を決める。jpackage のパッケージ用メタデータ
（CFBundleVersion・RPM の `%version`・MSI の `ProductVersion`）は数値のみの `MAJOR.MINOR.PATCH` でなければ
ならず、プレリリース接尾辞を含められないためである。プレリリースを伴わない通常のタグでは両者は一致するため、
何も変わらない。ローカルビルドは両方とも同じリテラル `"0.0.0"` にフォールバックする。jpackage が受け付けない
形式（`MAJOR.MINOR.PATCH[-<pre-release>]` 以外）のタグは、ワークフロー冒頭で明示的なメッセージとともに失敗させる。

`macos-latest` ランナーは arm64 のため、成果物名にアーキテクチャを含めている
（将来 x86_64 版やユニバーサル版を併置できるようにするため）。

### macOS における 0.x バージョンとプレリリースタグ

jpackage は macOS 向けの `--app-version` の先頭要素が `0` のものを受け付けない
（バージョンは 1 から始まるという CFBundleVersion の規則を強制するため）。しかも失敗するのは
DMG 生成ではなく **`createDistributable`** なので、そのままでは `0.x` は一切パッケージできない。
jpackage はパッケージ用バージョンが数値のみ（`MAJOR.MINOR.PATCH`）であることも要求するため、
`1.2.0-beta.1` のようなプレリリース接尾辞付きタグもそのままでは渡せない — 同じ制約は
他の 2 プラットフォームの RPM の `%version` フィールドと MSI の `ProductVersion` にも当てはまる。
jpackage に `--mac-app-version` のような別入力は無く、Compose プラグイン側の検証もどちらの
制約もカバーしていないため、設定で回避することはできない。

`composeApp/build.gradle.kts` は両方を `appPackageVersion`（前述の、プレリリース接尾辞を除いた
`appVersion`）で回避している。これが deb / rpm / msi 共通の `packageVersion` に渡り、macOS 専用の
派生値 `macOsPackageVersion` にもなる: `appPackageVersion` のメジャーが `0` のときは、macOS だけ
さらにプレースホルダ `1.0.0` でパッケージする（`macOS { packageVersion }` でのみ上書きする。
deb / rpm / msi は `0.x` を受け付けるので手を触れない）。メジャーが 1 以上の場合は素通しで何もしない。
パッケージされた `macOsPackageVersion` が実際の `appVersion` と食い違う場合 — 0.x のプレースホルダ、
プレリリース接尾辞の除去、あるいはその両方 — は常に、`createDistributable` の `doLast` で
`restoreMacOsShortVersion` が `CFBundleShortVersionString` を実バージョンに書き戻す。両者が
一致する場合（プレリリースを伴わない、メジャー 1 以上の通常のタグ）は何もしない。

jpackage が署名するのはこの `doLast` より**前**なので、書き戻しは ad-hoc の署名シールを無効化する。
そのため同じ `doLast` が続けてバンドルを**再署名**し（`resealMacOsBundle`:
`codesign --force --deep --preserve-metadata=entitlements,flags,runtime --sign -`）、
**再署名がハッシュ以外の署名特性を何も変えていないことを確認**し
（`macSignatureProperties` が `codesign -dv` の `flags=` と `hashes=13+N` を前後で比較する）、
最後に**検証**する（`verifyMacOsBundleSeal`: `codesign --verify --strict --deep`）。
どの段でも失敗すればビルドを失敗させる。

`--preserve-metadata` はこの中間の確認を通すためのもので、単なる保険ではなく必須である。
Compose Desktop はアプリイメージを自身の `default-entitlements.plist`（`allow-jit`、
`allow-unsigned-executable-memory`、`disable-library-validation`）**と** hardened runtime フラグの
両方で署名しており、これらは Apple Silicon 上で JVM が動作するために必要なものである。
`--options runtime` を手書きするとフラグは再現できるがエンタイトルメントが黙って落ち
（`hashes=13+7` が `13+3` になる）、`codesign --verify` は通るのに起動した瞬間に AMFI に
kill されるバンドルができる — シール自体は本当に有効なので、`verifyMacOsBundleSeal` だけでは
検出できない失敗である。よってメタデータの保持と前後比較のどちらも、シール検証と重複していない。検証は書き戻しの
有無にかかわらず macOS ビルドで常に走る。Windows / Linux には `.app` が存在しないので 3 段すべて
no-op。これは見た目の問題ではない: アプリ内アップデートは、ダウンロードしたバンドルを差し替える前に
まさにこの検証を実行する（[background-update.ja.md](background-update.ja.md) 参照）ため、これを
通れないアプリイメージはリリース ZIP を**アプリ内アップデータからは**インストール不能にする —
まったく同じ ZIP を手動でインストールする分には動き続ける。カーネルは起動時に `Info.plist` を
再ハッシュしないためである。この非対称性ゆえに 0.x のリリースはすべてこの状態のまま気づかれず出荷され、
アプリ内アップデータが初めてこの検証を行使したときに表面化した。そしてこれを捕まえられるのが
ビルド時の検証だけである理由でもある: 通常の手動スモークテストでは捕まらない。DMG でも表に出なかったのは、
jpackage が DMG 作成時にアプリイメージのコピーを再署名するから — `binaries/main/app` から直接作る
ZIP 資産だけが壊れたシールを抱えていた。

`0.1.1` での結果: タグ・`BuildConfig.VERSION`（About 画面）・更新チェック・Finder の表示が
すべて `0.1.1` で揃う。プレースホルダ `1.0.0` が残るのは `CFBundleVersion` だけで、これは
UI に一切現れない内部的なビルド識別子。中間成果物は `Keryx-1.0.0.dmg` という名前になるが、
ワークフローの rename ステップがタグから最終的なアセット名を決めるため、添付されるファイルは
`Keryx-0.1.1-macos-arm64.dmg` になる。`1.2.0-beta.1` のようなプレリリースタグでも同じ分離が
数値メタデータに適用される: `BuildConfig.VERSION`・Finder の表示バージョン・リリースアセット名は
すべて `1.2.0-beta.1` になり、`CFBundleVersion` / RPM の `%version` / MSI の `ProductVersion` は
除去済みの `1.2.0` になる。

`DROPBOX_APP_KEY` / `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` / `ONEDRIVE_CLIENT_ID` は
**リポジトリの Secrets** に設定する。未設定でもビルドは成功するが、リリースされたアプリでは
該当するクラウド連携が完全に非表示になる（`CloudStorageAvailability` 参照）。

Android のリリース署名には、`ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_RELEASE_KEYSTORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS`、`ANDROID_RELEASE_KEY_PASSWORD` をリポジトリの Secrets に設定する。keystore は Base64 エンコードした PKCS12/JKS ファイルであり、ワークフローがビルド時に復元する。GitHub Releases と Google Play で同じ署名キーを使いたい場合は、ローカルで生成した keystore を、アプリ作成時に Google Play Console で**既存のアプリ署名キー**として登録する: Play Console は生の JKS/PKCS12 ファイルをそのままでは受け付けず、まず Google の PEPK（Play Encrypt Private Key）ツールで暗号化する必要がある（`java -jar pepk.jar --keystore=<path> --alias=<alias> --output=<encrypted-file> --encryptionkey=<key-from-play-console>`。Play App Signing の登録ページからダウンロードできる）。生成された暗号化ファイルをアップロードすると、その keystore が**アプリ署名キー**として登録される — これは Google が保持し、ユーザーに届く前にアプリを再署名するために使う鍵であり、以降 Play Console にアップロードする各 `.aab` に署名する**アップロードキー**とは区別される。同じ keystore を両方の役割に使うこともでき（Google はアプリ署名キーをそのままアップロードキーとして再利用することを明示的に許可している）、これにより GitHub Releases（APK/AAB に直接その keystore で署名する）と Google Play の双方で単一の keystore のみで済む。専用のアップロードキーを別に用意するのは Google が推奨する追加の防御策であり、必須ではない。`release.yml` は `:androidApp:assembleGithubRelease`/`:androidApp:bundlePlayRelease` に `-PandroidReleaseSigningRequired=true` を渡しており、これは Secrets が未設定（または一部だけ設定）の場合に**即座のビルド失敗**へつなげるためのフラグ — このワークフローは成果物を公開するので、未署名のまま成功させてはならない。そのため release ワークフローの成功には4つすべての Secrets が必須。両方の flavor は同じ keystore で署名される（`signingConfigs` は flavor スコープではない）——これはまさに上記のアプリ署名キー登録が要求する構成そのもの: サイドロードされる `github` の APK と、Play が再署名する `play` の AAB は同一の署名 ID に遡れる必要があり、そうでなければ一方が既にインストールされている端末が他方をその場でのアップデートとして受け取れなくなる（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

`ci.yml` の通常のビルドジョブは、push のたびに実行され何も公開しない都合上、意図的にこれらの
Secrets を受け取らない。AGP は成果物が実際に使われるかどうかに関わらず `assembleRelease` を
`:androidApp` のデフォルトの `build` タスクに組み込むが（`bundleRelease` は別系統の
ライフサイクルタスクであり、だからこそ上記の `release.yml` は明示的に実行している）、
`androidApp/build.gradle.kts` の `signingConfigs` ブロックは、署名情報が一切設定されていない
状態を「未署名リリース」として扱う（ビルド失敗ではなく警告 — [setup.ja.md](setup.ja.md) の
「Android release signing keystore」参照）。`androidReleaseSigningRequired` を明示的に要求
しない限りこの経路に入るため、単なる `./gradlew build` は CI でもローカルでも keystore を
一切必要としない。成果物を実際に配布するワークフロー（`release.yml`）だけが、この経路の代わりに
即座の失敗を選んでいる。

> [!IMPORTANT]
> **リリースされる DMG は未署名**（ad-hoc）のため、開く際に Gatekeeper にブロックされる。回避方法は
> README の[ダウンロード](../README.ja.md#ダウンロード)節を参照。恒久的な解消に必要な作業は下記
> 「署名・公証」を参照。

## 署名・公証（将来対応）

現状、パッケージ成果物は **ad-hoc 署名**（実質未署名）。ローカルでの動作・開発には支障ないが、以下が
必要になったら **Developer ID Application** で署名する（Apple Developer Program の有償登録が前提）:

- 他 Mac への配布（Gatekeeper を通す）。
- macOS で Keychain アクセス時の許可ダイアログを消す（安定した署名アイデンティティにより ACL が固定される）。

> [!CAUTION]
> **0.x のまま署名を導入する場合は注意が必要。** jpackage が `.app` を署名するため、その**後**に
> `Info.plist` を編集するとバンドルの署名シールが壊れる。カスタム URI スキームは
> `macOS { infoPlist { extraKeysRawXml } }` 経由になったのでこれには該当しない
> （jpackage が署名する plist に最初から含まれている）。残るのは `restoreMacOsShortVersion` だけで、
> これはメジャーバージョンが `0` のときしか走らない。その書き戻しには既に `resealMacOsBundle` による
> 再署名が続く（前述のバージョン処理の節を参照）が、これは **ad-hoc** 再署名であり、このビルドが
> 署名 identity を一切設定していないため `-` をハードコードしている。したがって **0.x のまま**
> Developer ID 署名を導入する場合は、`resealMacOsBundle` にその identity を渡すよう変更する必要がある
> （Developer ID 署名の上に ad-hoc で再署名すると、それを黙って置き換えて公証も通らなくなる）。
> 1.0.0 以降は書き戻しも再署名も走らず、jpackage 自身の署名がそのまま残る。

手順の概要:

1. Apple Developer Program に登録し、**Developer ID Application** 証明書を login keychain に導入する
   （`security find-identity -v -p codesigning` に表示されること）。
2. `composeApp/build.gradle.kts` の `macOS {}` に署名を追加する。

   ```kotlin
   macOS {
       signing {
           sign.set(true)
           identity.set("Developer ID Application: <Name> (<TEAMID>)")
       }
   }
   ```

   秘密情報を VCS に載せない場合は `~/.gradle/gradle.properties` の
   `compose.desktop.mac.signing.identity` に置く。
3. 配布用に公証する場合のみ、app-specific password を用意して `macOS { notarization { appleID/password/teamId } }`
   を設定し、`./gradlew :composeApp:notarizeDmg` を実行する。ローカル確認だけなら公証は不要。

Keychain 利用のための特別な entitlement は不要（`get-task-allow` を付けないことだけ担保する。jpackage の
Developer ID 署名は hardened runtime を付与するため要件を満たす）。トークン保存の仕組みは
[sync-architecture.ja.md](sync-architecture.ja.md) の「Dropbox 認証 > トークン保存先」を参照。

## 設定メモ

- 構成キャッシュ（configuration cache）は `gradle.properties` で無効化している
  （`generateBuildConfig` タスクが config-cache 安全でないため）。安全性を確認せずに再有効化しないこと。
- `-Xexpect-actual-classes` を付与して expect/actual クラスの Beta 警告を抑制している。
- `nativeDistributions.modules` に **`jdk.security.auth`** を含めている。dbus-java の SASL EXTERNAL
  認証が非 Windows 環境で `com.sun.security.auth.module.UnixSystem` を使うため。外すと jlink イメージ
  自体は作れてしまうが、パッケージした `.deb`/`.rpm` だけが `NoClassDefFoundError` で落ちる
  （`./gradlew run` はフル JDK なので気づけない）。したがって **Linux のパッケージ検証は `run` ではなく
  `createDistributable` で生成した `bin/Keryx` を起動して行うこと**。これはトレイだけでなく
  java-keyring の Secret Service 経路にも効く。
- dbus-java（MIT）は全プラットフォームに同梱されるが、実行時に触るのは Linux のみ（トレイ + 通知）。
  バージョンは java-keyring が推移的に持ち込む版に固定している。`de.swiesend:secret-service` が
  `org.freedesktop.dbus.errors.Error` を参照しており、dbus-java 5 でこのクラスが移動したため、
  上げると Linux のキーリングが壊れる。
