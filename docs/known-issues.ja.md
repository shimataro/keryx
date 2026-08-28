# 既知の不具合

[English](known-issues.md)

原因は判明しているが、意図的に修正していない不具合と、その判断の根拠。
調査で除外した内容も記録してあるので、後から調べ直すときに同じ作業を繰り返さずに済む。

## macOS: 通知バナーをクリックしてもトレイに収納中のウインドウが復元されない

**状態**: 未修正 — 外部（JDK/AWT）の制限による。これを回避しようとしていたアプリ側の `MacTray` の
コードは**デッドコードとして削除済み**（実際には何も仕事をしていなかった — 詳細は下記「カスタムの
配線はデッドコードだった」を参照）。トレイアイコンのクリック、またはアプリの再起動（シングルインスタ
ンス転送。既に起動中の状態で Dock アイコンをクリックするなど）による復元はどちらも正常に動作するため、
ウインドウが永久に取り戻せなくなるわけではない。影響を受けるのは通知バナー自体のクリックだけで、
かつトレイ収納中のみ。

### 症状

macOS でウインドウをトレイに収納した状態で新着記事の通知をクリックしても何も起こらない —
ウインドウが一瞬でも表示されることはなく、Dock アイコンも復活せず、音も鳴らない。バナーの
「表示」アクションボタンを押した場合もバナー自体は消えるが、それ以外の効果はない。同じクリックでも、
ウインドウが単にバックグラウンド（表示はされているがフォーカスが外れている/他のウインドウの背後）の
状態では正しく前面化する。

### 診断

以前のバージョンの `MacTray.kt` は、通知クリックを AWT の `TrayIcon.addActionListener(...)` 経由で
配線していた — これは `displayMessage(...)` で表示したバナーのクリックに対して `TrayIcon` が唯一
公開している API。一時的な診断ログ（その `ActionListener` の最初の一文としての `Log.info`、および
`main.kt` の `activationRequests` コレクタの各段階）を仕込み、実際にパッケージ化したビルドで
`tail -f` を使って確認したところ、**ウインドウがトレイ収納中の状態（Accessory アクティベーション
ポリシー）でクリックした場合、`ActionListener` は一切呼び出されない**ことが確定した — どのレベルの
ログ行も、経路上のどこにも一切出力されなかった。同じコレクタは、通常のトレイアイコンクリックや、
起動中インスタンスへ転送される 2 回目の起動経由では正しく到達し復元される（どちらもログで確認済み）
ため、復元ロジック自体（`main.kt` の `activationRequests` コレクタ、そこにある Accessory→Regular
アクティベーションポリシーの順序修正を含む）には問題がないことが証明された。

これは `TrayIcon` のネイティブ macOS ピア（`CTrayIcon`）を指し示している。これは `displayMessage(...)`
を非推奨の `NSUserNotification` API 経由でブリッジしている。クリック時のデリゲートコールバック
（`userNotificationCenter:didActivateNotification:`）は、所有アプリに Dock アイコンがない状態
（`NSApplicationActivationPolicyAccessory`）— まさにこの機能が対象としていたトレイ収納状態そのもの —
では Java の `ActionListener` へ確実にはブリッジされていないと見られる。これ以上は Kotlin/Java の
コードを読むだけでは絞り込めなかった。本ファイルの Linux `GtkFileDialogPeer` クラッシュの節で行った
のと同様に `CTrayIcon` のネイティブ実装をデコンパイルするか、本コードベース外の最小構成の純粋 AWT
テストアプリで再現させる必要があるが、いずれも行っていない。

### カスタムの配線はデッドコードだった

続けて行った確認により、`ActionListener` ベースのコードが*何か*役に立っていたのかどうかが決着した:
「バックグラウンド状態ではクリックで前面化する／トレイ収納中は復元しない」という全く同じ挙動が、
`MacTray` に `onNotificationClicked`/`ActionListener` の配線が**一切存在しなかった** `v0` ブランチ
でも確認された。これにより、「バックグラウンド状態→前面化」というケースは一度もアプリ自身のコードが
動いていたわけではなく、macOS 自体が持つ「Regular ポリシーのアプリなら通知バナークリックで自動的に
アクティブ化する」というデフォルト動作であり、アプリが通知デリゲートを登録しているかどうかに関係なく
起こることが証明された。何回かのコミットにわたって追加されたこのカスタム AWT 配線（`MacTray.kt` 周辺
の git 履歴を参照）は、本来の対象だったトレイ収納中のケースでは一度も動作せず、`ActionListener` が
実際に発火するケースでは重複していただけだった — そのため丸ごと削除した。`MacTray` はもう
`onNotificationClicked` パラメータを受け取らず、`TrayIcon` の `ActionListener` も登録しない。
Linux SNI 側の独自の、正常に機能している通知クリック処理（`LinuxTray`、`LinuxNotifier` の D-Bus
`ActionInvoked` シグナル経由）には手を付けていない — `KeryxTray` の `onNotificationClicked`
パラメータと `main.kt` の `activationRequests.tryEmit(Unit)` コールバックは引き続き存在し、
そちらへ供給し続けている。

### 除外した仮説

- **`main.kt` の復元/アクティベーションポリシーのロジック自体のバグ** — 反証済み: 同じ
  `activationRequests` コレクタが、トレイアイコンクリックや転送された2回目の起動経由では毎回正しく
  ウインドウを復元し、ログですべての段階が完了していることを確認できた。
- **Cocoa のアクティベーションポリシー昇格とウインドウ表示の順序** — 完全な順序入れ替え（先に
  Regular へ昇格・アクティブ化し、ウインドウの表示・前面化・フォーカス取得はさらに後の EDT ターンへ
  遅延させる）を実装・検証したが、通知クリックのケースに限っては観測可能な違いを生まなかった。ただし
  これは別の実在するバグ — 同じコレクタがシングルインスタンス/再起動経路経由で到達した際のトレイからの
  復元 — は修正できたため、その理由でコードには残してある（`main.kt` に生きたまま残っているが、
  macOS の通知クリックからはもう到達できない）。

### 回避策

特に適用していない — 現状動作しているトレイアイコンクリック・アプリ再起動による復元経路を、macOS で
トレイ収納中のウインドウを取り戻す正式な手段として扱う。

### 本当の修正に必要なこと

macOS では `TrayIcon.displayMessage()`/`ActionListener` による通知表示・クリック検知を完全に迂回し、
Cocoa の `NSUserNotificationCenter`（または非推奨でない現行の `UserNotifications` フレームワーク）を
JNA ベースの Objective-C ブリッジで直接操作する必要がある — `MacActivationPolicy` が既に行っている
生の `objc_msgSend` 呼び出しと同じ方向性だが、規模はかなり大きい。通知センターのデリゲートとして動作する
ランタイム Objective-C クラスを生成する必要があり（`objc_allocateClassPair`/`class_addMethod` に JNA の
`Callback` を実装として渡す）、これは本質的にリスクの高いネイティブ相互運用であり（実装を誤ると JVM が
クラッシュしうる点は、本ファイルに記録されている Linux の GTK クラッシュと同じ種類のリスク）、実機での
検証を何度も繰り返す必要がある。

もっとも、これは着手する価値より先に不要になる可能性が高い。`app-architecture.md` によれば macOS は
将来的にネイティブ SwiftUI 実装へ移行することが想定されている（`external-spec.md` §2 — Android や iOS
自体がまだターゲットとして存在しない現状では、より長期の未確定な方向性）。ネイティブアプリであれば
通常のアプリライフサイクルを通じて `UNUserNotificationCenterDelegate` を使うことになり — AWT の
ブリッジも非推奨 API も、Accessory ポリシー固有のブリッジ不具合も存在しない — これは macOS の
メニューバー常駐（`LSUIElement`）アプリでよく使われる、確実に動作するパターンである。それを踏まえ、
また上記の AWT ベースの実装が結局動かなかったことも踏まえ、
上記の JNA ブリッジの実装は当面見送り、この記録にとどめる。ネイティブ SwiftUI 移行そのものがさらに
先送りされ続け、この回避策のギャップを個別に埋める価値が出てくるまでは。

## Linux: OPML のインポート/エクスポートで libawt_xawt.so の SIGSEGV により JVM がクラッシュする

**状態**: 解決済み — Linux の OPML ファイルダイアログのバックエンドを `javax.swing.JFileChooser`
（`platform/FilePicker.desktop.kt`）に差し替えたことで解決した。macOS/Windows は `java.awt.FileDialog`
のまま。デコンパイルした OpenJDK の内部実装が根拠になっており再調査を避けるため、また単なる JVM
フラグによる回避策を却下した理由の記録として、全文を残す。

### 症状

Linux で OPML のインポート/エクスポートダイアログ（設定 ▸ データ管理 ▸ OPML をインポート/エクスポート）
を開くとウインドウがフリーズし、プロセス全体が `SIGSEGV` でクラッシュした:

```text
# A fatal error has been detected by the Java Runtime Environment:
#  SIGSEGV (0xb) at pc=0x00007f0318391a5f, ...
# Problematic frame:
# C  [libawt_xawt.so+0x51a5f]
```

インポートとエクスポートは同じライブラリ内の異なるオフセット（インポートは `+0x51a5f`、エクスポートは
`+0x51af9`）でクラッシュした。macOS では再現しなかった。

### 調査

`platform/FilePicker.desktop.kt` は `java.awt.FileDialog` を使っていた。Linux では
`sun.awt.X11.XToolkit.createFileDialog()` が `GtkFileDialogPeer`（`libawt_xawt.so` 内の GTK3 ネイティブ
コード）を選ぶ（`sun.awt.disableGtkFileDialogs=true` を設定しない限り）。macOS は
`LWCToolkit`/`NSSavePanel` を使うためこの経路を一切通らず、報告どおり macOS では問題なかった。

OpenJDK ソースの `sun_awt_X11_GtkFileDialogPeer.c` をデコンパイルし、各 `hs_err_pid*.log` の 2 つの
クラッシュアドレスを逆アセンブルしたところ、両方とも同じ根本原因 — NULL チェックなしで逆参照された
NULL の `JNU_GetEnv(jvm, JNI_VERSION_1_2)` の返り値 — に行き着いた:

| 操作 | pc | 関数 | 逆アセンブルで判明したこと |
| --- | --- | --- | --- |
| インポート | `+0x51a5f` | `filenameFilterCallback`（`FilenameFilter` を設定したときだけ登録される＝インポート側のみ。エクスポート側は登録しない） | `mov rsi,[r13+8]` = `filter_info->filename`、その後 `NewStringUTF` の vtable スロット経由の呼び出し |
| エクスポート | `+0x51af9` | `handle_response` | `cmp r12d,-3`（`GTK_RESPONSE_ACCEPT`）、その後 `ExceptionCheck` の vtable スロット経由の呼び出し |

`JNU_GetEnv` が NULL を返すのは呼び出しスレッドが JVM にアタッチされていないときだけで、両方の
`hs_err` ヘッダがまさにそれを示している: `Current thread is native thread`。プロセスには既に
`libgtk-3`・`libgdk-3`・**`libwebkit2gtk-4.1`** がロードされていた — 記事リーダーのネイティブ
WebView（`io.github.kdroidfilter.webview`/wry）はペインの生存期間中ずっと無条件にマウントされる方針
（下記の WebView のエントリ参照）なので、プロセスのデフォルト `GMainContext` を共有する 2 つ目の
GTK コンシューマになっている。埋め込み WebView を持たない素の Swing アプリでこれが起きないのは、
GTK コンシューマが 1 つだけなら GTK 自身のシグナルディスパッチが JVM に既知のスレッド上にとどまる
ためと考えられる。

### 除外した仮説

- **インポートまたはエクスポート固有のバグ** — 反証済み: どちらも同じネイティブファイル内の、同じ
  チェックなし API（`JNU_GetEnv`）の呼び出し箇所（各ダイアログの応答経路ごとに 1 箇所）でクラッシュ
  している。
- **`FilenameFilter` 自体が引き金** — エクスポート側のクラッシュ（`handle_response`）には
  `FilenameFilter` が一切関与していない。共通の原因は JNI アタッチであり、フィルタではない。

### 効果がなかった／却下した回避策

- **`-Dsun.awt.disableGtkFileDialogs=true`** — Linux を `XFileDialogPeer` 経由にすることで GTK ピア
  （とクラッシュ）を丸ごと回避できる。却下: AWT/ツールキット初期化前に設定する必要があり
  （`ui/theme/DesktopLookAndFeel.installLookAndFeel` と同じ順序制約 — `app-architecture.md` 参照）、
  `XFileDialogPeer` はこのアプリが同じ「見た目が古い」という理由で FlatLaf に置き換えた GTK2 世代の
  Swing Look & Feel よりもさらに古い Motif 風の XAWT ダイアログである（下記「Desktop Tray」「Native
  file dialogs」参照）。FlatLaf にもまったく追従しない点も `JFileChooser` と異なる。

### 解決方法

Linux のバックエンドを `javax.swing.JFileChooser`（`platform/FilePicker.desktop.kt` の
`SwingFilePickerBackend`）に完全に差し替えた。コンテキストメニューで既に使っている Linux
Swing-vs-AWT の分岐（`NativeMenu.desktop.kt` の `defaultPopupHandle`）と同じパターン。
`JFileChooser` はどの Look & Feel 上でも純粋な Swing 実装であり — FlatLaf が失敗した場合の
システム L&F フォールバックでも、`GTKLookAndFeel` 自身の `GTKFileChooserUI` は純粋な Swing
実装なので — クラッシュした GTK ネイティブコードには一切到達しない。結果の設計、上書き確認の詳細、
そして将来課題として記録した XDG デスクトップポータルについては `app-architecture.md` の
「Native file dialogs (platform branch)」を参照。

挙動面で記録に値する点が一つある: 同じネイティブファイルをデコンパイルしたところ、クラッシュ前の
Linux の `FileDialog` は SAVE アクションで無条件に
`gtk_file_chooser_set_do_overwrite_confirmation(dialog, TRUE)` を呼んでおり、既にネイティブの
上書き確認を持っていたことが分かった（macOS/Windows も今なお同様）。`JFileChooser` にはそうした
確認機能が一切ないため、今回の修正ではそれを明示的に復元している（`resolveSavePath` ＋
`JOptionPane` による確認）。Linux 自身の従来の挙動に対して黙って退行させないための対応である。
同じソースには拡張子の自動補完ロジックはどのプラットフォームにも一切存在しなかったため、
そちらは意図的に追加していない。

## Linux の Wayland/XWayland: ドラッグ中のカーソルが「禁止」のまま固着する（ドロップ自体は成功）

**状態**: 解決済み — フィード一覧から OS レベルのドラッグ&ドロップそのものを取り除いたことで解決した。
フィード/フォルダーの並べ替えジェスチャーは現在、自前実装の Compose ネイティブなドラッグ
（`ui/home/FeedListDragController.kt` + `FeedListDragGestures.kt`：手動の `pointerInput` 追跡、
Compose で描画するフローティングゴースト、直接的な当たり判定）になっており、どのセッション種別でも
XDnD/XWayland のカーソル交渉自体が一切発生しない。以下の調査内容はそのまま残してある —
この機能（あるいは将来の同種のウィンドウ内限定ドラッグ）に対して
`Modifier.dragAndDropSource`/`dragAndDropTarget`（実際の OS レベル DnD）を再導入することを
検討する際に、XWayland をクライアント側から回避できない理由の分析としてなお価値があるため。

### 症状

フィード一覧でフィードやフォルダーの行をドラッグすると、ドラッグ中ずっと OS の禁止（「no-drop」）
カーソルが表示され、ドラッグゴーストは出ない。ドロップ自体はどの環境でも問題なく成立する —
データや機能への影響はないが、正常に動く機能が壊れているように見える。

### 診断

実機に対して、順に 3 段階のコード修正を試みた。

1. AWT 自身が計算したドロップアクションを `DragSourceListener` の `dragEnter`/`dragOver`/
   `dropActionChanged` から `DragSourceContext.setCursor()` にそのまま反映する。
2. 計算されたドロップアクションに関わらず、同じコールバックから無条件に
   `DragSource.DefaultMoveDrop` を強制する。
3. ウィンドウ内ドラッグではステータス系のコールバックがそもそも発火していない可能性を疑い、
   ポインター移動があるたびに（ドロップターゲット側の応答の有無に関わらず）発火する
   `DragSourceMotionListener`（`dragMouseMoved`）を追加し、同じ無条件の `setCursor()` 呼び出しの
   トリガーとして使う。

いずれも報告者の環境では**見た目上まったく変化がなかった**。4 回目を当てずっぽうで試す前に、
ドラッグ中にどのコールバックが実際に発火しているかを記録する一時的な診断ログを追加した。同じ
ビルドでセッション種別だけを変えて 2 回テストした結果は次の通り。

Plasma **Wayland** セッション — ドラッグ中ずっと禁止アイコンが表示される:

```text
Linux drag-cursor fix installed
Observed dragMouseMoved for the first time this drag (dropAction=0)
Observed dragEnter for the first time this drag (dropAction=2)
Observed dragOver for the first time this drag (dropAction=2)
Drag ended; callbacks observed this gesture: [dragMouseMoved, dragEnter, dragOver]
```

Plasma **X11** セッション — カーソルは終始正しく、禁止アイコンは一度も出ない:

```text
Linux drag-cursor fix installed
Observed dragMouseMoved for the first time this drag (dropAction=0)
Observed dragEnter for the first time this drag (dropAction=2)
Observed dragOver for the first time this drag (dropAction=2)
Observed dragExit for the first time this drag
Drag ended; callbacks observed this gesture: [dragMouseMoved, dragEnter, dragOver, dragExit]
```

両者のシーケンスは機能的に同一で（AWT は**どちらのセッションでも**数ミリ秒以内にドロップ
アクションを `ACTION_MOVE`（`2`）に解決しており、同じ `setCursor(DragSource.DefaultMoveDrop)`
呼び出しに**どちらでも**到達している）、それでも X11 だけがカーソルの修正を反映する。2 回の
テストで変わった変数はセッション種別だけである。

### 原因

Keryx の Linux ビルドは AWT の X11 ツールキット上で動作する（一般提供されているネイティブ
Wayland 対応の AWT/Compose Desktop ツールキットは存在しない）。そのため Wayland セッションでは
**XWayland** クライアントとして動作する。XWayland はクライアントの X11 XDnD プロトコルを
コンポジターのネイティブな `wl_data_device` プロトコルへブリッジしており、そのブリッジされた
操作中に表示されるドラッグカーソルは、交渉された Wayland 側の DnD アクションに基づいて
**コンポジターが描画**するものであり、クライアントが `XDefineCursor`／
`DragSourceContext.setCursor()` で要求する X11 カーソルではない。これは Keryx や Compose
Desktop 固有の問題ではなく、XWayland の DnD 実装によく知られる制限のカテゴリに属する —
X11 では確実にカーソルを直す同じ呼び出しが、XWayland を経由した途端に無視されるのは、
Wayland ネイティブの DnD グラブにおいてカーソルの所有者がクライアントではなくコンポジターで
あるためである。

### 除外した仮説

- **AWT が誤った（拒否の）ドロップアクションを計算している** — 反証済み。両方のセッションの
  ログで、数ミリ秒以内に `ACTION_MOVE` に解決している。
- **`DragSourceListener`/`DragSourceMotionListener` のコールバックがそもそも発火していない** —
  反証済み。X11 と Wayland で同一のコールバック列が発火している。
- **Keryx や Compose Desktop のコード固有の問題** — 同じビルド・同じ呼び出し列で、セッション
  種別だけによって見た目の結果が変わり、2 回のテスト間でコード変更は一切ない。

### 効かなかった対処（Wayland では。3 つとも X11 では効く）

再度試さないように記録しておく。3 番目はこの修正が入る前に出荷されていたもので、X11 に対する
実効性のある修正だった。

- 計算されたドロップアクションを `setCursor()` にそのまま反映する（対処 1）。
- `DragSourceListener` のコールバックだけから `DragSource.DefaultMoveDrop` を無条件に強制する
  （対処 2）。
- 同じ無条件の `setCursor()` 呼び出しのトリガーとして、より頻繁に発火する
  `DragSourceMotionListener.dragMouseMoved` を追加する（対処 3、旧出荷版）。

### 本当に Wayland を直すには何が必要か

Wayland セッションでドラッグカーソルを完全に制御するには、X11 の `Cursor` ではなく本物の
Wayland サーフェスを介して、Wayland ネイティブの `wl_data_device`/`wl_data_source` プロトコルを
直接叩く必要がある。これには、（このプロジェクトが対象とする JDK/Compose Multiplatform の
バージョンには存在しない）ネイティブ Wayland 対応の AWT/Compose Desktop ツールキットを使うか、
この 1 箇所のためだけに AWT を迂回して libwayland への JNI ブリッジを自前で書くか、いずれかが
必要になる。ドロップ自体はどのセッション種別でも既に成功していることを踏まえると、見た目だけの
カーソルアイコンの問題に対してはどちらも釣り合わない対応である。

### 実際にどう解決したか

上記のどちらの手段も取らず、フィード一覧のドラッグを OS レベルの DnD にまったく頼らない形へ
作り直した。Keryx のドラッグは常にウィンドウ内で完結する（フィード一覧内での並べ替えのみで、
別アプリ／別ウィンドウへドラッグすることはない）ため、`java.awt.dnd` はそもそも必須ではなかった。
`LinuxDragCursorFix.kt`（上記の対処 3）と、AWT 依存だった `platform/FeedDragAndDrop.kt`/
`FeedDragAndDrop.desktop.kt`、`ui/home/DragAndDropSourceWithThreshold.kt` は完全に削除し、
自前実装のドラッグに置き換えた。このドラッグはフィードペインの単一の（仮想化されない）コンテナ上で
ホストされ（行単位でジェスチャーを持たせるとオートスクロールでドラッグ中に破棄されうるため）、
Compose 側で描画する独自のゴーストオーバーレイを持つ。副産物として、Linux でも初めて本物の
ドラッグゴーストが手に入り（X11 の AWT はカーソルの不具合以前からゴースト自体に非対応だった）、
このジェスチャーが初めてユニット/UI テスト可能になった（`ui/home/FeedListDragTest.kt`）——
OS レベル版は実際の（テストコードから構築できない）AWT イベントを必要とし、`docs/testing.md` に
テスト不可と記録されていた。

## 記事一覧の激しいスクロールと選択変更が重なると UI スレッドが落ちる

**状態**: 未修正 — Compose 側の不具合のため、ライブラリの更新待ち。

### 症状

記事一覧（中央ペイン）をスクロール中に、AWT イベントスレッドが次の例外で停止する。

```text
java.lang.IllegalArgumentException: onReuse is only expected on attached node
    at androidx.compose.ui.node.LayoutNode.onReuse(LayoutNode.kt:2262)
    at androidx.compose.runtime.Applier.reuse(Applier.kt:185)
    ...
    at androidx.compose.ui.layout.LayoutNodeSubcompositionsState.subcompose(SubcomposeLayout.kt:719)
    at androidx.compose.foundation.lazy.LazyListMeasureKt.measureLazyList-pIk1_oM(LazyListMeasure.kt:179)
    at androidx.compose.foundation.lazy.LazyListState.onScroll$foundation(LazyListState.kt:549)
```

同じ例外がログに 3 回出る（コンポジション内で捕捉 → `SEVERE [Main] Uncaught exception in window`
→ `Exception in thread "AWT-EventQueue-0"`）。**これが最初のエラー**であり、別の例外の二次障害
ではない。

発生後はウィンドウが反応しなくなる。**データは壊れない** — UI 層に閉じており、DB 書き込み経路には
到達しない。復旧はアプリの再起動。

### 発生条件

`LazyColumn` は画面外に出た行のコンポジションを使い回す（reuse pool）。クラッシュには次の
**2 つが同時に**必要。

1. 記事一覧の scroll-into-view が動くこと — `ArticleListPaneContent` の
   `LaunchedEffect(selected?.id, …)` → `scrollToIndexIfNeeded`（`ui/home/HomeCommon.kt`）。
   **選択記事の id が変わったときだけ**再起動する。
2. 行のレイアウトノード数が一定以上あること。現在の `ArticleRow` は閾値を超えている。

自動再現ハーネス（後述）で測定した結果、**選択変更とホイールイベントが交互に 15 回以上**
続いたときに発生する。閾値は非常に鋭い。

| 交互に繰り返した回数 | 再現 |
| --- | --- |
| 14 | 0 / 5 |
| 15 | 5 / 5 |
| 20, 60 | 5 / 5 |

実際の操作に置き換えると、次のいずれか。

- **↓ / ↑ / J / K を押しっぱなし**にしたまま、ホイールやトラックパッドでスクロールする
  （OS のキーリピートは毎秒 25〜30 回なので、15 回は約 0.5 秒）。
- **macOS の慣性スクロールが効いている間にそれらのキーを押す**。フリックすると指を離した後も
  1 秒以上ホイールイベントが流れ続けるため、意識して同時操作しなくても重なる。実際に踏むとすれば
  これが最も可能性が高い。
- 記事を高速にクリックし続けながらスクロールする。ビューポート端で**部分的に見切れている行**の
  クリックでもスクロールが走る（`scrollToIndexIfNeeded` が no-op になるのは完全表示のときだけ）。
  右クリックも行を選択するので同様。

### 発生しない操作

以下は再現しないことを確認済み。「放置していたら落ちた」という壊れ方はしない。

- 選択変更が **1 回だけ**の場合。そのスクロールアニメーション中にホイールを回しても再現しない（0 / 5）。
- 一覧が空→非空になるとき（起動時の選択復元、未読のみ ON で自動更新が着弾した場合）（0 / 5）。
- バックグラウンド更新、クラウド同期、キャッシュ削除、既読/未読・スターの切り替え、
  「すべて既読」。いずれも選択記事の **id を変えない**ため、effect が再起動しない。

### 原因

Compose 自身のレイジーリスト項目再利用における内部不変条件違反。reuse pool から取り出した
`LayoutNode` が既に detach されているのに、`onReuse()` が attach 済みであることを表明している。
アプリ側に Compose API の誤用は見つからなかった — ユーザー操作によるスクロールとプログラムからの
スクロールが並行するのは正当な使い方であり、reuse pool を壊してよい理由にはならない。

この不具合は**潜在的なもので、行のノード数に敏感**である（行が何をしているかではない）。根拠として、
記事カードの**修正前**のメタ行（連結した 1 個の `Text`）を、現在と同じ `Row` / `Spacer` の入れ子で
包んだだけでも再現する。したがって、最初の報告の直前に入ったメタ行の修正はこの不具合を
**作り込んだのではなく**、行を閾値の向こう側へ押しやって露出させただけである。

同じ表明違反の上流報告:
[compose-multiplatform#3977](https://github.com/JetBrains/compose-multiplatform/issues/3977)、
[issuetracker 303256075](https://issuetracker.google.com/issues/303256075)。

### 緩和策: 行あたりのノード数を減らす

この不具合はノード数に敏感なので、`ArticleRow` の見た目を一切変えずにノード数を減らした。
固定の隙間を作るためだけの `Spacer`（それ自体が独立した `LayoutNode` を持つコンポーザブル）
3 個を、隣接要素への先頭/末尾 `Modifier.padding` に置き換え（モディファイアは既存の
`LayoutNode` に付くだけで新規ノードを作らない）、favicon を包んでいた `Box` も
`AsyncImage`/`Spacer` の直接選択に置き換えた。これにより常時ノード数は 12 から 8 まで下がり
——これはこの不具合を最初に露出させたメタ行修正**より前**の行（9 ノード）よりも少ない。
パターンの詳細は `ui-guidelines` スキルの「Gaps and node count」を参照。この行（および他の
`LazyColumn` 内の行）で、単なる固定の隙間のために `Spacer` を再び使わないこと。

実測した効果: 12 ノードの状態では `ArticleReuseCrashRepro` が閾値 15 回で 5/5 再現していた。
8 ノードへの削減後は、このハーネスでは全く再現しなくなった — 元の閾値の 10 倍（150 回連続、
3/3 で再現せず）まで確認済み。コミットしたテスト自体は 60 回（旧閾値より十分大きい）で走らせ、
これも 0/5。

**これは緩和策であり、修正ではない。** Compose 側の根本原因には一切手を付けていないため:

- 「絶対に発生しない」ことの証明にはならない — 現在の行構造では、ハーネスで試した最も
  厳しい条件でも踏まなかった、という以上の意味は持たない。
- 将来、この行（や同じ reuse pool を共有する他の `LazyColumn` の行）に再びノードを追加する
  変更が入れば、今回のメタ行修正のときと同じように、この余裕は再び失われうる。
- gutter `Box`（8dp 幅の star/unread ドット用コンテナ）は**変更していない** — 削除するには
  star アイコンと未読ドットの位置決めを `Box`/`align()` から手動オフセットに置き換える必要が
  あり、削減できるノード数に対してリスクが見合わないと判断した。

### 調査で除外したもの

いずれも決定的な再現条件に対して試し、影響が無いことを確認済み。

- `LazyListState` を共有している `VerticalScrollbarIfNeeded`。
- 行に付いている `Modifier.nativeContextMenu`（行ごとの `remember` / エフェクト / `pointerInput`）。
- Coil の `AsyncImage` — 再現条件では favicon が無くコンポーズされないため元から無関係。
- 行内の `stringResource` 呼び出し。
- Compose のバージョン不整合。`runtime` / `foundation` / `ui` は単一バージョンに解決されており、
  クラスパス上の `org.jetbrains.compose.*` と `androidx.compose.*` の desktop 成果物は
  **クラスを含まないエイリアス jar** なので重複クラス衝突は起きない。`compose-material3` のピンも
  要因ではない（そのバージョンが最新安定版で、1.10 / 1.11 は alpha しか出ていない）。

### 効果が無かった回避策

再度試さないように記録しておく。

- アニメーション付きスクロール（`animateScrollToItem` / `animateScrollBy`）を、即時の
  `scrollToItem` / `scrollBy` に置き換える。
- scroll-into-view の前に `isScrollInProgress` が false になるまで待つ。
- 上記 2 つの併用。
- 次の measure パスまでスクロールを遅延させる `requestScrollToItem`。
- Compose Multiplatform **1.12.0-beta03** への更新（依存グラフに実際に解決されたことを確認済み。
  それでも 5 / 5 で再現）。

唯一有効だったのは scroll-into-view 自体を取り除くことだが、それではキーボード操作時に選択記事が
画面外に留まってしまう。発生頻度の低さに対して割に合わないため採用しなかった。

### ライブラリ更新後の再検証

無効化した再現ハーネスを
`composeApp/src/desktopTest/kotlin/works/merc/keryx/app/ui/home/ArticleReuseCrashRepro.kt`
に残してある。実際のホイールスクロールと同じ desktop の経路を駆動するので、手動操作は不要。

**検証は現在の構成ではなく緩和策適用前の構成に対して行うこと** — 上記「実測した効果」の通り、現在の
8 ノード構成では上流の修正状況にかかわらずこのハーネスは既に再現しなくなっているため、そのまま
実行しても何も分からない。まず `ArticleRow`（`ArticleRowComponents.kt`）を緩和策適用前の構成に戻す
— 現在の `Modifier.padding` に畳み込んだ隙間と `AsyncImage`/`Spacer` の分岐ではなく、`Spacer` で
隙間を作り favicon を `Box` で包む元の形に戻す（favicon 分岐直前のインラインコメント、および
"cut ArticleRow's LazyColumn item node count" コミットに、戻すべき差分の詳細がある）。そのうえで
`@Ignore` を外して数回実行する。

```bash
./gradlew :composeApp:desktopTest --tests '*ArticleReuseCrashRepro*' --rerun-tasks
```

この緩和策適用前の構成で繰り返し成功するようになっていれば上流で修正済み。この項目を削除する
（あるいはテストは通常のリグレッションテストとして残す）とともに、`ArticleRow` は緩和策適用前の
構成のまま戻さず残し、`ui-guidelines` スキルの「Gaps and node count」節も削除する — この節はこの
緩和策を説明するためだけに存在する。まだ失敗するなら、`@Ignore` と緩和策の両方を元に戻す
（一時的な変更を取り消す）。

## 記事が未選択の状態から選択するとウインドウ全体がフリッカーする

**状態**: 解決済み — 記事リーダーのネイティブ WebView を、記事選択中だけコンポーズするのではなく
常時マウントしたままにすることで解決した。

### 症状

記事が未選択の状態（詳細ペインに「記事を選択してください」と表示）から記事をクリックすると、
**ウインドウ全体**が一瞬フリッカーした — 詳細ペインだけでなく、フィード一覧・記事一覧のペインも
含む。ライト／ダーク両テーマで再現した。既に選択済みの記事から別の記事へ切り替える場合は
一度もフリッカーしなかった。

### 診断

記事リーダー（`ui/home/ArticleDetailPane.kt`）は記事の HTML を `io.github.kdroidfilter.webview`
の `WebView` composable で描画しており、これは（`webview-compose-jvm.jar` と
`ui-desktop-1.11.1.jar` を逆コンパイルして確認した通り）ヘビーウェイトな AWT `SwingPanel` が
`WryWebViewPanel extends javax.swing.JPanel` をホストしたもの — 実 OS のブラウザサーフェス
（skiko の `HardwareLayer` である `java.awt.Canvas`）であり、Compose が描画するテクスチャでは
ない。

`androidx.compose.ui.viewinterop.SwingInteropContainer.executeScheduledUpdates()` は末尾で
次を実行する。

```text
root.validate()
root.repaint()
```

この `root` は**ウインドウ全体**の interop コンテナであり、このペインだけのものではない。この
ヘビーウェイトコンポーネントの追加／削除（`SwingInteropContainer.place()`／`unplace()`）と、
その移動（`SwingInteropViewHolder.layoutAccordingTo()` → `setBounds(...)`）はどちらもこの呼び出しを
スケジュールするため、いずれか一方だけでもウインドウ全体が 1 フレーム分再描画される。

このペインの以前の構造は、「記事未選択」状態に対して early return を持っていた。

```kotlin
if (current == null) {
    Box(...) { Text("記事を選択してください") }
    return
}
```

そのため記事が選択されるまでネイティブ WebView は一切コンポーズされておらず、未選択 → 選択の
遷移は**そのウインドウで初めて**ヘビーウェイトパネルを追加することになり、上記のウインドウ全体の
再描画を引き起こしていた。既に選択済みの記事同士の切り替えではこの経路を一切通らない — WebView が
継続してマウントされたままだからで、報告された症状（記事から記事への切り替えではフリッカーせず、
未選択から記事への切り替えでのみ発生する）と完全に一致する。同じ early return は `content` も
`summary` も無い記事（「本文なし」分岐）に対しても存在していたため、そのような記事との間の
切り替えでも同一のフリッカーが起きていた。

### 除外した仮説

- **Compose の再コンポーズ／レイアウトのやり直し** — `HomeScreen` は意図的に `selectedArticle`
  を収集していないため、記事選択はそのレベルではフィード一覧・記事一覧ペインを再コンポーズしない。
  このフリッカーは Compose 側のレイアウトのやり直しではない。
- **ペインのリサイズ** — フィード一覧・記事一覧ペインの幅は永続化された設定値であり、記事選択の
  影響を受けない。

### 解決方法

WebView を、ペインの生存期間中は `if` の下に置かず**常時無条件で**コンポーズするように変更した
（`ui/home/ArticleDetailPane.kt` の `ArticleDetailPaneContent`）。同一ウインドウ内でヘビーウェイトな
AWT サーフェスの上には Compose が描画するものを一切重ねられないため（このアプリのダイアログが
実際の `DialogWindow` である理由と同根 — `ui/common/KeryxDialogs.kt` 参照）、「記事を選択して
ください」のプレースホルダーと「本文がありません」の通知は、**同じ WebView の内部**に HTML として
描画するようにした（`ui/article/ArticleWebViewHtml.kt` の
`articlePlaceholderHtml`／`articleNoContentHtml`。実記事用の `wrapArticleHtml` と同じ `<style>`
ブロックを共有するため、ダークモードでデフォルトの白いページが一瞬出ることもない）。リーダー上部の
ツールバー（スター／未読に戻す／URL コピー／ブラウザで開く）も常時表示するようにし、未選択時は
ボタンを非表示にせず無効化した — これによりツールバーの Compose 構造が状態間で常に同一に保たれ、
結果として WebView のバウンズが動かなくなる（`layoutAccordingTo` によるバウンズ変更だけでも
同じウインドウ全体の再描画を引き起こしうるため）。

### 効果がなかった／試さなかった対処法

再試行を防ぐために記録しておく。

- **未選択時は WebView をマウントしたままサイズを 0 にする、または画面外に置く** — 効果がない:
  バウンズの変更は `SwingInteropViewHolder.layoutAccordingTo()` を経由し、パネルの追加／削除と
  同じウインドウ全体の `validate()`／`repaint()` をスケジュールしてしまう。
- **マウント済みの WebView の上に Compose のスクリム／プレースホルダーを重ねる** — 不可能:
  同一ウインドウ内では、ヘビーウェイトな AWT サーフェスは常に Compose の軽量なコンテンツより
  上に合成される。
- **`SwingInteropContainer.executeScheduledUpdates()` にパッチを当てる** — アプリケーションコードから
  到達できない（`ui-desktop-1.11.1.jar` の内部にある）。
- **このツールバーと記事一覧のヘッダー行とで `Modifier.height(...)` の共有定数を導入し、高さを
  「保証」する** — 検討したが不採用にした: 記事一覧のヘッダーは実テキストを含む `ToggleChip` を
  子に持ち、フォントスケール設定（このアプリは 0.8〜1.6 倍をサポート）が高い場合、アイコンのみの
  基準高さを超えて伸びうる。そこに固定の高さを共有で強制するとクリップしてしまう。詳細ペインの
  ツールバーにはそのような定数は不要 — 子はすべてフォントスケールの影響を受けない固定サイズの
  アイコンなので、状態間で Compose 構造をそのまま同一に保つだけで十分である。

## ダイアログがたまに想定外のサイズで開く

**ステータス**: 解決済み — ダイアログ自動フィットの「一発勝負・有界」な補正を、生存期間ずっと効く
ドリフトガードに置き換えた（`ui/common/KeryxDialogs.desktop.kt` の `DesktopModalWindow`、判断は
`ui/common/WindowGeometry.desktop.kt` の `nextDialogFit`）。同じバグに対する過去 2 回の修正試行が
今もそのファイルのコメントに残っていること、そして以下の根拠が逆アセンブルで得た Compose Desktop の
内部挙動であり、失うと再度導出し直すことになるため、記録として残す。

### 症状

設定ダイアログや About ダイアログを開くと、**たまに**内容が収まらないほど小さいウィンドウになる —
プレースホルダーの高さ 240dp のまま、macOS がサイズ未適用のダイアログに与える ~80x28、あるいは
設定のタブバーが末尾のタブを切り落とすほど狭い幅。発生は不定期で、一度起きるとそのダイアログの
生存期間中ずっと直らない（ダイアログは `resizable = false` なので、ユーザーがドラッグして直すことも
できない）。報告は macOS だが、機序はプラットフォーム非依存。閉じて開き直すとたいてい正常になるのは、
開くたびに新しい `DialogWindow` が作られてレースがやり直されるため。

### 診断

ダイアログは Compose のコンテンツを測定し、フィットしたサイズを `DialogState.size` に書くことで
サイズが決まる。`ui-desktop-1.11.1.jar` を逆アセンブルすると、Compose 側の実装はこうなっている:

```kotlin
// androidx.compose.ui.awt.SwingDialog の update ラムダ。UpdateEffect
//（SnapshotStateObserver → Channel）経由なので非同期
if (state.size != appliedState.size) { window.setSizeSafely(state.size, Floating); appliedState.size = state.size }

// androidx.compose.ui.awt.SwingDialog の ComponentAdapter
override fun componentResized(e) {
    currentState.size = DpSize(dialog.width.dp, dialog.height.dp)  // DialogState に書き戻す
    appliedState.size = currentState.size                          // かつ「適用済み」にする
}
```

`DialogStateImpl.size` は既定（構造的等価）ポリシーの `mutableStateOf` であり、`AwtWindow` は
`window.isVisible = true`（peer 生成 = ~80x28 の出どころ）を、最初の update を走らせたコンポジション
パスの後に、別コルーチンから呼ぶ。ここから 3 つの帰結が出る:

1. **同じ `DpSize` の再アサートは二重に無効。** `mutableStateOf` への同値書き込みは無効化を起こさず、
   仮に update ラムダが再実行されても `state.size == appliedState.size` でネイティブ呼び出しが
   スキップされる。従来の「フレームをまたいで再アサートする」ループは、実質ポーリングでしかなかった。
2. **そのループは一致した最初のフレームで監視をやめ**、再武装する仕組みが無かった。測定したコンテンツ
   サイズはウィンドウサイズから意図的に独立している（測定用 `Box` の `requiredWidthIn` /
   `requiredHeightIn`。これ自体が「幅が狭いまま固着する」という別バグの修正だった）ため、
   `capturedContentPx` は二度と変化せず `snapshotFlow` も再発火しない。
3. **Compose 自身も自己修復できない。** `componentResized` がネイティブのサイズを `DialogState.size` と
   自前の適用済みコピーの*両方*に書くので、裏側で着地したサイズは Compose から見て整合が取れている。

結果として、ループ離脱後に着地したサイズ適用は恒久化する。着地が離脱の前か後かは純粋にスケジューリング
次第 — これが不定期発生の正体であり、固着する値がプレースホルダーおよび未サイズ peer と一致する理由でもある。

同じ見た目になる独立した 2 つ目の欠陥もあった: フィットが `LocalDensity` を*呼び出し元*
（メインウィンドウ）のコンポジションから読みながら、*ダイアログ*のコンポジションで測定したピクセルを
変換していた。スケールファクタの異なる画面ではその比率分ずれる — オーナー density 2 / ダイアログ
density 1 なら 640dp 幅のコンテンツが 320pt のウィンドウになり、タブバーが切れ、内容が折り返して
高さが画面上限に張り付く。

### 除外した仮説

- **フィットしたサイズをもっと何度も／もっと長いフレーム数だけ再アサートする** — 同値書き込みは
  独立した 2 層で no-op（上記 1）なので、ループが実際に再適用できることは一度もなく、Compose 自身の
  非同期適用が着地するのを待っていただけだった。
- **有界ループのまま break を遅らせる／しない** — `withFrameNanos` はダイアログシーンの
  `BroadcastFrameClock` に駆動され、シーンが描画されている間しかフレームが来ない。ループ中に設定
  ダイアログが隠れたりトレイに最小化されたりすると、無期限に停止する。
- **コンテンツ測定を再トリガーにする** — 構造上不可能。`requiredWidthIn` / `requiredHeightIn` が
  測定値をコンテンツのみの関数にしている。外せば、それらが修正した「幅が狭いまま固着」が再発する。

### 解決方法

`DialogState.size` を観測値に含めた（`snapshotFlow { capturedContentPx to dialogState.size }`）ことで、
Compose 自身によるネイティブリサイズの書き戻しがそのままドリフトイベントになる — ダイアログの生存期間
ずっと、ポーリングなしで。各イベントで測定コンテンツから target を再計算し、ウィンドウが一致しなければ
`DialogState.size` を書き（peer 未生成のウィンドウを pack してくれる経路）、さらにサイズを AWT ウィンドウへ
直接押し込む（同値書き込みが no-op になる穴を塞ぐ）。その後 `componentResized` が Compose の状態を
同期し直し、ガードを再武装する。collect 本体は suspend しないので、フレームクロックに依存せず再入もしない。
`nextDialogFit` は target ごとに補正回数を制限し、ジオメトリを拒否するウィンドウマネージャーとの
無限往復を防ぐ。諦める際はサイレントにせず `Log.warn` を 1 回出す。また target が変わったときだけ
再配置するので、ユーザーがドラッグしたウィンドウがドリフト補正で引き戻されることはない。
density はダイアログ自身のコンポジションから読むようにした。

直接押し込みは**サイズと位置をまとめて 1 回の `setBounds`** で行う必要がある（`applyWindowGeometry`）。
最初の版はサイズだけを AWT に押し込み、位置は `DialogState` 任せにしていたため、サイズは同期的に、
位置は `UpdateEffect` の `Channel` を 1 ホップ跨いでから届いていた。その隙間で描画されたフレームは
ダイアログを最終サイズで、しかし AWT が新規 `Window` に与える位置 — スクリーン原点 + スクリーン
インセット、つまり画面左上 — に表示し、そこから中央へ飛んで見えた（初期位置のオフセットは
`java.awt.Window.init` によるもので、Compose 自身の `WindowLocationTracker.getCascadeLocationFor` も
同じ基準点を使う）。サイズのときと同様、その隙間にフレームが入るかはスケジューリング次第なので、
これも不定期発生だった。

### 残存する制約

`COMPONENT_RESIZED` を発火しないネイティブリサイズはガードから見えない。恒久ポーリング以外に検知手段が
無く、Skia シーン自身のサイズも同じ peer イベント由来なので `onSizeChanged` を足しても得るものがない。
許容する。

## Linux: モードレスダイアログ（設定・About）の横幅がほぼ 0 まで縮む

**ステータス**: 解決済み — `fitWindowSize`（`ui/common/WindowGeometry.desktop.kt`）が要求ウィンドウの
**横幅**を測定値から導出することを完全にやめ、常にダイアログ固有の固定幅を使うようにした（測定値は
高さの算出にのみ使う）。これにより、ウィンドウマネージャが一時的に狭いクライアント領域を報告する
きっかけが何であれ、以下で説明する連鎖経路自体が構造的に成立しなくなった。

### 症状

Linux（KDE Plasma、Wayland/XWayland で再現）で設定ダイアログまたは About ダイアログを開くと、最初は
正しい幅で表示されるが、1〜2 秒かけて**ウィンドウの外枠自体**（描画内容だけではない）が徐々に狭くなり、
ほぼ 0 ピクセルまで潰れる。ダイアログは `resizable = false` なのでユーザーがドラッグで戻すことはできず、
閉じて開き直しても再現する。macOS では一度も観測されていない。同じ
`KeryxAlertDialog`/`DesktopModalWindow` の仕組みに乗っている他のダイアログ — フィード追加、
フォルダー・タグの追加/編集/削除、フィード名変更、各種確認ダイアログ — はすべて無関係だった。

### 診断

実機ログの 1 行が、「WM が外から潰している」のではなく「アプリ自身が縮小を要求している」ことを示す
最初の決定的な証拠になった:

```text
WARNING [KeryxDialogs] Dialog stayed at 11.0.dp x 535.0.dp after 5 attempts to fit 1.0.dp x 535.0.dp
```

高さは target/actual とも 535dp で一致しており（高さの自動フィットは正常に機能していた）、幅だけが
乖離していた。しかも重要なのは、**要求サイズ（target）自体**が —— 実際に適用できたサイズだけでなく
—— 1.0dp まで劣化していたことである。

再現する 2 つ（設定、About）と再現しない残り全部（他の `KeryxAlertDialog` 呼び出し元すべて）を比較すると、
コード上の違いはただ 1 点に一致した: `modal = false` を明示的に渡しているかどうか —— `AboutDialog` は
明示的に渡し、`KeryxTabDialog`（設定の実装）は常にそうする —— それ以外の呼び出し元は全員デフォルトの
`modal = true` のまま。`DesktopModalWindow` 内でこのフラグが実際に AWT に渡る形で変えるのは
`modalityType`（`DocumentModal` vs `Modeless`）だけであり、`resizable` も実質的な装飾も両方同じで、
どちらの分岐も最終的に同じ `SwingDialog`/`ComposeDialog` 実装を通る（Compose Desktop 1.11.1 の
sources jar を読んで確認済み）。

機序そのものは自己増幅する測定ループだった:

1. `fitWindowSize` は要求幅を、測定用 `Box` の測定幅（`contentPx.width.toDp()`）だけから計算していた。
   その `Box` は `requiredWidthIn(max = initialWidth)` で上限は固定していたが下限が無く、クランプ先の
   incoming 制約は測定時点のウィンドウのクライアント領域そのものだった。
2. Linux では、モードレスダイアログが配置される際にクライアント領域が要求値より一瞬狭く報告される
   （document-modal では起きないらしい — 両者の違いは AWT の modality フラグ 1 点のみ）。その一瞬狭い
   incoming max の下で、`Box` 内の enforce-incoming な `Column(Modifier.width(initialWidth))` も
   `initialWidth` より狭く測定されてしまう。
3. その狭い測定値が `componentResized` → `DialogState.size` → ドリフトガードの `snapshotFlow`
   コレクターを通じて、そのまま次に要求するウィンドウ幅として AWT に書き戻される。
4. 狭くなったウィンドウは次のティックでさらに狭いクライアント領域を報告し、ループが繰り返される —
   狭いウィンドウ → 狭い測定値 → 狭い要求 — と縮小が複利で効いて ~1dp まで落ち込む。

`nextDialogFit` の「ターゲットごと 5 回まで」の補正上限はこれを止められなかった。**target 自体**が
ほぼ毎ティック変わり続け（狭くなるたびに異なる `DpSize` になる）、ターゲットが変わるたびに補正予算が
無条件でリセットされていたため、上限は実質的に一度も効かないまま、ループが AWT の実質的な下限に
自然に落ち着くまで続いた。

### 除外した仮説

- **ウィンドウマネージャが外側から外枠を強制的に縮めている** — ログにより否定: アプリが要求している
  **target 自体**が、実際に適用できたサイズだけでなく 1dp まで劣化していた。これはウィンドウマネージャが
  大きいサイズを拒否しているのではなく、アプリ自身が極小の幅を要求している状態である。
- **設定ダイアログ固有の原因**（タブ内容が ViewModel の状態読み込みで高さを変え、フィットのターゲットを
  何度も動かしているなど） — `AboutDialog` が完全に静的な内容にもかかわらず同一の症状を再現することで
  否定された。両者はコードや内容・ライフサイクルを何も共有しておらず、唯一の共通点は
  `DesktopModalWindow` の `modal = false` 分岐を通ることだけだった。

### 解決方法

- `fitWindowSize` は測定幅を一切入力に取らなくなった。呼び出し側の `initialWidth` をそのまま
  `contentWidth` として直接使い、測定した `contentPx` は本来自動フィットすべき唯一の軸である高さにのみ
  使う。これにより、ウィンドウマネージャが配置中に一瞬狭いクライアント領域を報告しても、それが次の
  要求幅になることが構造的にあり得なくなった —— Linux に限らず、`resizable = true` のダイアログが
  将来登場したとしても同様に有効。
- 本来幅・高さ両方に関わるサイズ比較に対して、高さだけの装飾補正を流用していたこと自体が独立した
  非対称の不具合だった（外形ウィンドウ幅を、装飾を一切考慮しないコンテンツ幅の target と比較していた
  —— この不具合と無関係に、設定のタブバー末尾が数 px 切れる窮屈さの原因でもあった）。
  `decorationAllowanceFor` を新設し、ウィンドウの実測 `insets` から幅・高さ両軸の `DpSize` 補正を計算し、
  insets がまだ全ゼロ（ウィンドウマネージャがまだ reparent/装飾していない、既知の AWT/X11 のタイミング
  クセ）の間だけ従来の高さのみの固定推測値にフォールバックするようにした。
- `nextDialogFit` の補正予算は、ターゲットが変わるたびに無条件でリセットするのではなく、**直前の
  ターゲットに実際に到達していた場合のみ**（`DialogFitState.targetReached`）リセットするようにした。
  動き続けるターゲットが上限を無期限に回避できていた穴を塞ぐもので、上記の幅の修正とは独立に効く
  防御であり、将来何らかの変更でターゲットが自力でドリフトするケースが再発しても機能する。
- `applyWindowGeometry` にオプションの `minSize` フロアを追加し、`DesktopModalWindow` は生成時に
  `window.minimumSize` も設定するようにした。どちらも最後の安全網であって本命の修正ではないが、
  `resizable = false` である以上、将来同様の潰れが起きた場合にユーザー側で回復できない点を踏まえて
  持たせている。

## ダイアログを開いたときフリッカーし、内容が一瞬別の位置に表示される

**状態**: 解決済み — ドリフトガードがジオメトリの確定を報告する（`DialogFitDecision.presentable`）まで
ダイアログの OS ウィンドウを可視化しないようにし、あわせてネイティブウィンドウ自身の背景をダイアログの
コンテナ色でプリフィルすることで解決した。上記の「ダイアログがたまに想定外のサイズで開く」「Linux:
モードレスダイアログ（設定・About）の横幅がほぼ 0 まで縮む」と同じ `DesktopModalWindow` の自動フィット
機構を巡る同一ファミリーの3件目だが、経路は既存2件のどちらとも異なる —— 既存2件が「最終的にどのサイズに
落ち着くか」の問題だったのに対し、本件は「そこへ至る途中で画面に何が出るか」の問題である。根拠が
Compose Desktop の sources jar から読み取った内部実装であり、再調査コストが高いためそのまま残す。

### 症状

ダイアログを開いたとき、**時々**フリッカーが発生する。macOS の**ライトモード・ダークモード両方**で
発生し（ダークのほうが目立つ）、設定 / About / フィード追加 / 名前変更 / 各種削除確認 —— つまり
`ui/common/KeryxDialogs.desktop.kt` の共通基盤（`DesktopModalWindow` / `KeryxAlertDialog` /
`KeryxTabDialog`）に載っている**すべてのダイアログ**で起きる。

ダイアログ全体ではなく一部。報告内容は「**コンポーネントが一瞬別の場所に表示された**ように見える」で、
ダークモードではさらに明るい帯が一瞬見える。整定してしまえば消える。

### 診断

主因は色ではなく**ジオメトリの飛び**であり、テーマに依存しない構造的な問題である（だからライトモードでも
再現する）。

`DesktopModalWindow` はウィンドウを必ず `placeholderSize(initialWidth)` = ダイアログの固定幅 ×
**プレースホルダー高さ 240dp** で生成し、`resolvePosition` は**その 240dp を前提に**オーナー中央へ配置
する。実サイズへの補正はその後に走るドリフトガードの担当である。`ui-desktop-1.11.1-sources.jar` を
読むと、その間に何が起きるかが確定する:

| 段階 | Compose Desktop 側の実装 | 効果 |
| --- | --- | --- |
| 1 | `AwtWindow` の `DisposableEffect(Unit)` → `create()` | `ComposeDialog` を生成。`setContent` はラムダを保持するだけ |
| 2 | `UpdateEffect` → `SwingDialog.update` → `setSizeSafely` | まだ displayable でないので `pack()`（"Pack to allow drawing the first frame"）→ `ComposePanel.addNotify()` → **ここで初めて composition と measure が走る** |
| 3 | `if (!wasDisplayable && it.isDisplayable) it.renderImmediately()` | プレースホルダー 240dp のフレームを描画 |
| 4 | `AwtWindow` の `DisposableEffect(visible)` → `GlobalScope.launch(MainUIDispatcher) { window().isVisible = true }` | **別の EDT tick でウィンドウを可視化** |
| 5 | Keryx のドリフトガードが `capturedContentPx` を受けて `applyWindowGeometry` | 実サイズ高さへリサイズ＋**実サイズ基準で再センタリング** |

4 と 5 の順序は純粋にスケジューリング依存であり（上記2件と同種の競合。「時々」なのはこのため）、4 が
先に来ると、240dp・240dp 基準の中央位置のフレームが一瞬表示されてから 5 が到着する。カードは
ウィンドウ内で `Alignment.TopCenter` に配置されるため、240dp から実サイズ H へ拡大しつつ再センタリング
すると、カードの上端は `(H − 240) / 2` dp だけ上へ移動する（H=500dp なら 130dp）。これが「コンポーネントが
一瞬別の場所に表示された」の正体である。

`KeryxTabDialog`（設定）は `repositionOnResize = false` だが例外ではない: 初回 tick では
`nextDialogFit` が `applyPosition = !state.positionApplied` すなわち true を返すため、ちょうど1回だけ
再センタリングされる —— そしてここで問題になるのはその1回である。

報告の「色」の側面、および**表示済みダイアログ**の内容駆動リサイズ時（フィード追加の候補リスト出現、
設定のタブ切替、名前変更ダイアログの supporting text の出入り）に同じちらつきが出ることについては、
以下の3つが寄与している:

- **ダイアログのネイティブ AWT ウィンドウ背景がテーマ色で塗られていなかった。** `main.kt` はメイン
  ウィンドウについてまさにこの目的で同じことをしている（"Paint the native window/content-pane with the
  theme surface so a dark-mode launch doesn't flash the platform-default (light) background"）が、
  `DesktopModalWindow` の `remember(window)` ブロックは `minimumSize` と macOS の `apple.awt.*`
  クライアントプロパティしか設定していなかった。そのためリサイズで新たに露出した領域は、Compose が
  塗り直すまで L&F 既定（明るい）で塗られる。
- **ネイティブボタン行が Compose キャンバスに透明の穴を開ける。** `NativeButtonRow` は実 `JButton` を
  `SwingPanel` で埋め込み、上流の `SwingInteropViewHolder.init` はその矩形を `BlendMode.Clear` で
  くり抜くため、全面 `Box` もカードの `Surface` もそこを塗らない。さらに
  `SwingInteropViewHolder.layoutAccordingTo` は `container.scheduleUpdate` で非同期に境界を更新し、
  `SwingInteropContainer.executeScheduledUpdates()` は最後にダイアログルート全体の
  `root.validate(); root.repaint()` を行う。その間、穴からは下地 —— つまり上記の未設定なネイティブ背景 ——
  がそのまま見える。これは独立した原因ではなく、前項に**従属する**現象である。
- **全面塗りの色とカードの色が違っていた。** 全面 `Box` は `surfaceContainerLow` 固定である一方、
  `KeryxAlertDialog` は既定で `surface`、`KeryxTabDialog` も `surface` をハードコードしていたため、
  ウィンドウの現在サイズと測定済みコンテンツの差分（整定中の余剰領域）が別トーンの帯として見えていた
  （M3 ダークで `#141218` vs `#1D1B20`）。

4つめは塗りではなくフィードバックループである: `NativeButtonRow` の `SwingPanel` の `update` ブロックは
`panel.revalidate()` を**無条件に**呼んでいた。呼び出し側は `onConfirm` / `onDismissRequest` に
非 `remember` のラムダを渡すため Compose の再コンポジションスキップが効かず、このブロックは親の
あらゆる再コンポジション（例: `confirmEnabled` が変わる `TextPromptDialog` の毎打鍵）で実行される。
`SwingInteropViewGroup.invalidate()` は `layoutNode.invalidateMeasurements()` を呼び、
`AwtContentMeasurePolicy` はノードのサイズを `component.preferredSize` から決めるため、そのたびに
Compose 再 measure → ウィンドウリサイズ → `componentResized` → ドリフトガード再起動 → `SwingPanel`
再配置 → `root.validate()/repaint()` という経路が回りうる。

### 除外した仮説

- **「単なるダークモードの色のちらつき」** — 報告者がライトモードでも観測していること、および報告内容が
  色ではなく位置についてであることから否定。上記の色の寄与は実在するが副次的。
- **`SwingPanel` の `background` 既定値 `Color.White` が実際に白く描画されている** —
  `SwingPanel.desktop.kt` を読んで否定: 内部の update は `it.background = background.toAwtColor()` を
  実行した後、**同一呼び出し内で**呼び出し側の `update` を続けて実行し、Keryx 側の update がそこで
  `panel.background = awtBackground` を設定するため、既定値で描画されるフレームは存在しない。
  `background` の明示指定は、この順序に依存しないための保険として採用したものであり、修正の本体ではない。
- **特定のダイアログ固有、あるいは `modal = false` 分岐固有の原因** — 上記の Linux 幅潰れの件を特定した
  切り分け基準は本件には当てはまらない: モーダルな `KeryxAlertDialog`（名前変更・削除確認）でも
  設定 / About と同様に再現する。

### 効果がなかった／見送った対処法

- **プレースホルダー高さをより良い値に推測して開く** — 原理的に不十分: 実サイズの高さはコンテンツを
  測定するまで分からず、上表のとおり測定は `pack()` でピアが実体化してから初めて走る。ダイアログごとの
  ハードコード推測値にすると、フォントサイズ設定やロケールごとに再調整が必要になる。
- **表示済みダイアログの内容駆動リサイズ時にも一旦隠す**（候補リスト出現、タブ切替） — 見送り: 小さな
  飛びを「消えて出直す」に置き換えるだけであり、「設定のタブ切替で上端を動かさない」という既存の意図的な
  仕様とも直接衝突する。この経路は隠すのではなく、背景・色の修正で綺麗に描画されるようにした。
- **可視化前にジオメトリをより強く再適用する** — 本ファミリー1件目で既に否定済み: 同値の書き込みは
  独立した2層で no-op になるため、実質ポーリングにしかならない。今回のゲートはガードと競争するのではなく、
  ガード自身の判断を待つ。

### 解決方法

- `DialogFitDecision` に **`presentable`** フラグを追加した（`WindowGeometry.desktop.kt`）。これ以上
  補正することがない状態 —— ウィンドウが既にターゲットに一致しているか、補正予算を使い切ったか —— で
  true になる。`DesktopModalWindow` は両 `DialogWindow` オーバーロードに `visible = readyToShow` を渡し、
  ガードが最初に `presentable` を報告した時点で `readyToShow` を立てる。その直前に
  `window.renderImmediately()` を呼ぶので、**最初に見えるフレームが既にフィット後のフレーム**になる。
  これは上表の段階3で Compose Desktop 自身が同じ理由で使っている API を、プレースホルダーのフレームから
  フィット後のフレームへ移して適用したものである。上表の 1〜3 と 5 はすべて不可視のうちに完了するため、
  プレースホルダーのフレームが画面に出ることはなくなった。`presentable` は補正を諦めたケースでも
  意図的に true にしてあり、要求ジオメトリを拒むウィンドウマネージャ環境でもダイアログが永久に出ない
  ことはない。さらに、コンテンツが有効な高さを一度も報告しない病的ケースに備えて
  `DIALOG_PRESENT_FALLBACK_MS`（500ms）の `LaunchedEffect` を安全網として置いている。
- `DesktopModalWindow` に **`containerColor`** パラメータを追加した（既定 `Color.Unspecified` →
  テーマの `surface`）。自身の `KeryxTheme` スコープ内で解決し、全面 `Box`（ハードコードの
  `surfaceContainerLow` を置き換え、トーンの帯を解消）と、ネイティブの `window.background` /
  `contentPane.background`（`main.kt` と同じ手法）の両方に使う。`LaunchedEffect` ではなく
  `remember(resolvedColor)` でコンポジション中に同期適用しており、これは `main.kt` が記録している
  "as early as possible" と同じ判断による。色をキーにしているので実行中のテーマ切り替えにも追従する。
  `KeryxAlertDialog` は受け取った `containerColor` をそのまま転送するだけ、`KeryxTabDialog` は既定値が
  既にハードコードしていた `surface` に解決されるため無改修。
- `NativeButtonRow` は `SwingPanel` に `background = backgroundColor` を明示的に渡すようにし（保険。
  「除外した仮説」参照）、`revalidate()` は**レイアウトに影響する値が前回の `update` から実際に変わった
  ときだけ**呼ぶようにした（確定ボタンのラベル、およびキャンセルボタンの表示可否を兼ねる却下ラベル）。
  `isEnabled` / `foreground` の変更は repaint のみで足りるため、打鍵が `invalidateMeasurements()` に
  到達することはなくなった。前回値は、意図的に snapshot state ではないプレーンなホルダーで保持している:
  `SwingPanel` の update は `InteropViewHolder` の `SnapshotStateObserver.observeReads` の内側で走るため、
  ここで `mutableStateOf` を読むと購読が登録され、書くと更に別の interop update ——
  すなわち `SwingInteropContainer` のルート `validate()`/`repaint()` —— がスケジュールされてしまい、
  まさに削ろうとしている処理を呼び戻すことになる。
- `WindowGeometryTest` に `presentable` のケースを追加した（補正が残っている間は false、サイズが
  landed したら true、補正上限を使い切ったら true、`FIT_TOLERANCE` 以内の差分なら true）。背景の
  プリフィルと `revalidate()` の条件化は実ネイティブピアに対する挙動で純粋関数として切り出せないため、
  `docs/testing.md` に既に記録されている「実 `DialogWindow` へのサイズ適用」と同じ理由により手動確認に
  委ねる。

### 残存する制約

**既に表示されているダイアログ**が、内容の変化でサイズを変える場合（フィード追加の候補リスト、
テキスト入力ダイアログの補助テキスト）は、仕様どおり画面上でリサイズされる —— 上記の見送った対処法を
参照。この場合に変わったのは、新たに露出する領域とボタン行の interop の穴が、L&F 既定ではなく
ダイアログ自身の色で塗られる点だけである。なお macOS では、そのリサイズの際に内容が一瞬ずれて見える
という別の不具合もあった —— 次の項目を参照。そちらは「リサイズをアーティファクトなしにする」のではなく、
設定ダイアログのタブ切替リサイズ自体をなくすことで解決している。

## macOS: 設定のタブを切り替えると内容がウインドウ上端側へ跳ねる

**ステータス**: 解決済み —— `KeryxTabDialog` のタブ内容領域を**固定高**にすることで解決した
（`ui/common/KeryxDialogs.desktop.kt` の `KERYX_TAB_DIALOG_CONTENT_HEIGHT`）。タブを切り替えても
OS ウインドウのリサイズが一切起きなくなる。上の3項目と同じ系統の4件目であり、とりわけ直前の項目の
「残存する制約」が残していた不具合そのものである —— 先行3件が「ダイアログが最終的にどのサイズに
なるか」と「開くまでの間に何が画面に出るか」の話であったのに対し、本件は**既に表示されている
ダイアログがリサイズされる間**に何が画面に出るかの話である。一見自明な対処法を先に試して不具合を
恒久化させてしまったこと、また根拠が配布 dylib から読み出した skiko のネイティブコードであり
再調査すると同じ手間がかかることから、詳細を残す。

### 症状

設定ダイアログのタブを切り替えるとフリッカーが発生した。タブのラベル（およびカード全体）が一瞬
ウインドウ上端側へずれて見え、その後元の位置に戻る。macOS で報告。ダイアログを最初に開くときには
発生せず（そちらは上記の「フィット完了まで不可視にするゲート」でカバー済み）、既に表示されている
ダイアログのタブ切替時のみ発生し、高さの差が大きいタブ間ほど目立つ。

### 原因

ダイアログの高さは選択中タブの内容に追従していた。そして処理順は構造上こう決まっている:

1. タブが切り替わり、新しいタブの内容が composition・測定され、**前のタブの高さのままのウインドウに
   描画される**。
2. `onSizeChanged` / `onGloballyPositioned` がその内容高さを publish するため、drift guard の
   `snapshotFlow` コレクタが走るのは、そのフレームの**後**になる。
3. guard が新しい目標サイズを計算し、`setBounds` で適用する。

つまりリサイズは必ず「誤ったウインドウ高さで描かれたフレーム」の後追いになる。これが macOS で
可視化される理由は、Skia のサーフェスがどこにあるかにある。配布されている
`libskiko-macos-arm64.dylib`（0.144.6）を逆アセンブルすると、`createMetalDevice` が
`AWTMetalLayer`（`CAMetalLayer`）を **AWT コンテンツビューのレイヤーのサブレイヤー**として追加し、
`autoresizingMask = kCALayerWidthSizable|kCALayerHeightSizable`、
`contentsGravity = kCAGravityTopLeft` を設定していることが分かる。そして `layer.frame` と
`drawableSize` を設定する `resizeLayers`（skiko 内でこのフレームを触る唯一の箇所）は、
`MetalRedrawer.syncBounds` からのみ呼ばれ、そこでは **Java 側**の AWT bounds からフレームを
計算している（`y = rootPane.height - globalPosition.y - layer.height`）。

一方で `setBounds` が実際の NSWindow に届くのは非同期である。`LWWindowPeer.setBounds` はプラット
フォームウインドウへ転送するだけで、意図的に自身の bounds を古いまま残す（「ネイティブが制約する
可能性があるのでコールバックで更新する」）。`CPlatformWindow.setBounds` も
`nativeSetNSWindowBounds` を EDT 外で実行する。`syncBounds` の呼び出し箇所は skiko 全体で4つ
だけ（`backedLayer.reshape`、isShowing の変化、redrawer の再生成、`SkiaLayer.reshape` の
DIRECT3D 限定分岐）で、**どれもネイティブリサイズを観測しない** —— レイヤーのフレームは AWT の
レイアウトパスからしか再計算されない。よってウインドウが画面上でサイズを変えてから次のレイアウト
パスまでの間、レイヤーの幾何は前のサイズのものであり、上端固定の内容がウインドウ上端より上に出得る。
これが「跳ね」である。

### 一見自明な対処法は恒久化させる —— 再試行しないこと

リサイズ直後にレイアウトパスを強制する（`applyWindowGeometry` の直後で `window.validate()` →
`window.renderImmediately()`）のは skiko 自身が `SkiaLayer.reshape` で行っていることだが、
そこには理由があって fence されている —— この組み合わせは
`if (renderApi == GraphicsApi.DIRECT3D && isShowing)` の場合のみ実行され、「逆向きのグリッチを
引き起こす」というコメントが添えられている。Metal では、その逆向きのグリッチが一時的では済まない:

- `validate()` は NSWindow がまだ旧サイズのうちに走るため、`syncBounds` はまだ旧サイズの
  スーパーレイヤーに対して新しい高さのフレームを設定する —— すなわち上マージンが Δ の**負値**になる。
- CALayer の springs-and-struts は Y 方向の両マージンを保持するため、ネイティブリサイズが着地すると
  レイヤーの高さが Δ を**もう一度**吸収する。フレームは `newH + Δ` になり、`drawableSize` は
  `newH` のまま。`kCAGravityTopLeft` により内容はウインドウ上端より Δ 上に固定され、下端には
  Δ 分の素のウインドウ背景の帯ができる。
- そして何も是正しない。ネイティブリサイズ着地時に AWT は `COMPONENT_RESIZED` を**投げる**
  （ピア自身の bounds は古いままなので `notifyReshape` は「すべて同期済み」の早期 return を通らない）、
  `java.awt.Window.dispatchEventImpl` も `invalidate(); validate();` を行う。しかし Java 側の値は
  すべて既に最終値なので、`Container.validateTree` は（有効な）子へ再帰せず、
  `SkiaLayer.doLayout` は二度と走らず、`syncBounds` も二度と呼ばれない。

実際にその通りに観測された: タブバーと macOS のマージされたタイトル行がウインドウ上端より上に
切れ、下端に背景色の帯が出て、ずれ量は切替ごとに異なり、タブを再度切り替えるかダイアログを開き
直すまで直らない。1フレームのフリッカーを、そのタブを表示している間ずっと残る不具合に変えてしまった。

`renderImmediately()` 単体でも救えない。これは描画しかせず（`SkiaLayer.renderImmediately` →
`Redrawer.renderImmediately`、`MetalRedrawer` では `update()` + `performDraw()`）、レイヤーの
フレームもシーンのサイズも再同期しない。ドキュメント上の契約も「displayable だが未表示の
ウインドウに1回だけフレームを描く」用途であり、本ファイルで今なお使っているのはその用途だけである
（上記の `presentable` ゲート）。

### 切り分けで否定した仮説

- **Material 3 タブバーへの移行**（`SecondaryScrollableTabRow`、コミット `6fb2c15`）—— 報告が
  その直後だったため第一の容疑だった。`ScrollableTabRowImpl` は行の高さを**全**タブの intrinsic
  高さの `max` から求めるため、どのタブを選んでも 72dp 固定であり、その `onLaidOut` が
  スクロールさせるのはタブ行であってウインドウではない。連動して見えるタブ毎の高さ追従は、
  リポジトリの最初のコミットより前から存在する。
- **macOS のマージされたタイトル行が1フレーム消える** —— ちょうど 28dp であり、報告も「タブの
  ラベルが上端へ移動」だったため有力に見えた。だが起こり得ない: この行は
  `selectedLabel != null` のとき常に描かれ、`SettingsDialog` の `onSelectTab` は `tabs` に
  含まれる id しか渡せない。
- **第2のリサイズ経路** —— Compose 自身の `SwingDialog.update` → `setSizeSafely` は毎回のタブ
  切替で再実行される（ウインドウタイトルが選択タブに追従するため）が、要求するサイズは guard が
  直前に適用したものと同じなので `Component.reshape` が早期 return し、ネイティブ呼び出しは起きない。
- **リサイズのアニメーション化** —— どのステップも同じ「1フレーム遅れのレイヤー」を抱えるため、
  アーティファクトを消すのではなく引き伸ばす。
- **リサイズの間ウインドウを隠す** —— 上の項目で既に見送り済み。消えて再出現する方がジャンプより悪い。

### 解決方法

`KeryxTabDialog` のタブ内容領域を固定高にした（`KERYX_TAB_DIALOG_CONTENT_HEIGHT` = 416dp。
`fontScale = 1.0` における最も高いタブの自然な内容高さ＋余裕）。これで全タブの測定結果が同一になり、
タブ切替でウインドウがリサイズされることがなくなる。このアーティファクトは「表示中のウインドウを
リサイズすること」と不可分であり、順序を強制すると悪化するため、リサイズをなくすことがアプリ
コードから可能な唯一の対処である。

トレードオフは意図的に受け入れている: 最も短いタブ（通知。内容は 100dp 未満）では領域の大部分が
ダイアログ自身の背景として見える。領域より高い内容 —— 将来タブが育った場合や、`fontSizeScale` が
大きい場合 —— は元々あった `verticalScroll` でスクロールするので、定数を超えても壊れるのではなく
緩やかに劣化する。定数の引き上げは見た目の追随作業であり、正しさの修正ではない。

「そのセッションで訪問した中で最も高いタブ」までウインドウを伸ばす案も検討したが、実際には
優位性がないため見送った: `general` は最も高いタブであり同時にダイアログが最初に開くタブなので、
どうせ最初のフレームで同じ高さに達する一方、通知からのディープリンク時にはリサイズが残る。

### 残存する制約

他のダイアログは依然として表示中にリサイズされる —— フィード追加ダイアログで候補リストが現れる
とき、テキスト入力ダイアログで補助テキストが出入りするとき —— ので、macOS では同じ1フレームの
ずれが出得る。ただしそこでは目立ちにくく（打鍵に応じて1回起きるだけで、2クリックのタブ切替のように
繰り返されない）、本修正はそれらには一般化できない —— サイズを合わせるべき状態の有限集合が
存在しないためである。

## Windows: 記事リーダーの WebView が一度も描画されず、どこかをクリックするとアプリがフリーズする

**Status**: 解決済み —— リーダーの WebView（`ArticleDetailPane.kt` の `ArticleWebView`）に、書き込み
可能な `desktopWebSettings.dataDirectory` を明示的に設定することで解決した。Windows だけに絞らず
デスクトップ 3 OS 共通で適用している。ライブラリ側がこのパラメータを OS 分岐なしに一律で読むためで、
macOS/Linux でこれまで問題が出ていなかったのは、たまたまそれぞれの暗黙のデフォルトが書き込み可能な
場所に解決していたに過ぎない。

### 症状

Windows で、記事詳細ペインの幅とほぼ同じ幅の白い矩形がフィード一覧ペインの位置に表示され、本来の
リーダー領域自体は空白のままだった —— 記事本文はおろか、リーダーが自身の中に HTML として描く
「記事が選択されていません」のプレースホルダすら出ない。この状態でウィンドウのどこかをクリックすると、
以降アプリが完全に応答しなくなった。macOS / Linux では再現しない。

### 診断

`WRYWEBVIEW_LOG=1`（`io.github.kdroidfilter:composewebview` 自身が持つ環境変数ゲート付きログ）を
付けて実行したところ、見えている症状よりも一段階手前の、本当の失敗が判明した:

```text
[WryWebViewPanel] createIfNeeded handle=459234 parentIsWindow=true size=280x291
Exception in thread "AWT-EventQueue-0" io.github.kdroidfilter.webview.wry.WebViewException$WryException:
v1=WebView2 error: WindowsError(Error { code: HRESULT(0x80070005), message: "Access Denied." })
        at ... NativeBindings.createWebview-E7Fn0XA(WryWebViewPanel.kt:787)
        at ... WryWebViewPanel.createIfNeeded(WryWebViewPanel.kt:398)
        at ... WryWebViewPanel.scheduleCreateIfNeeded$lambda$0(WryWebViewPanel.kt:589)
        at java.desktop/javax.swing.Timer.fireActionPerformed(Timer.java:289)
        ...
        at java.desktop/java.awt.EventDispatchThread.run(EventDispatchThread.java:90)
```

ネイティブの WebView2 サーフェス自体が一度も生成されていなかった —— 位置がズレていたのではない。
位置を設定する呼び出し（`WryWebViewPanel.updateBounds()` の `setBounds`）自体が一度も走っていない。
`webviewId` が null の間は早期リターンするためである。白い矩形の正体は、ライブラリがネイティブ
サーフェスを乗せるために使う素の `java.awt.Canvas` だった（Windows だけ `SkikoInterop.createHost()`
が Skiko の `HardwareLayer` ではなく素の `Canvas` を返す。WebView2 はトップレベルウィンドウの HWND を
親にして自前で位置をミラーリングする方式のためで、`resolveParentHandle()`/`boundsInParent()` 参照）。
そこには何も描画されていなかった。

**根本原因**: `PlatformWebSettings.DesktopWebSettings.dataDirectory` はデフォルト `null` で、Keryx の
`ArticleWebView` はこれを一度も設定していなかった。明示的なディレクトリがないと、WebView2 は実行
ファイルの隣に自分のデータフォルダを作ろうとする —— 今回は `C:\Program Files\Java\jdk-25.0.4\bin\java.exe`
（起動ログの `Acquired single-instance lock; running as primary instance from ...` 行より）—— これは
一般ユーザーが書き込めない場所であるため `HRESULT(0x80070005)` になる。これは上流の
[kdroidFilter/ComposeNativeWebview#31](https://github.com/kdroidFilter/ComposeNativeWebview/issues/31)
と完全に一致する —— 例外クラス、呼び出しチェーン、HRESULT すべて同一で、コントリビューターのコメントも
同じ修正（`dataDirectory` に書き込み可能なパスを設定する）を確認している。`dataDirectory` はライブラリ
内で OS 分岐されておらず（`WebViewDesktop.kt` の `defaultWebViewFactory` がどの分岐でも同じ値を
`NativeWebView(...)` に渡す）、macOS/Linux で問題が出ていなかったのは、それぞれのプラットフォームの
暗黙のデフォルトディレクトリがたまたま書き込み可能だったからにすぎない。

この一つの原因でフリーズも説明できる。`WryWebViewPanel.createIfNeeded()` の非 macOS 経路は
`NativeBindings.createWebview(...)` の呼び出しを `catch (e: RuntimeException)` で囲んでいるが、
ライブラリの jar に対して `javap` を実行したところ `WebViewException` は `RuntimeException` ではなく
`java.lang.Exception` を直接継承していることが確認できた —— つまりこの `catch` 節は実際には機能せず、
例外は uncaught のまま EDT まで届く（上記の `Exception in thread "AWT-EventQueue-0"` は AWT 自身の
デフォルト未捕捉ハンドラの出力そのもの）。例外が `createIfNeeded()` を中断させ `stopCreateTimer()` に
到達できないため、`scheduleCreateIfNeeded()` の 100ms 間隔の `javax.swing.Timer` は止まらず、失敗する
WebView2 生成呼び出しを EDT 上で無限に再試行し続ける —— これは EDT が新しい入力に応答しなくなるという
症状と整合する。

### 除外した仮説

- **Windows 固有の位置(bounds)のバグ** —— `WRYWEBVIEW_LOG=1` が使える前、見えている症状だけから
  立てた最初の仮説。Windows だけ `WryWebViewPanel` がネイティブサーフェスの位置をトップレベルウィンドウの
  HWND に対して手動でミラーリングしており（`resolveParentHandle()` が `parentIsWindow=true` を返し、
  `boundsInParent()` が `convertPoint(host, 0, 0, window) - window.insets` をミラーする）、Compose の
  interop とネイティブ位置とのレースに見えたため。ログで `updateBounds()`/`setBounds` が一度も走って
  いないことが判明した時点で除外 —— ネイティブサーフェス自体が存在しないので、ズレる位置がそもそも
  ない。`dataDirectory` の修正後の再検証ログで、`createIfNeeded success`、正しいペイン相対位置での
  `setBounds` の 1 回だけの発火、クリックしてもフリーズしないことを確認し、解消を確認した。

### 解決方法

`ArticleWebView` は `rememberWebViewStateWithHTMLData(...)` の直後、`WebView(...)` コンポーザブルに
到達する前に、`remember(webViewState) { ... }` の中で
`webViewState.webSettings.desktopWebSettings.dataDirectory` を `AppDirs.cacheDir()` 配下の
`webview` サブディレクトリに設定するようにした。これは `LaunchedEffect` ではなく同期的に、かつ
`WebView(...)` が最初にコンポーズされる前に行う必要がある —— 内部の `WryWebViewPanel` の
`dataDirectory` フィールドは `private final` で、`ActualWebView` の
`remember(state, factory) { factory(...) }` がネイティブパネルを構築する際に一度だけキャプチャされ、
以後読み直されないためである。ディレクトリ自体の作成はネイティブライブラリ側に任せ(Keryx 側では
`mkdirs()` を呼ばない)、再起動をまたいで永続させる —— 上流が提案した修正(毎回タイムスタンプ付きの
一時ディレクトリを新規作成する)とは異なり、埋め込みコンテンツ(SNS 埋め込みなど)の Cookie/
ローカルストレージが再起動のたびに捨てられることなく残る。OS 分岐は不要だった —— このプロパティは
macOS/Linux にも同一に適用され、既に書き込み可能な暗黙のデフォルトを明示的な値に置き換えるだけなので
無害である。パス結合ロジック `webViewDataDirectory(cacheDir: String): String` は `commonTest` の
`ArticleReaderDataDirectoryTest.kt` でカバーしている。

## Windows: コンテキストメニューが誤った位置に開き、項目のラベルが重なって描画される

**状態**: 解決済み —— Windows を `java.awt.PopupMenu` から `javax.swing.JPopupMenu` に移した。
コンテキストメニュー(`platform/NativeMenu.desktop.kt` の `defaultPopupHandle`)とトレイメニュー
(Compose の `Tray()` を置き換える新規の `tray/WindowsTray.kt`)の両方が対象。macOS は本物の `NSMenu`
になるため AWT のまま据え置いた。

### 症状

Windows で記事行やフィード行を右クリックすると、コンテキストメニューがカーソルの**左上方向**にずれて
開き、さらに各項目が互いに重なって描画された。枠の幅はラベルに対して十分なのに高さだけが必要量に
まったく足りず、隣り合うラベルが重なる。どちらの症状もデスクトップの表示スケール設定に比例して悪化し、
100% では発生しなかった。macOS / Linux では再現しない。

### 診断

2つの症状は同一の JDK の不具合に由来する。Windows の AWT メニューピアが、Java のユーザー空間
(論理座標、96 DPI 基準)とデバイスピクセルの間の変換をまったく行っていない。

**位置** —— `awt_PopupMenu.cpp` の `AwtPopupMenu::Show` は、Java の `Event` から x/y をそのまま取り出し、
デバイスピクセルで動作する Win32 API に渡している。この関数には `ScaleUpX`/`ScaleUpY` が一切ない:

```cpp
pt.x = env->GetIntField(event, AwtEvent::xID);   // Java のユーザー空間
pt.y = env->GetIntField(event, AwtEvent::yID);
::MapWindowPoints(awtOrigin->GetHWnd(), 0, (LPPOINT)&pt, 1);   // デバイスピクセル
::TrackPopupMenu(GetHMenu(), flags, pt.x, pt.y, 0, awtOrigin->GetHWnd(), NULL);
```

これは JDK 全体の流儀ではなく、この経路だけの欠落である。`awt_Component.cpp` は
`ReshapeNoScale(ScaleUpX(x), ScaleUpY(y), ...)` と正しく変換しているし、`WFontMetrics` のネイティブ実装は
Java に返す際に `ScaleDownX` を通している。結果としてメニューは
`ウィンドウ原点 + クリックオフセット ÷ スケール` に出るため、原点から遠いほどズレが大きくなる。報告された
スクリーンショット2枚に対して `menu = origin + (click − origin) / S` を解くと、縦横とも、かつ共通の原点で
`S = 2.0` に整合する —— つまり報告者のデスクトップは表示スケール 200% だった。

**重なり** —— `awt_MenuItem.cpp` の `AwtMenuItem::MeasureSelf` が、1つの構造体の中で2つの座標空間を
混在させている:

```cpp
int height = JNU_CallMethodByName(env, 0, fontMetrics, "getHeight", "()I").i;
measureInfo.itemHeight  = height;          // ユーザー空間
measureInfo.itemHeight += measureInfo.itemHeight/3;
measureInfo.itemWidth   = size.cx;         // getMFStringSize -> デバイスピクセル
```

`FontMetrics.getHeight()` は `awt_Font.cpp` から返る時点で `ScaleDownY` 済みなので、Windows が
デバイスピクセルとして解釈する `itemHeight` は `1 / スケール` に縮んでいる。一方 `DrawSelf` が描画に使う
HFONT は `lfHeight` が `ScaleUpY` 済み(`awt_Font.cpp` の `CreateHFont_sub`)である。幅は
`getMFStringSize` 由来で最初からデバイスピクセルなので正しい。この非対称が、報告された見た目
**「幅は足りているが、文字の高さに対して行の高さがまったく足りない」**そのものである。

上流: [JDK-8259913](https://bugs.openjdk.org/browse/JDK-8259913) *AWT menu items are not scaled
correctly on Windows HiDPI displays*(未解決)。300% 以上に対して起票されているが、上記の算術は 100% を
超えるすべてのスケールでスケールに比例して破綻する。

切り分けの決め手は1枚のスクリーンショットの中にあった。同じ DPI でアプリ自身のメニューバーは正常に
描画されている。Compose Desktop の `MenuBar` が `javax.swing.JMenuBar` を構築するためである
(`ui-desktop` の逆アセンブルで確認: `MenuBarScope.setContent(javax.swing.JMenuBar, ...)`)。Swing は
変換行列が適用された Java2D 経由で描画するのでどのスケールでも正しく、AWT のメニューピアはそうではない。

### 除外した仮説

- **Keryx 自身の座標変換のバグ**(`Modifier.nativeContextMenu` の
  `(elementPosition + localPosition) / density.density`)。この除算は `java.awt.PopupMenu.show` の
  仕様どおりである —— 同メソッドはインボーカのユーザー空間の座標を取り、Compose Desktop のポインタ座標は
  `ユーザー空間 × density` だからである。macOS で問題が出ないのも同じ理由で、AppKit はポイント基準
  なので `NSMenu` はこの値をそのまま正しく消費する。アプリ側の計算に変更は不要であり、実際に変更して
  いない。
- **Keryx 側でスケール係数を打ち消す**(`show` の前に x/y を掛け戻す)。却下 —— 直せるのは位置だけである。
  ラベルの重なりは JDK のオーナードロー計測コールバックの内部で起きており、Java 側のどの呼び出しからも
  手が届かない。行に収まるようメニューのフォントを縮める案も同じ理由で却下した —— 描画上の不具合を
  別の不具合に置き換えるだけである。
- **本物のチェック用ガターを導入した `java.awt.CheckboxMenuItem` 化**。フィード行のメニューが
  チェック項目だらけなので疑ったが、チェック項目を1つも持たないタグ行のメニューでも同じように再現する。

### 解決方法

`defaultPopupHandle` は `AwtPopupHandle` を **macOS でのみ**選ぶようになり、Windows は Linux と同じ
`SwingPopupHandle` に合流した。この選択はプロセス定数を既定値とする `macOs` 引数になっているが、これは
`NativeMenuTest` がどの CI ホストでも対応関係を固定できるようにするためだけのものである。バックエンドに
追随して変わる挙動が2つあるが、いずれも意図的なものである。セパレータが `"-"` ラベルの `MenuItem` では
なく本物の `JPopupMenu.Separator` になること、そして `java.awt.MenuShortcut` では構造的に表現できない
修飾キーなしのショートカット(F2 / Delete)がアクセラレータ列に表示されるようになることである。
`forceHeavyweight` は Linux では必須ではなかったが Windows では必須になる —— Linux では FlatLaf が
どのみちポップアップを heavyweight に強制するのに対し、Windows は `installLookAndFeel` のシステム L&F
分岐を通るため、この1行だけが記事リーダーの WebView の背後にメニューが隠れるのを防いでいる。

トレイも同じ対応の中で修正した。Compose の `Tray()` は内部で独自に `java.awt.PopupMenu` を構築しており
(これも逆アセンブルで確認)、2項目が同様に潰れていたためである。`WindowsTray` は生の `TrayIcon` を駆動し、
右クリックで `WindowsTrayMenu`(Swing)を開く —— これは `MacTray` が別の理由で `Tray()` を迂回している
のと同じ形である。`TrayIcon.addActionListener` は `onTrayAction` に従来どおり接続してあるので
`shouldHideOnTrayAction` のヒューリスティックは以前とまったく同じイベントを受け取るし、通知は
`TrayIcon.displayMessage` 経由で出る —— これは `TrayState.sendNotification` が内部で呼んでいたものと同一
である。インボーカ用の Frame だけは `MacTray` のものと1点異なり、記録に値する: フォーカスを取れるように
してあり、使用時以外は非表示にしている。AWT の `PopupMenu` は自前のネイティブなモーダルループを持ち
自分で閉じるのに対し、`JPopupMenu` は所有ウィンドウがフォーカスを保持し、かつ失える場合にのみ、外側の
クリックで閉じるからである。

### トレイにはもう1つ修正が必要だった: `TrayIcon` 自身の座標

ウィジェットを差し替えたことでトレイメニューの**描画**は直ったが、**位置**は直らなかった。アイコンが
どれだけ左にあっても、メニューは画面右端に張り付いて見切れたままだった。これは同じ不具合パターンの
3 箇所目であり、メニューのバックエンドとは無関係な原因なので、独立して記録しておく価値がある。

`AwtTrayIcon::WmAwtTrayNotify` は素の `::GetCursorPos()` の結果 —— デバイスピクセル —— を
`SendMouseEvent` に渡し、`SendMouseEvent` はそれをコンポーネント相対の座標対と画面座標対の**両方**に
格納している(`x, y, // no client area coordinates` / `x, y`)。この経路のどこにも `ScaleDownX/Y` は
現れない。したがって **Windows では `TrayIcon` の `MouseEvent.getXOnScreen()` はデバイスピクセル**で
あり、一方 `java.awt.Window.setLocation` はユーザー空間を取り内部でスケールアップし直す。イベントの
数値をそのままインボーカウィンドウの配置に使うと、スケール倍だけ遠くに置かれることになる —— トレイは
そもそも画面右下にあるので画面外へ完全に飛び出し、その後 `JPopupMenu` 自身の画面内補正がメニューを
端に張り付かせていた。

修正は、位置を `MouseInfo` から取ること(`WindowsTray.kt` の `trayMenuAnchor`)。
`Java_sun_awt_windows_WMouseInfoPeer_fillPointWithCoords` は同じ `::GetCursorPos()` を読むが、
その下のモニタを `MonitorFromPoint` で解決したうえで
`AwtWin32GraphicsDevice::ScaleDownAbsX/Y(pt)` を返す —— これはモニタ自身の原点を基準にした
モニタ単位の除算である(`screen + ClipRound((x - screen) / scaleX)`)。`setLocation` が求める空間で
あると同時に、モニタごとにスケールが異なる場合も正しいので、アプリ側でスケール係数を導出する必要が
まったくない。

macOS ではこれらは一切不要である。`CTrayIcon` は一貫してポイントで報告するため、`MacTray` の
構造的に同一な `e.xOnScreen - origin.x` の演算はそのままで正しく、手を入れていない。

## macOS: 項目の端をちょうどクリックすると隣の項目が選択される

**状態**: 未修正 — macOS 自身の挙動であり、Apple 純正のメモアプリでも同じ狙いで再現する。当初原因と
疑ったアプリ側のジオメトリは実測の結果すべて正確だった。ただし、この調査で**実際に見つかった当たり
判定の不具合は修正済み**（下記「実際に修正したもの」を参照）。

### 症状

選択中のフィード / フォルダー / タグ / 記事行の下端ぎりぎり —— カーソルが選択ハイライトに明らかに
重なって見える位置 —— を狙ってクリックすると、**下**の行が選択される。行と行は 4dp の隙間で区切られて
いるため、新たに選択された行のハイライトはクリックしたように見えた位置よりはっきり下から始まり、
選択がクリック位置を飛び越えたように見える。「ハイライトの内側をクリックしたのに下の項目が選ばれる」
として繰り返し報告された。

この「飛び越えて見える」距離は、隙間が 2dp から 4dp に広がったぶん大きくなった（ドラッグ中の挿入
マーカーが隙間を埋めるのをやめ、`LIST_ROW_GUIDE_CLEARANCE` が両ハイライトとの間を空けるようになった
—— 詳細は `ui-guidelines` skill）。下記の原因は OS 側のままで変わっていないので、同じ現象が少し
見えやすくなっただけであり、新たな不具合ではない。

### 診断

分かりやすい説明の両方 —— 「ハイライトと当たり判定がずれている」「ポインタ座標がずれている」 ——
を直接実測したが、どちらも厳密に一致していた。

**ハイライトはバンドと一致している。** 一時的なテストでペインの描画結果をキャプチャしてピクセルを
読み出した。density 2.0 のとき記事行のバンドは `[48, 126)` / `[126, 204)` / `[204, 282)` ——
つまり隣接するバンドは連続しており、間に説明のつかない空間は存在しない —— であり、選択行の
ハイライトは中央のバンドの 126..203 行、すなわちその全ピクセルを塗っていた。

**座標も一致している。** ウインドウに一時的な AWT `MouseMotionAdapter` を仕込み、同じ移動に対する
生イベントの Y と、Compose のポインタ入力が受け取った Y を並べてログ出力した。差は **0** で、
ペインの上端でも下端でも、density 2.0 で変わらなかった。補正すべきずれは存在しない。

**残るのはカーソル自身である。** macOS の矢印カーソルは黒いグリフに白フチを付けて描画され、
ホットスポットは**黒い**グリフの先端にある —— したがって目が「先端」と認識する点は、実際の
ホットスポットより 1〜2 物理ピクセル**上**にある。端に触れたつもりで狙うと、ホットスポットは
わずかに端の外に出る。観測されたすべての性質がこれと整合する: 端でだけ起こり、スクロール位置に
依存せず、隙間の大きさを変えても効果の大きさが変わらない。

**ネイティブアプリでも再現する。** Apple 純正のメモアプリで同じ狙い方をすると、同様に隣のメモが
選択される。ピクセル単位の厳密な原因がどうであれ、これに合わせることはこのアプリの不具合ではない。

### 実際に修正したもの

この報告を起点とした調査で、実在する不具合が 3 つ見つかっており、いずれも修正して
`ListRowHitAreaTest`（`composeApp/src/desktopTest/.../ui/home/`）で固定してある:

- **行の内側にあった死角。** 以前は `clip`/`background` を入力系の modifier より**前**に置いていた
  ため、当たり判定がインセットされた角丸矩形にクリップされていた: 外側のマージンと角丸の 4 隅は
  クリックしても何も選択されなかった。さらに悪いことに、`Column` で包まれていた行（ドラッグの挿入
  マーカーを兄弟としてレイアウトするため）では、`Column` の報告バウンズの内側であっても、パディング
  を持つ子孫のバウンズの外側にある点はすべて死んでいた。`listRowClickable` / `listRowSurface`
  （`ui/home/ListRowChrome.kt`）で当たり判定（行のバンド全体）と描画されるハイライト（インセット
  + クリップ）を分離して解決した。
- **行間の隙間が不均等に割れていた。** 挿入マーカーがレイアウト領域を確保しており、その空間は
  それを含む側の行のレイアウトに完全に属していたため、隣の行より自分の行のハイライトに近い位置を
  クリックしても隣が選ばれることがあった。マーカーをレイアウトせず行自身のマージンに描画する
  （`insertionMarkers`）ことで、当たり判定の境界を隙間の真の中点に置いた。
- **行の高さが状態で変わっていた。** フォルダー見出しのバンドは折りたたみ状態や最後のフォルダーか
  どうかで、フィード行のバンドはグループ内で最後かどうかで高さが変わっていた。

これらにより「クリックしても**何も**選択されない」ケースは消滅した —— 元の報告のもう半分がこれ
である。残っているのは上記の端のケースだけである。

### 除外した仮説

- **`apple.awt.fullWindowContent` / `transparentTitleBar`。** ウインドウのコンテンツ座標原点を
  AppKit のヒットテストに対してずらしているのではないかと疑った。実験的に無効化した ——
  タイトルバーは戻ったが、挙動は変わらなかった。
- **フィード一覧のドラッグ機構。** `FeedListDragController` が境界や行の上下半分を解決するのは
  ドロップ位置の決定のためだけで、`selectFilter` を呼ぶことはない。選択は完全に各行自身の
  `listRowClickable` が担っているので、`bandAt` / `resolveHitBand` / `resolveRowHalf` が影響を
  与えることはあり得ない。
- **modifier の順序・パディングの非対称・隙間の割り方。** `ListRowHitAreaTest` が各境界
  （記事↔記事、フォルダー見出し↔先頭のフィード、フィード↔フィード、タグ配下のフィード↔フィード）
  を 1px ずつスイープし、隣接バンドが連続していることと、共有境界でちょうど切り替わることを
  検証している。
- **隙間を縮める。** 6dp、4dp、2dp、0dp（マージンなし、ハイライトがバンド全体を埋める）で試した。
  各段階で発生する幅は狭まったが、消えたものは一つもなかった —— 原因が行の位置ではなくクリックの
  着地点であることと整合する。
- **UI フレームワークの変更。** SwiftUI/AppKit に移しても解決しない: メモアプリはネイティブで
  同じ挙動を示すし、カーソルのホットスポットは OS が決めている。

### 回避策を入れない理由

補正するには、各行の当たり判定をハイライトに対して上へずらすことになる。これは一方の端を他方と
交換するだけである: 今度はハイライトの**上端**付近をクリックすると上の行が選ばれる。両端を同時に
成立させるずらし方は存在せず、プラットフォーム純正のアプリもそのような補正を入れていない。

## 並行書き込みにより read→write トランザクションがリトライ不能な SQLITE_BUSY で失敗する

**状態**: 未修正 — 該当箇所は限定的で本番では未観測。本当の修正はデータアクセス層の中核に触れる。
これが flaky にしていたテスト側では回避済み。

### 症状

並行する書き込みの下で、`FeedRepository.subscribeFeedWrite` の `feeds.upsert` が
`org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked` を投げることがある。
これは `Result.Err` ではなく呼び出し元スレッドの**捕捉されない例外**である —
`subscribeFeedWrite` の `db.transaction {}` は `try`/`catch` で囲まれておらず、例外は
`FeedRepository` の外まで伝播する。

CI でのみ発生する `FeedRepositoryTest.subscribeFeedSerializesSortOrderAllocationAcrossConcurrentCalls`
の flaky（実スレッド 2 本で `subscribeFeed` をファイルバック DB に対して並行実行するテスト）として
最初に見つかり、その後ローカルでも同じスタックトレースで再現した（30 回中 1 回）。

### 診断

`subscribeFeedWrite` の `db.transaction {}` は write の前に read を行う
（`feeds.getByUrl` / `feeds.nextSortOrderInGroup` の後に `feeds.upsert`）。SQLDelight の
`JdbcSqliteDriver` は常に素の `BEGIN TRANSACTION`（SQLite のデフォルトである deferred モード）を
発行するため、トランザクションは read の時点では SHARED ロックしか取らず、write の際に
RESERVED へ**昇格**する必要がある。

SQLite 自身のロック昇格ルール（`sqlite3_busy_handler` の公式ドキュメントに記載された挙動）は次のとおり:
その昇格を許可すると、別の接続が自分自身の昇格待ちでデッドロックし得る場合、SQLite は
**busy handler を呼び出すことなく即座に** `SQLITE_BUSY` を返す。`busy_timeout`（このアプリの
`SQLITE_BUSY_TIMEOUT_MS`。`sqlite_connection_properties()` 経由で適用）は、他の昇格と衝突していない
ロックの**取得**を待つ場合にのみ効き、この経路には効かない。

失敗したテストにおける並行書き込み元は次の 2 つ: 1 本目の `subscribeFeedWrite` は
`subscribePlacementMutex.withLock { db.transaction { ... } }` の中で RESERVED を保持するが、
mutex を解放した**直後**にその呼び出しが行う次の処理 —
`articleRepository.upsertParsed(feedId, fetched.articles)`（フィードごとの別トランザクション）—
がまだ進行中でロックを保持している間に、2 本目の呼び出しが自分の SHARED read ロックを RESERVED へ
昇格させようとする、という重なりである。

### 除外した仮説

- **`busy_timeout` では解決しない。** 設定はされている（`SQLITE_BUSY_TIMEOUT_MS = 5_000`）が、
  失敗したテスト実行は 1 秒未満で完了しており、待機は一切発生していない。これは「取得が遅れて
  タイムアウトした」のではなく「ロック**昇格**が busy handler を経由せず即座に失敗した」ことと
  整合する。
- **WAL モードへの切り替えでも解消しない。** 窓が狭まるだけである。WAL でも書き込み側同士は
  直列化され、他の書き込み側が保持するロックを追い越して昇格しようとする書き込み側は同じ理由で
  `SQLITE_BUSY_SNAPSHOT` になる。

### 本当の修正に必要なこと

素の `BEGIN` の代わりに `BEGIN IMMEDIATE TRANSACTION` を発行する `ConnectionManager`/`JdbcDriver`
が必要になる。書き込みを行うことが分かっているトランザクションが、後から昇格するのではなく
最初から書き込みロックを取ることで、失敗モードが `busy_timeout` で既にカバーされている
通常のリトライ可能な「ロック待ち」に変わる。
`app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver` は（`open` でない）`class` であり、
トランザクションの begin/end/rollback は private な `connectionManager(url, properties)`
ファクトリに由来するため、サブクラス化では実現できない —
`desktopMain` に独自の `JdbcDriver` 実装を書き、`JdbcSqliteDriver` 自身が行っている
`ThreadedConnectionManager` 相当のスレッドごとのコネクション管理とリスナー管理を再実装する
必要がある。すべてのトランザクションが最初から書き込み側になる副作用があるため、FTS の
差分インデックスや同期マージとの相互作用を別途評価する必要がある — CI の flaky 修正の
スコープを超える。

### どこで起こり得るか

コード上の確認による（実際に発生させて確認したものではない）— トランザクションが write の前に
read を行っている箇所:

- `FeedRepository.subscribeFeedWrite`（`FeedRepository.kt`。`getByUrl`/`nextSortOrderInGroup` の後に
  `upsert` を行う `db.transaction {}`）— 今回観測された箇所。
- `FeedRepository.moveFeedsOutOfFolder`（同一トランザクション内で `nextSortOrderInGroup` を読んでから
  フィードごとに書き込む）。直接呼ばれる経路に加え、`FolderRepository.deleteFolder` の
  トランザクション経由でも到達する。

`FeedRepository.moveFeed` と `FolderRepository.reorderFolders` は並び順の read を
`db.transaction {}` を開く**前**に済ませているため、この形状には当てはまらない。
フィード更新の反映トランザクション（`FeedRepository.kt` の `applyFetch` 周辺）も write のみで
該当しない。

### テスト側でどう回避したか

`FeedRepositoryTest.subscribeFeedSerializesSortOrderAllocationAcrossConcurrentCalls` は、
`<item>` を持たないフィードを購読するようにした。`articleRepository.upsertParsed` が挿入する
記事が無くなるため、1 本目の呼び出しが mutex 解放後に行う処理は読み取りのみになり、
昇格と競合し得る 2 本目の書き込み側が存在しなくなる。並行書き込みテストの一般的な指針は
`docs/testing.md` の該当箇所を参照。
