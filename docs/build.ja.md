# ビルド・パッケージング

[English](build.md)

## 必要環境

- **JDK 25 以上**（`JAVA_HOME` が指す、`./gradlew` を起動する JVM）。
  コンパイル用ツールチェーン（JDK 25）は Gradle の foojay-resolver が自動取得する。
  ただし `:composeApp:run` などの JavaExec タスクは Gradle を起動した JVM で実行されるため、
  それが 25 未満だと実行時に `UnsupportedClassVersionError` になる。
- Gradle は同梱の wrapper（`./gradlew`, Gradle 9.6.1）を使う。

サンドボックス等でツールチェーンの自動ダウンロードが必要な場合:
`./gradlew -Dorg.gradle.java.installations.auto-download=true ...`。

## ビルド・実行

```bash
./gradlew build                    # 全ソースセットのコンパイル + テスト
./gradlew :composeApp:desktopTest  # テストのみ
./gradlew :composeApp:run          # デスクトップアプリを起動
```

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

> **「テスト」ステータスのままだとリフレッシュトークンが7日で失効する。** OAuth同意画面の公開ステータスを
> 「テスト」のままにしていると、Googleは発行から7日で失効するリフレッシュトークンを発行するため、Google
> Driveの同期接続はおよそ週1回の再連携が必要になる（アプリ側は `CloudAuthException` の通知センター表示として
> 表面化し、サイレントに失敗するわけではない）。長期運用する場合は「オーディエンス」タブで公開ステータスを
> 「本番環境」に切り替える必要がある（`drive.appdata` は非機微スコープなので、必要なのはGoogleの基本的な
> アプリ確認のみで、制限付きスコープの審査は不要）。

### OneDrive

1. [Azure Portal](https://portal.azure.com) →「Microsoft Entra ID」→「アプリの登録」→「新規登録」でアプリを登録
   - 「サポートされているアカウントの種類」は「個人用 Microsoft アカウントのみ」を選ぶ。
2. 「認証」→「プラットフォームを追加」→ **「モバイル アプリケーションとデスクトップ アプリケーション」**:
   - 「カスタム リダイレクト URI」に `keryx://oauth2/callback` を追加する。
   - 「パブリック クライアント フローを許可する」を **はい** にする（OneDrive は PKCE パブリッククライアントで、クライアントシークレットは不要）。
3. 「API のアクセス許可」→「アクセス許可の追加」→「Microsoft Graph」→「委任されたアクセス許可」で **`Files.ReadWrite.AppFolder`** を追加する（ドライブ内の任意のファイルではなく、アプリ専用フォルダーへのみアクセスを許可する）。リフレッシュトークン用の `offline_access` は実行時に要求する。
4. 「概要」の「アプリケーション (クライアント) ID」を `local.properties` の `onedrive.client.id` に指定する。

OneDrive は Dropbox と同じカスタム URI スキーム（`keryx://oauth2/callback`、`state` で識別）を再利用するため、追加の OS 登録は不要。**クライアントシークレットは不要**（Google と異なり、Microsoft は「モバイル/デスクトップ」登録を PKCE の完全なパブリッククライアントとして扱う）。同期 DB は OneDrive のアプリ専用フォルダー（`/me/drive/special/approot`）に保存される。Dropbox 同様、macOS では `keryx://` がパッケージ済みアプリへルーティングされるため `./gradlew :composeApp:run` では連携が完了しない。macOS で検証するには `createDistributable` で `Keryx.app` をビルドして起動する。

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

> **カスタム URI 連携の確認**: `./gradlew :composeApp:run` ではどのデスクトップ OS でも Dropbox / OneDrive
> 連携が完了しない（macOS は `keryx://` がパッケージ版アプリにルーティングされ、Windows / Linux は起動時の
> 登録がパッケージ版ランチャー以外を意図的にスキップするため）。連携の動作確認は `createDistributable` で
> ビルドしたアプリを起動して行う（詳細は [setup.ja.md](setup.ja.md) の「よくある問題」）。

## リリース（CD）

`.github/workflows/release.yml` がパッケージをビルドし、GitHub Release に添付する。
**現状は macOS のみ**（クロスコンパイル非対応のため、プラットフォームを増やすにはランナーを追加する）。

フロー:

1. `vMAJOR.MINOR.PATCH` 形式のタグ（例: `v0.1.0`）で GitHub Release を公開する。
2. `release: published` で起動し、先頭の `v` を除去して `-PappVersion` に渡す。
3. `:composeApp:packageDmg` を実行し、DMG を `Keryx-<version>-macos-arm64.dmg` として Release に添付する。

**バージョンはタグを正とする**。`composeApp/build.gradle.kts` の `appVersion` は
`-PappVersion` > 環境変数 `APP_VERSION` > ファイル内のリテラル、の順に解決するため、タグが
`BuildConfig.VERSION`（About 画面に表示）と `packageVersion` の両方を決め、両者が食い違うことがない。
ローカルビルドはリテラルにフォールバックするので、普段の開発手順は変わらない。jpackage が受け付けない
形式（`MAJOR[.MINOR[.PATCH]]` 以外）のタグは、ワークフロー冒頭で明示的なメッセージとともに失敗させる。

`macos-latest` ランナーは arm64 のため、成果物名にアーキテクチャを含めている
（将来 x86_64 版やユニバーサル版を併置できるようにするため）。

### macOS における 0.x バージョン

jpackage は macOS 向けの `--app-version` の先頭要素が `0` のものを受け付けない
（バージョンは 1 から始まるという CFBundleVersion の規則を強制するため）。しかも失敗するのは
DMG 生成ではなく **`createDistributable`** なので、そのままでは `0.x` は一切パッケージできない。
jpackage に `--mac-app-version` のような別入力は無く、Compose プラグイン側の検証もこの制約を
カバーしていないため、設定で回避することはできない。

`composeApp/build.gradle.kts` で以下のように回避している。メジャーバージョンが `0` のときは
プレースホルダ `1.0.0` でパッケージし（`macOsPackageVersion`。`macOS { packageVersion }` でのみ
上書きする。deb / rpm / msi は `0.x` を受け付けるので手を触れない）、`createDistributable` の
`doLast` で `restoreMacOsShortVersion` が `CFBundleShortVersionString` を実バージョンに書き戻す。
メジャーが 1 以上の場合は素通しで何もしない。

`0.1.1` での結果: タグ・`BuildConfig.VERSION`（About 画面）・更新チェック・Finder の表示が
すべて `0.1.1` で揃う。プレースホルダ `1.0.0` が残るのは `CFBundleVersion` だけで、これは
UI に一切現れない内部的なビルド識別子。中間成果物は `Keryx-1.0.0.dmg` という名前になるが、
ワークフローの rename ステップがタグから最終的なアセット名を決めるため、添付されるファイルは
`Keryx-0.1.1-macos-arm64.dmg` になる。

`DROPBOX_APP_KEY` / `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` / `ONEDRIVE_CLIENT_ID` は
**リポジトリの Secrets** に設定する。未設定でもビルドは成功するが、リリースされたアプリでは
該当するクラウド連携が完全に非表示になる（`CloudStorageAvailability` 参照）。

> **リリースされる DMG は未署名**（ad-hoc）。利用者は Gatekeeper にブロックされるため、
> 右クリック →「開く」または quarantine 属性の削除が必要。解消に必要な作業は下記「署名・公証」を参照。

## 署名・公証（将来対応）

現状、パッケージ成果物は **ad-hoc 署名**（実質未署名）。ローカルでの動作・開発には支障ないが、以下が
必要になったら **Developer ID Application** で署名する（Apple Developer Program の有償登録が前提）:

- 他 Mac への配布（Gatekeeper を通す）。
- macOS で Keychain アクセス時の許可ダイアログを消す（安定した署名アイデンティティにより ACL が固定される）。

> **0.x のまま署名を導入する場合は注意が必要。** jpackage が `.app` を署名するため、その**後**に
> `Info.plist` を編集するとバンドルの署名シールが壊れる。カスタム URI スキームは
> `macOS { infoPlist { extraKeysRawXml } }` 経由になったのでこれには該当しない
> （jpackage が署名する plist に最初から含まれている）。残るのは `restoreMacOsShortVersion` だけで、
> これはメジャーバージョンが `0` のときしか走らない。1.0.0 以降は後処理が一切なくなり、
> `codesign --verify --strict` が通る。**0.x のまま** Developer ID 署名を導入する場合は、
> バージョン書き戻しが署名を無効化するため、書き換え後に同じ identity で再署名する必要がある
> （ad-hoc で再署名すると Developer ID 署名を黙って上書きしてしまい、公証も通らなくなる）。

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
