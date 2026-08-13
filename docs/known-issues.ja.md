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

**既に表示されているダイアログ**が、内容の変化でサイズを変える場合（フィード追加の候補リスト、設定の
タブ切替）は、仕様どおり画面上でリサイズされる —— 上記の見送った対処法を参照。この場合に変わったのは、
新たに露出する領域とボタン行の interop の穴が、L&F 既定ではなくダイアログ自身の色で塗られる点だけである。
