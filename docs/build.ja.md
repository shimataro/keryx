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

## Dropbox App Key（`DROPBOX_APP_KEY`）

Gradle のカスタムタスク（`generateBuildConfig`）で実現している。優先順位:

1. `-PdropboxAppKey=...`（Gradle プロパティ）
2. 環境変数 `DROPBOX_APP_KEY`
3. `local.properties` の `dropbox.app.key`（git 管理外。`local.properties.example` を参照）
4. 空文字

生成される `works.merc.keryx.app.BuildConfig.DROPBOX_APP_KEY` が空の場合、
`CloudStorageAvailability.dropboxAvailable` が `false` になり、Setup / Settings 画面から
Dropbox 連携が完全に非表示になる。

## Google Drive Client ID / Secret（`GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET`）

Dropbox App Key と同じく Gradle のカスタムタスク（`generateBuildConfig`）で生成する。
Google Cloud Console で **「デスクトップアプリ」タイプの OAuth クライアント**を作成し、その
クライアント ID とクライアントシークレットを指定する（Google のデスクトップ向けクライアントは
任意のカスタムスキームを許可せず、リダイレクトは `http://127.0.0.1:<ポート>` のループバックで
受ける。アプリ側は `LoopbackRedirectTransport` で一時 HTTP サーバーを立てて受信する）。フローは
PKCE（`code_verifier`）を使うが、**クライアントシークレットは別途必要**——「デスクトップアプリ」
タイプの OAuth クライアントは iOS/Android と異なり完全な public client として扱われず、Google の
トークンエンドポイントは `client_secret` を伴わないトークン交換・リフレッシュを
`invalid_request: client_secret is missing` で拒否する（PKCE の有無に関わらず）。スコープは
`drive.appdata`（ユーザーの Drive に見えないアプリ専用フォルダー）のみを要求する。開発中は OAuth
同意画面を「テスト」公開ステータスにしてテストユーザーを登録すれば足りる。

クライアント ID の優先順位:

1. `-PgoogleDriveClientId=...`（Gradle プロパティ）
2. 環境変数 `GOOGLE_DRIVE_CLIENT_ID`
3. `local.properties` の `googledrive.client.id`（git 管理外。`local.properties.example` を参照）
4. 空文字

クライアントシークレットの優先順位（同じ規則）:

1. `-PgoogleDriveClientSecret=...`（Gradle プロパティ）
2. 環境変数 `GOOGLE_DRIVE_CLIENT_SECRET`
3. `local.properties` の `googledrive.client.secret`（git 管理外）
4. 空文字

生成される `works.merc.keryx.app.BuildConfig.GOOGLE_DRIVE_CLIENT_ID` または
`GOOGLE_DRIVE_CLIENT_SECRET` のいずれかが空の場合、`CloudStorageAvailability.googleDriveAvailable`
が `false` になり、Setup / Settings 画面から Google Drive 連携が完全に非表示になる。

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
`composeApp/src/commonMain/composeResources/drawable/tray_icon*.png`。これらは
`design/icons/make_desktop_icons.sh` で共有アートワークから生成する
（生成済みファイルはコミットしておくのが望ましい）。

> **macOS の Dropbox 連携確認**: `keryx://` のカスタム URI はパッケージ版アプリにルーティングされるため、
> `./gradlew :composeApp:run` では連携が完了しない。連携の動作確認は `createDistributable` でビルドした
> `Keryx.app` を起動して行う（詳細は [setup.ja.md](setup.ja.md) の「よくある問題」）。

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

`DROPBOX_APP_KEY` / `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` は
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
