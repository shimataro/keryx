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
  **クラスを含まないエイリアス jar** なので重複クラス衝突は起きない。`compose-material3` のピン
  （現在 `1.9.0`、`gradle/libs.versions.toml`）も要因ではない——将来バンプする際は、この判断を
  再確認せずに信用せず、下記「ライブラリ更新後の再確認」に従って最新安定版を確認し直すこと。

### 効果が無かった回避策

再度試さないように記録しておく。

- アニメーション付きスクロール（`animateScrollToItem` / `animateScrollBy`）を、即時の
  `scrollToItem` / `scrollBy` に置き換える。
- scroll-into-view の前に `isScrollInProgress` が false になるまで待つ。
- 上記 2 つの併用。
- 次の measure パスまでスクロールを遅延させる `requestScrollToItem`。
- より新しい Compose Multiplatform のプレリリース版への更新（依存グラフに実際に解決されたことを
  確認済み。検証時点のバージョンでもそれでも 5 / 5 で再現——このバージョン番号を永続的に信用せず、
  下記「ライブラリ更新後の再確認」に従って再確認すること）。

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
`SQLITE_BUSY_TIMEOUT_MS`。`sqliteConnectionProperties()` 経由で適用）は、他の昇格と衝突していない
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

## CI でのみ `UpdateDownloaderTest.progressArrivesWhileTheBodyIsStillStreaming` がタイムアウトする

**状態**: 根本原因は未特定。素のタイムアウトではなくダウンロードが実際に何をしたのかを報告するよう
テストを組み替えたので、次に再現したときは失敗自体が原因を示す。これは**診断性の改善であって、
引き金そのものの修正ではない**。

### 症状

desktop のテストタスクが、次の情報だけを残して失敗する:

```text
UpdateDownloaderTest[desktop] > progressArrivesWhileTheBodyIsStillStreaming[desktop] FAILED
    kotlinx.coroutines.TimeoutCancellationException
```

これまでに 2 回観測。いずれも OS が異なり、同一 run 内の 4 ジョブのうち 1 つでしか起きていない:
`v0` @ `1cfb4993`（windows-latest、run 35158285213）と `fix/article-swipe-pager` @ `0639f3b7`
（ubuntu-latest、run 35179227601）。どちらのコミットも `UpdateDownloader` とそのテストには触れていない。

### なぜ失敗から何も分からなかったのか

`UpdateDownloader.download` は通常の失敗を例外ではなく `Result.Err` で報告し
（[error-design.ja.md](error-design.ja.md) 参照）、`Result.Err` を返す経路はログも出さない。
テストは進捗の `CompletableDeferred` だけを待っていたため、**進捗を 1 度も出さずにダウンロードが
終わると Deferred は永久に未完了**になり、さらに外側の `finally` が理由を保持している `Deferred` を
キャンセルしてしまう。`composeApp/build.gradle.kts` には `testLogging` 設定が無いので、唯一の
`Log.warn` 経路すら CI の出力には現れない。

### 切り分け済み（原因ではないもの）

- **ローカルでは再現しない**: 無負荷で 40 回、さらに CPU を 3 倍に過負荷させた状態で 15 回
  （macOS/arm64、JDK 26）——すべて成功。
- **書き込み側の背圧でテストのフィーダーが止まっているわけではない。** Ktor 3.5.2 の `ByteChannel`
  が書き手をサスペンドさせるのは flush バッファが `CHANNEL_MAX_SIZE`（1 MiB。`ktor-io` の
  `ByteChannel.kt`）を超えてからで、テストが書くのは 320 KiB。つまり `writeFully` は読み手を
  一切待たずに返る。
- **MockEngine が本文をバッファリング／二重に読んでいるわけでもない。** `respond(ByteReadChannel, …)`
  はテストが作ったチャネルをそのままレスポンス本文として渡す（`ktor-client-mock` の `MockUtils.kt`）。
  コピーも 2 人目の読み手も存在しない。
- **`UpdateDownloader` の `timeout {}` 指定でもない。** MockEngine は `HttpTimeoutCapability` を
  宣言しているだけで自前のタイムアウトを実装していないため、`socketTimeoutMillis` は発火しない。

### 次に再現したときに得られる情報

`awaitFirstProgress` は進捗の到着とダウンロードの完了を競わせるようになったので:

- ダウンロードが先に終われば、その `Result`（`Err` なら `UpdateException` のメッセージ込み）を
  理由として失敗する
- ダウンロードが例外で終われば、その例外がそのまま伝播する
- 本当に停止している場合はタイムアウトするが、ダウンロード用コルーチンがまだ動いているかどうかを報告する

これにより、残る 2 つの仮説——「進捗を出す前に無言で `Result.Err` を返した」のか「ダウンロード用
コルーチンが時間内にスケジュールされなかっただけ」なのか——を区別できる。上記の素のタイムアウトでは
区別できなかった。

## Linux/GNOME: トレイメニューを開いた瞬間、直前のラベルが一瞬表示される

**状態**: 未修正 — 外部（GNOME Shell の AppIndicator 拡張）の設計上の制限であり、そもそも
Keryx 側からは解消できない（下記「送信タイミングを変えても無駄な理由」を参照）。表示上のみの
問題で、ラベルは 1〜2 フレームで正しい値に落ち着き、クリックの動作は常に正しい。影響を受けるのは
AppIndicator 拡張を入れた GNOME だけで、しかもメニューを閉じている間にラベルが変わった場合のみ。
KDE Plasma・macOS・Windows・Linux の AWT トレイフォールバックはいずれも影響を受けない
（どれもホスト側のキャッシュからラベルを読み直さないため）。

### 症状

GNOME（AppIndicator 拡張）でウインドウをトレイに収納した状態でトレイメニューを開くと、
一瞬「非表示」が見えてから「表示」に切り替わる。最終的なラベルは正しく、直前の値が乗るのは
最初に描画される 1 フレームだけである。ダウンロード実行中のアプリ内アップデート項目も同様で、
ラベルとグレーアウト状態がその最初の 1 フレームだけ 1 拍遅れる。

これは、GNOME でラベルが**恒久的に**古いままになっていた**別の**不具合（修正済み。
`app-architecture.md` の `ItemsPropertiesUpdated` の段落を参照）の残りかすである。混同しないこと。

### 診断

Keryx 側は正しく動作しており、まずそこを検証済みである: `SniDBusMenu.updateState` が変化した
プロパティを算出し（`TrayMenuModel.kt` の `changedItemProperties`）、ラベルが変わった時点で
遅延なく `ItemsPropertiesUpdated` を発火している。アプリ側に調べ直すべきものは無い。

遅延はすべて拡張側の dbusmenu クライアント（`ubuntu/gnome-shell-extension-appindicator` の
`dbusMenu.js`）にあり、3 段階からなる:

1. 受信したプロパティシグナルはクライアント自身の `active` フラグで足切りされ、それが false の
   間は単に退避されるだけである:

   ```js
   if (signal === 'ItemsPropertiesUpdated') {
       if (!this._active && this.getRoot()?.hasChildren()) {
           this._flagItemsUpdateRequired = true;
           return;                                    // 適用せず退避するだけ
       }
       this._onPropertiesUpdated(params.deep_unpack());
   }
   ```

2. `active` の代入箇所はただ 1 つ —— `_onMenuOpenStateChanged(menu, state)` の
   `this._client.active = state` —— なので、true になるのはメニューが開いている間だけである。
   Keryx のラベル変更は常にメニューが閉じている間に起きる（ウインドウ自身の閉じるボタン、
   あるいはこのメニュー項目自体のクリック。後者は発火と同時にメニューが閉じる）ため、
   実際には必ず退避側に落ちる。

3. メニューを開くと `set active(true)` が走り、`_doPropertiesUpdate()` が `GetGroupProperties`
   で既知の全項目を読み直す。ただしこの呼び出しは `IdlePromise` と D-Bus 往復による
   fire-and-forget であり、メニューの描画を**待たせない**。

したがってメニューは古いキャッシュで描画され、1 アイドルサイクル + 1 往復のあとに再描画される。

### 送信タイミングを変えても無駄な理由

1 の退避分岐はメニューが閉じている間は無条件なので、前倒しでも遅延でも複数回送信でも、すべて
同じ分岐に落ちる。まだ聞く気の無いクライアントにプロパティを押し込めるような dbusmenu の
呼び出しは、サーバー側には存在しない。

### 除外した仮説

- **`ItemsPropertiesUpdated` と `LayoutUpdated` の併送**。`active` のセッターは
  `if (this._flagLayoutUpdateRequired) … else if (this._flagItemsUpdateRequired) …` なので
  レイアウト側が優先され、プロパティの更新が**次にメニューを開くときまで**先送りされる。
  現状の 1 回のちらつきより明確に悪い。
- **`AboutToShow` で常にレイアウトが stale だと答える**。クライアントは stale の報告に対して
  同じ `_requestLayoutUpdate()` を呼ぶが、その `GetLayout` が要求するのは
  `['type', 'children-display']` だけで `label` を含まない。ラベルの更新には一切ならない。
- **`changedItemProperties` が送る内容の変更**。クライアントは項目を最初に作る際に何らかの値を
  取得する必要があり、その値はその時点で最新だったものになる。差分から外しても、キャッシュが
  より古くなるだけである。

### 本当の修正に必要なこと

拡張側が初回描画の前に退避分を適用すること（open 経路で `_doPropertiesUpdate()` を await
する。これは上流の変更であって Keryx 側の話ではない）か、あるいは Keryx がトグル項目のラベルを
ウインドウの可視状態に依存させない設計にすること（固定ラベルの「ウインドウの表示/非表示」1 項目、
または「表示」「非表示」の常設 2 項目）。

後者は検討のうえ**却下**した。1 つのデスクトップ環境における 1 フレーム未満の描画
アーティファクトを消すために、全プラットフォームでメニューの状態表示を失う（あるいは
プラットフォームごとにメニューの形を変える）ことになるためである。動的なラベルは
macOS・Windows・KDE Plasma・AWT フォールバックでは正しく即座に反映されており、それを
手放すほどの価値はない。

## フィード本文のスクリプトが記事リーダー内で実行される

**状態**: 半分修正 — スタイルシート側は塞いだ（リーダーの文書が
`style-src 'unsafe-inline'` の CSP を宣言しているため、本文が引き込もうとする外部スタイルシートは
一切取得されない）。スクリプト側は意図的に残している。script 型の SNS 埋め込みがそれを必要として
おり、`ArticleWebView` のリンク横取りもまさにその埋め込みを壊さないために存在するため。

### 症状

Android で `https://alfalfalfa.com/articles/11110022.html` を開くと、1〜2 秒は正常に描画された
のち表示が作り替わる。文書の余白が消え、タイトルが赤茶色になって上に移動し、フォントが小さくなり、
見出しに太い茶色の左バーが付き、「続きを読む」のリンクが青紫のボタンになる。デスクトップでは
再現しない。

### 診断

`ui/article/ArticleWebViewHtml.kt` はフィード本文を、記事自身のオリジンを指す `<base href>` の下に
無加工で埋め込んでおり、リーダーの WebView は JavaScript が有効（`composewebview` の
`WebSettings.isJavaScriptEnabled` の既定が `true`。アプリは `desktopWebSettings.dataDirectory`
しか上書きしていない）。

当該記事の `content:encoded` には、UA が `/iPhone|iPod|Android.*Mobile|Windows Phone/i` に一致する
とき `/css/smartphone.css` を `document.head` に追加するスクリプトに加え、`<noscript>` 内の同ファイル
への `<link rel="stylesheet">`、`https://blogroll.livedoor.net/css/default2.css` への `<link>`、
広告 `<script>` 4 個が含まれる。Android では UA が一致し、`<base>` によって
`https://alfalfalfa.com/css/smartphone.css` に解決され、取得完了後に適用される — カスケード上は
リーダー自身の `<style>` より後ろなので後勝ちになる。症状はすべてこのスタイルシートのルールに対応する:

| 症状 | 該当ルール |
| --- | --- |
| 余白が消える・行間が詰まる | `html, body, h1, … {margin:0; padding:0}` / `body {line-height:1}` |
| フォントが小さくなる | 同じリセットの `font-size:100%`（`html {font-size:N%}` を打ち消す） |
| タイトルが赤茶色になる | `a:link {color:#a52a2a}` — `.article-title a {color:inherit}` と同詳細度で、後に宣言されている |
| 見出しの茶色の左バー | `div.daily_popular_title {border-left:12px solid #8D4225; color:#532E0C}` |
| 「続きを読む」ボタン | `.article_bodyfooter .readmore a {background:#444499; color:#fff}` |

デスクトップで再現しないのは、UA がそのスクリプトの判定に一致せず `smartphone.css` が注入されない
というだけの理由で、コードパス自体は commonMain 共通である。

### 修正した内容

`articleDocument` が `<meta http-equiv="Content-Security-Policy" content="style-src
'unsafe-inline'">` を出力するようにした。`style-src` に URL ソースを一切書かないため、静的な
リンク・`@import`・スクリプトによる追加のいずれであっても外部スタイルシートは取得されない。一方
`'unsafe-inline'` によってアプリ自身の `<style>` ブロックと本文の `style=""` 属性は従来どおり効く。
加えて、リーダーのクロームのルールには `!important` を付けており、そこに列挙した宣言を、より低い
詳細度からの上書きに対して硬くしている（硬化であって隔離ではない。
[app-architecture.ja.md](app-architecture.ja.md) の「記事リーダー」参照）。

### 意図的に残している範囲

`script-src` は宣言していないので、本文のスクリプトは引き続き実行される。広告・計測コードが動いて
独自に外部リクエストを発行するため、[external-spec.ja.md](external-spec.ja.md) §10 の「外部サーバへ
データを送信しない」とは厳密には折り合っていない。また、スクリプトがインラインの `<style>` 要素を
注入する経路も残る（`'unsafe-inline'` の範囲内。通常のケースはクロームの `!important` で受け止めて
いるが、受け止められるのは列挙済みの宣言だけで、どのルールも `!important` を付けていない
プロパティや、本文側の同詳細度の `!important` ルールは、本文が `<style>` ブロックより後ろに来る
以上そのままクロームを作り替える）。
これは意図的なトレードオフで、JavaScript を無効にすると script 型の SNS 埋め込みも道連れになる。

### 本当の修正に必要なこと

影響を封じ込めるだけでなく、本文を信頼できないマークアップとして扱うこと — ksoup で `<script>` /
`<link>` / `<style>` / `<base>` / `<meta>` / `<noscript>` / `on*` 属性を除去し
`isJavaScriptEnabled = false` にする（SNS 埋め込みは埋め込みコード自身が持つ `<blockquote>`
フォールバックに退化する）か、本文を `<iframe sandbox srcdoc>` に隔離してリーダーの文書に
一切到達できないようにする。

## Linux arm64: 記事リーダーのネイティブ WebView バイナリが存在せず、ウインドウがフリーズする

**状態**: 回避済み（未修正） — WebView ライブラリがバイナリを同梱していないプラットフォーム／
アーキテクチャの組み合わせでは、リーダーが Compose 描画の簡易表示にフォールバックする
（`platform/NativeWebViewSupport.kt` と `ui/article/ArticleContentView.kt`）。本当の修正は arm64
Linux バイナリを持つバックエンドへの移行だが、規模がまったく違う（後述）。なお Linux arm64 は
そもそもサポート対象外であり（`build.md` の Linux は x86_64 のみ、CI も arm64 成果物を公開して
いない）、これは配布ビルドではなく開発機の話である。

### 症状

ARM64 Ubuntu（M2 Mac の VMware Fusion ゲスト）で `./gradlew :composeApp:run` を実行すると、
ウインドウは開くがそこで完全に停止する。中身は何も描画されず、操作も一切受け付けない。同じマシンで
ビルドしてインストールした `.deb` は、起動しても何も起きないように見える。

### 診断

どちらも同じ失敗である。`keryx.0.log` に記録が残る:

```text
java.lang.UnsatisfiedLinkError: Unable to load library 'composewebview_wry':
Native library (linux-aarch64/libcomposewebview_wry.so) not found in resource path
    at io.github.kdroidfilter.webview.wry.WryWebViewPanel.<clinit>
    at works.merc.keryx.app.ui.home.ArticleDetailPaneKt.ArticleWebView
```

`wrywebview-1.0.0-beta-02.jar` が同梱しているネイティブビルドはちょうど 4 つ — `darwin-aarch64`、
`darwin-x86-64`、`linux-x86-64`、`win32-x86-64`。**`linux-aarch64` が無い**。macOS には arm64 版が
あるが、Linux には無い。

フリーズそのものは、このエラーが外に出る過程で起きている。`UnsatisfiedLinkError` は `Exception`
ではなく `Error` であり、コンポジションの中から送出されるため、Compose の既定のウインドウ例外
ハンドラがこれを捕捉して**モーダル**のエラーダイアログを開く。「フリーズ中」に採取したスレッド
ダンプでは、イベントディスパッチスレッドがそのダイアログ自身のネストしたイベントループの中で
待機しており、CPU 使用率はほぼ 0% である:

```text
"AWT-EventQueue-0" ... java.lang.Thread.State: WAITING (parking)
    at java.awt.Dialog.show(Dialog.java:1051)
    at androidx.compose.ui.window.WindowExceptionHandlerFactory_desktopKt.showErrorDialog
```

パッケージ版の `.deb` もまったく同じエラーに当たっている。単にそれを表示するコンソールが無い
だけである。

### 除外した仮説

再調査を繰り返さないための記録:

- **グラフィックス / Skiko / OpenGL**。GPU パススルーの無い VM で真っ先に疑う筋だが、外れ。
  ゲストの `glxinfo -B` は `llvmpipe`・`direct rendering: Yes`・GL 4.5 を報告しており、スレッド
  ダンプでも Skiko は `LinuxSoftwareRedrawer` / `AbstractDirectSoftwareRedrawer` 経由で正常に
  動作している。既にソフトウェアレンダリングを使っているので `-Dskiko.renderApi=SOFTWARE` は
  何も変えない。
- **パッケージ版ランタイムの jlink モジュール欠落**。この種の不具合には前例があり
  （`jdk.security.auth`、`jdk.localedata` — `composeApp/build.gradle.kts` の `modules(...)` 参照）、
  `.deb` の症状を見て最初に疑ったのもこれ。しかしログには `/opt/keryx/bin/Keryx` 配下でも同じ
  `UnsatisfiedLinkError` が出ており、原因ではない。
- **シングルインスタンスロック**。フリーズした `./gradlew run` がロックを保持したままだと、
  2 回目の起動はアクティベーションを転送して黙って終了するため、まさに「何も起きない」ように
  見える。しかしその経路のログ（`Single-instance lock held by another instance`）は出ておらず、
  パッケージ版は `Acquired single-instance lock` を出してから同じエラーで失敗している。
- **ゲスト側で AWT/X11 が壊れている**。ボタンを 1 つ置いた素の Swing `JFrame` は正常に表示され、
  操作もできる。

### 回避策

`isNativeWebViewSupported()` が、リーダーがどのみち読み込むことになるネイティブライブラリを
事前に 1 回だけプローブし、失敗した場合は `ArticleWebView` が記事を Compose で描画する。この
フォールバックはブロック構造・インライン装飾・画像を再現する。ブラウザエンジンを本当に必要と
する埋め込み（iframe、スクリプト駆動のウィジェット、動画）は、外部ブラウザで開くボタンになる。

`-Dkeryx.reader.webview=false` を渡すと任意のマシンでフォールバックを強制できる。本プロジェクトが
ビルドできるどのプラットフォームでも通常は発動しない経路なので、見た目を作り込むには事実上これしか
手段がない。逆に `-Dkeryx.reader.webview=true` は WebView を強制的に使わせる。ライブラリ更新で
プローブ対象のクラス名が変わり、プローブが恒常的な偽陰性になった場合に効く（そのケースは専用の
警告をログに出す）。

### 本当の修正に必要なこと

上流のライブラリは `dev.nucleusframework:composewebview` に移行しており、現行リリースには
`nucleus/native/linux-aarch64/libcompose_webview_linux.so` が**同梱されている**。ただし採用は
バージョン上げでは済まない。Wry のデスクトップバックエンドは削除され、README はデスクトップの
WebView に Nucleus Tao バックエンドが必須だと明記している — *"Desktop is Tao-only … Swing/Compose
Desktop without Tao will not host the WebView"* — アプリのエントリポイントは
`nucleusApplication(backend = NucleusBackend.Tao)` になる。これは Compose Desktop 自身の
`application` / `Window` を置き換えるものであり、`main.kt` はその上に SNI トレイ、AWT メニューバー、
`WindowChrome`、`FilePicker`、macOS の `Desktop` ハンドラを積み上げている。独立した案件として、
かつ Linux arm64 をサポート対象にする価値が出てきた場合にのみ着手する価値がある。
