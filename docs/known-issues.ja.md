# 既知の不具合

[English](known-issues.md)

原因は判明しているが、意図的に修正していない不具合と、その判断の根拠。
調査で除外した内容も記録してあるので、後から調べ直すときに同じ作業を繰り返さずに済む。

エントリーには「起きにくい」以外の理由が必要である。原因が外部にある（OS・JDK・ライブラリ・プラットフォームの制約）か、
穴を塞ぐのに、それを見つけた変更の範囲を超える再設計が要るか、のどちらかである。「まれにしか起きない」「窓が狭すぎて当たらない」
は理由にならず、そうした不具合は修正する（`.claude/CLAUDE.md` の「Error handling and race conditions」を参照）。原因が判明している
エントリーには、本当の修正に何が必要かを書く（上流での変更が必要な場合はそう書く）。

## macOS: 通知バナーをクリックしてもトレイに収納中のウインドウが復元されない

**状態**: 未修正 — 外部（JDK/AWT）の制限による。対象は **Compose 版 macOS ビルドのみ**で、これは内部の確認用に
残しているだけでリリースしていない。出荷している macOS アプリはネイティブの SwiftUI アプリ（`external-spec.md` §2）
で、この問題は起きない。トレイアイコンのクリックとアプリの再起動（シングルインスタンス転送。起動中に Dock アイコンを
クリックするなど）ではどちらも復元できるため、ウインドウが取り戻せなくなることはない。失敗するのはバナー自体の
クリックだけで、それもトレイ収納中に限られる。

### 症状

macOS でウインドウをトレイに収納した状態で新着記事の通知をクリックしても何も起こらない — ウインドウも
Dock アイコンも現れず、音も鳴らない。バナーの「表示」アクションボタンもバナーが消えるだけで、それ以外の効果はない。
ウインドウが単にバックグラウンド（表示されているが他のウインドウの背後）にあるときは、同じクリックで前面化する。

### 診断

`displayMessage(...)` で表示したバナーのクリックに対して `TrayIcon` が公開している API は AWT の
`TrayIcon.addActionListener(...)` だけだが、実際にパッケージ化したビルドでログを取ったところ、ウインドウが
トレイ収納中（Accessory アクティベーションポリシー）のときにクリックしても **`ActionListener` は一切呼ばれない**。
復元ロジック自体（`main.kt` の `activationRequests` コレクタ）には問題がない。トレイアイコンのクリックや
転送された 2 回目の起動から到達した場合は、毎回正しく復元する。

原因と見られるのは `TrayIcon` のネイティブ macOS ピア（`CTrayIcon`）で、`displayMessage(...)` を非推奨の
`NSUserNotification` API でブリッジしている。そのクリック時のデリゲートコールバック
（`userNotificationCenter:didActivateNotification:`）が、Dock アイコンのない状態
（`NSApplicationActivationPolicyAccessory`）では Java へ戻ってこないようである。これ以上絞り込むには
`CTrayIcon` のデコンパイルか最小構成の純粋 AWT アプリでの再現が必要だが、いずれも行っていない。

バックグラウンド状態で前面化するのはアプリのコードではなく、Regular ポリシーのアプリに対する macOS 自身の既定の
クリックでアクティブ化する挙動であり、通知クリックの配線が一切なくても同じように動く。そのため `MacTray` は
`ActionListener` を登録せず、通知クリックの処理を自前では持たない。Linux の通知クリック処理（`LinuxTray`、
`LinuxNotifier` の D-Bus `ActionInvoked` シグナル経由）はこれとは別で正常に動いており、`KeryxTray` の
`onNotificationClicked` パラメータと `main.kt` の `activationRequests.tryEmit(Unit)` コールバックはそのために存在する。

### 除外した仮説

- **`main.kt` の復元/アクティベーションポリシーのロジックのバグ** — 同じ `activationRequests` コレクタは、
  トレイアイコンや転送された起動から到達した場合には正しくウインドウを復元する。
- **Cocoa のアクティベーションポリシー昇格とウインドウ表示の順序** — 先に Regular へ昇格・アクティブ化し、
  表示・前面化・フォーカス取得をさらに後の EDT ターンへ遅らせても、通知クリックには何の違いも生まなかった。
  この順序は、シングルインスタンス/再起動経路からのトレイ復元を成り立たせているため `main.kt` に残している。

### 回避策

特に適用していない — macOS でトレイ収納中のウインドウを取り戻す手段は、トレイアイコンのクリックとアプリの再起動とする。

### 本当の修正に必要なこと

macOS では AWT の `TrayIcon.displayMessage()`/`ActionListener` を迂回し、`NSUserNotificationCenter`
（または非推奨でない `UserNotifications` フレームワーク）を JNA ベースの Objective-C ブリッジで直接操作する必要が
ある — `MacActivationPolicy` の生の `objc_msgSend` 呼び出しと同じ方向性だが、規模はずっと大きい。通知センターの
デリゲートとなるランタイム Objective-C クラス（`objc_allocateClassPair`/`class_addMethod` に JNA の `Callback`
を実装として渡す）が必要で、これはリスクの高いネイティブ相互運用であり（誤ると JVM がクラッシュしうる）、
実機での検証を何度も繰り返す必要がある。

これは実装しない。影響を受ける Compose 版 macOS ビルドはリリースしておらず、出荷している SwiftUI アプリは通常の
アプリライフサイクルの中で `UNUserNotificationCenterDelegate` を通じて通知のクリックを受け取るためである。

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
   `LaunchedEffect(listState, revealRequest, …)` → `scrollToIndexIfNeeded`（`ui/home/HomeCommon.kt`）。
   キーのうち、操作中に変わるのは `revealRequest` だけで、これはユーザーが別の記事へ動かした選択を数える
   （`HomeViewModel.selectionRevealRequests`）。選択記事の id が単独で変わる場合（墓石化した行を
   選んだために、直前の記事へカーソルが戻る場合）は再起動しない。
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
  「すべて既読」。いずれもユーザーが行う選択ではないため、effect が再起動しない。

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
- Android の行スワイプ（`ui/home/ArticleRowSwipe.kt`）は、M3 の `SwipeToDismissBox` ではなく、行自身の
  ノードに付ける修飾子（`pointerInput`・`drawBehind`・`absoluteOffset`）だけで作っている。
  `SwipeToDismissBox` は行をさらに 2〜3 個のノードで包んでしまうため。この不具合が上流で直ったら
  `SwipeToDismissBox` に移す（`ui-guidelines` スキルの「Standard components over custom drawing」の例外 3）。

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
判定の不具合は修正済み**（下記「実際に修正したもの」を参照）。この項目が扱うのは **macOS 上の
Compose 版デスクトップビルド**（内部の確認用。出荷している macOS アプリはネイティブの SwiftUI アプリ、
`external-spec.md` §2）であり、以下の行・テスト・計測はすべて Compose 版のものである。

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

この調査で見つかった当たり判定の不具合は修正済みで、`ListRowHitAreaTest`
（`composeApp/src/desktopTest/.../ui/home/`）が現在のジオメトリを固定している:

- **行の当たり判定はバンド全体**で、マージンも角丸の隅も含む。描画されるハイライトはインセットして
  クリップしてある — `listRowClickable` / `listRowSurface`（`ui/home/ListRowChrome.kt`）がこの 2 つを
  分けているため、行の内側に何も選択されない点はない。
- **2 行間の当たり判定の境界は隙間の中点。** ドラッグの挿入マーカーはレイアウトせず行自身のマージンに
  描画する（`insertionMarkers`）ので、どちらの行の領域も奪わない。
- **行のバンドの高さは状態に依存しない**（フォルダー見出しが折りたたまれているか最後か、フィード行が
  グループ内で最後か）。

残っているのは上記の端のケースだけである。

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
- **Compose 自体。** メモアプリはネイティブの AppKit アプリで同じ挙動を示し、カーソルのホット
  スポットは OS が決めているので、この現象に Compose ビルド固有の要素はない（SwiftUI 版アプリの
  ネイティブのリストについては計測していない）。

### 回避策を入れない理由

補正するには、各行の当たり判定をハイライトに対して上へずらすことになる。これは一方の端を他方と
交換するだけである: 今度はハイライトの**上端**付近をクリックすると上の行が選ばれる。両端を同時に
成立させるずらし方は存在せず、プラットフォーム純正のアプリもそのような補正を入れていない。

## 並行書き込みにより read→write トランザクションがリトライ不能な SQLITE_BUSY で失敗する

**状態**: 未修正 — 本当の修正には `BEGIN IMMEDIATE` を発行する専用の JDBC ドライバーが要る（後述の「本当の修正に必要なこと」を参照）。
これはデータアクセス層の中核への変更で、見つけた変更の範囲を超える。これが flaky にしていたテスト側では回避済み。

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
`feedPlacementMutex.withLock { db.transaction { ... } }` の中で RESERVED を保持するが、
mutex を解放した**直後**にその呼び出しが行う次の処理 —
`articleRepository.upsertParsed(feedId, fetched.articles)`（フィードごとの別トランザクション）—
がまだ進行中でロックを保持している間に、2 本目の呼び出しが自分の SHARED read ロックを RESERVED へ
昇格させようとする、という重なりである。

（これは観測した当時のコードの説明である。その後、購読は記事をフィード行と同じトランザクションの中で、mutex を
保持したまま書くようになったので、更新（refresh）がその 2 つの間に確定することはない。検索インデックスの追いつき
処理は、今も mutex の解放後に書く。また、このトランザクションは今も書く前に読む（`getByUrl` → `upsert`）ので、
別の書き込み元（更新など）によって同じように昇格が失敗することはありうる。そのため、本当の直し方は
`BEGIN IMMEDIATE` だという下の結論は変わらない。）

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
- `FeedRepository.moveFeedsOutOfFolderLocked`（同一トランザクション内で `nextSortOrderInGroup` を読んでから
  フィードごとに書き込む）。`FolderRepository.deleteFolder` のトランザクション経由で到達する。

`FeedRepository.moveFeed` と `FolderRepository.reorderFolders` は並び順の read を
`db.transaction {}` を開く**前**に済ませているため、この形状には当てはまらない。（`moveFeed` は
その read からトランザクションまでの間 `feedPlacementMutex` を保持するので、同じグループへフィードを
配置する購読と交互に実行されることはない。read はトランザクションの外に置いたままである。）
フィード更新の反映トランザクション（`FeedRepository.kt` の `applyFetch` 周辺）も write のみで
該当しない。

### テスト側でどう回避したか

`FeedRepositoryTest.subscribeFeedSerializesSortOrderAllocationAcrossConcurrentCalls` は、
`<item>` を持たないフィードを購読するようにした。書き込む記事も検索インデックスの追いつき処理も
無くなるため、1 本目の呼び出しが mutex 解放後に行う処理は読み取りのみになり、
昇格と競合し得る 2 本目の書き込み側が存在しなくなる。並行書き込みテストの一般的な指針は
`testing.ja.md` の該当箇所を参照。

## 同期のマージが、その間ずっとデータベースの書き込みロックを持つ

**状態**: 未修正 — 塞ぐにはマージの再設計が要り、見つけた変更の範囲を超える（「本当の修正に必要なこと」を参照）。

### 症状

同期がダウンロードしたクラウドファイルをマージしている間、ローカルのデータベースへのほかの書き込みはすべて待たされる。
`busy_timeout`（`SQLITE_BUSY_TIMEOUT_MS`、5 秒）より長く待った書き込みは `SQLITE_BUSY` で失敗する。UI から渡された
既読・スターの変更やフィード一覧・設定の書き込みは、ローカル書き込みの失敗として通知センターに載り
（`LocalWriteFailureReporter`）、UI はその変更を取り消す。フィード更新の反映もその回は失敗する。

### 診断

`DatabaseMerger.merge` はすべての `MergeSql` 文を専用の接続の 1 つのトランザクションで実行するので、最初の文から
コミットかロールバックまで書き込みロックを持ち続け、その時間はマージする行数に比例して伸びる。そのため記事が
十分に多ければ（キャッシュの保持期間を無制限にしていればありうる）、`busy_timeout` を超える。判定に遅延再実行が要る
制約違反（main に違反が元からあるときの外部キー違反と、Android では main に違反が元からあるときのすべての制約違反）は、
残りの文をもう一度実行するので、さらに長く持つ。それ以外の制約違反は再実行しない（[sync-architecture.ja.md](sync-architecture.ja.md)
の「マージ失敗の分類」を参照）。

規模の目安としてだけ書いておく: ある desktop 環境で 1 件約 2 KB の記事 2 万件を 1 回計測したところ、成功するマージは
約 350 ms、再実行が要る失敗はその約 1.7 倍だった。この数値を再現するテストはリポジトリになく、マージを変えれば古く
なる。上の主張はこの数値に依存しない。

### 除外したもの

- **`busy_timeout` を延ばす** — 書き込みが失敗し始める規模が動くだけで、マージを待つ書き込みはすべてさらに長く待つ。
- **WAL モード** — 読み取りはマージと並行できるが、書き込みはマージの後ろに直列化されたままである。
- **今のマージをそのまま複数のトランザクションに分ける** — 失敗やクラッシュで途中まで適用されたマージが残り、
  `cloud_file_rev` はそのことを何も表さない。

### 本当の修正に必要なこと

それぞれがデータベースを整合した状態に保ち（外部キーに安全な順序）、冪等に適用し直せる、上限のある複数の
トランザクションでマージを適用し、`cloud_file_rev` は最後のトランザクションの後にだけ記録する。そうすればロックは
トランザクションの間で手放され、どこで中断したマージも次の同期がそのまま完了させる。その際、
[sync-architecture.ja.md](sync-architecture.ja.md) の「マージ（`DatabaseMerger` + `MergeSql`）」と「マージ失敗の分類」の
保証（分類のトランザクション内のチェックを含む）と、マージの後に `indexMissing()` を呼ぶという FTS の規則をすべて保つ必要がある。

## Google Drive: アップロードが他端末のアップロードを気付かれずに上書きすることがある

**状態**: 未修正 — 外部（Google Drive API）の制約。Drive には条件付きの内容更新がない。変更を行った端末が同期を続ける限り、
上書きされた変更は失われない。

### 症状

Google Drive で同期する 2 台の端末が同時に同期すると、両方がアップロードのリビジョンチェックを通ることがある。
すると後の書き込みがクラウド上で先の書き込みを置き換え、どちらの端末も競合に気付かない。
先の端末の変更は、その端末が次に同期するまで、クラウドのファイルからも、それをダウンロードした端末からも欠ける。

### 診断

他のプロバイダーはリビジョンチェックを書き込みそのものの一部にしている（Dropbox の `rev`、OneDrive と Nextcloud の
`If-Match`）ので、古いリビジョンに基づく書き込みはサーバーが拒否する。Drive にはこの前提条件がない。`files.update`
にもそのメディアアップロードにも、Drive API v3 リファレンスに ETag / `If-Match` のチェックや `412` 応答の記載がない。
そのため `GoogleDriveStorage.upload` は現在の `version` を読んでから比較して書き込み、読み取りと書き込みの間に窓が残る。
これが今も成り立つかを確かめるには、公式の `files.update`（とそのメディアアップロード）のリファレンスに、ETag /
`If-Match` の前提条件か `412` の記載があるかを見る。

最初のアップロードも同じ形をしている。Drive には不可分な「なければ作成」がないため、2 台が同時にファイルを作成すると
それぞれ 1 つずつ作られ、`GoogleDriveStorage.resolveCreateRace` が id の最も小さいものを残す。Drive のファイル一覧は
作成直後のファイルをまだ含むとは限らないので、端末が自分のファイルしか見えずに勝ったと判断し、一覧が追いつくまでそこへ
書き込み続けることがある。`RACE_RECHECK_DELAY_MS` 後の再確認はこの窓を狭めるが、閉じることはできない。一覧に両方が
見えるようになると、`findFile` はどの端末も id の最も小さいファイルに解決し、もう一方のファイルの内容は取り残される。

### 通常は何も失われない理由

上書きされた端末は、自分の変更をローカル DB に持っており、自分の書き込みが生んだリビジョンを記録している。次の同期で
クラウドに別のリビジョンを見つけ、そのファイルをダウンロードしてマージし、マージ結果をアップロードするので、変更は
クラウドに戻る。クラウドから失われるのは、その端末が二度と同期しない場合（先にアンインストール、リセット、Drive との
切断をした場合）だけである。

### 除外したもの

- **同期のリトライループ** — 検出した競合しか再試行しない。窓の中で起きた上書きは検出されない。
- **書き込みが返す version から上書きを推測する** — `version` はメタデータを含むサーバー側のすべての変更で増えるので、
  2 以上進んでいても他のアップロードがあった証拠にはならない。
- **ロックファイル** — その作成自体が条件なしの Drive への書き込みで、同じ窓を持つ。

### 本当の修正に必要なこと

Drive の内容更新に対するサーバー側の前提条件（`files.update` の `If-Match` など）と、最初のアップロードのための
不可分な「なければ作成」（または強い整合性を持つ一覧）。それがあれば、`upload` は OneDrive や Nextcloud のストレージと
同じように `expectedRev` を書き込みそのものに渡せ、`create` は後から調整するのではなく、その場で失敗できる。

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

断続的に起きる。複数のランナー OS で観測されているが、同一 run の複数ジョブで起きたことはなく、
`UpdateDownloader` にもそのテストにも触れていないコミットで起きている。

### なぜ失敗から何も分からなかったのか

`UpdateDownloader.download` は通常の失敗を例外ではなく `Result.Err` で報告し
（[error-design.ja.md](error-design.ja.md) 参照）、`Result.Err` を返す経路はログも出さない。
テストは進捗の `CompletableDeferred` だけを待っていたため、**進捗を 1 度も出さずにダウンロードが
終わると Deferred は永久に未完了**になり、さらに外側の `finally` が理由を保持している `Deferred` を
キャンセルしてしまう。`shared/build.gradle.kts` には `testLogging` 設定が無いので、唯一の
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

`presentation/article/ArticleWebViewHtml.kt` (`:shared`) はフィード本文を、記事自身のオリジンを指す `<base href>` の下に
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

SwiftUI アプリのリーダー（`ReaderNavigationGate`）は、さらにブラウザ化することを拒む：スクリプトは動くが、
ページ全体を置き換える遷移はその場では読み込まない——クリックによるものは外部ブラウザで開き（http/https のみ）、
クリックを伴わない最上位の遷移（`location = …`、meta refresh）は破棄し、iframe の中の埋め込み自身の遷移だけを
読み込む。スクリプトが合成したクリック（`a.click()` や cancelable な dispatch）はページ内で取り消す。ただし完全では
ない：cancelable でないイベントには `preventDefault` が効かないため、`new MouseEvent('click', {bubbles: true})` を
リンクに dispatch するスクリプトにはリンクを起動され得る。さらに、このガードは `window` 上でイベントの `composedPath()` からリンクを探す（`href` または
`xlink:href` 属性を持つ要素はすべてリンクとみなすので、HTML の `<a>` / `<area>` だけでなく SVG や MathML の
アンカーも対象）ため、*closed* な shadow root の中のアンカー（`composedPath()` に現れない）や、shadow root 内で dispatch された composed でない
クリック（`window` まで届かない）ではリンクが起動し得る。WebKit はそれをユーザーのクリックとして報告するので、
誰もクリックしていないのにページが外部ブラウザで開く（http/https のみ）。Compose のリーダー（デスクトップ・Android）は独自のリンク横取りのままで、この規則は持たない。Apple の
年齢制限は、任意のページをアプリ内で閲覧できることを「無制限の Web アクセス」（16+）とみなすので、
これで SwiftUI アプリはその対象外になる。

スクリプトによる最上位の遷移は、*次の*記事の読み込みを失わせることもある。WebKit は、別の最上位の遷移が
始まった時点で（その遷移をゲートが拒否する場合でも）進行中の文書の読み込みを止め、止めた読み込みについては多くの場合
何も通知しない。そのため、`location` を設定し続けるスクリプトを含む記事（実測では、1 ミリ秒ごとに設定する記事で、
素早い切り替え 150 回中 12 回、次の記事の読み込みが失われた）は、一覧が新しく選んだ記事を示しているのに、
自分を表示したまま残っていた。SwiftUI のリーダーはそこで、自分の読み込みをコミットまで追跡し、読み込み中に
最上位の遷移を拒否したとき、または読み込みが失敗したときに、文書を読み直す。読み直しは文書 1 つにつき
`READER_DOCUMENT_RELOAD_LIMIT` 回まで（`:shared` の `presentation/article/ReaderDocumentLoad.kt`。修正後は 150 回中 0 回）。
Compose のリーダーは、こうした遷移を拒否せず通すが、同じように読み込みを失っていた（デスクトップで 150 回中 4 回）。
こちらも同じ規則に従い、文書に付けた目印を確かめる——[app-architecture.ja.md](app-architecture.ja.md) の
「文書はビューの準備ができしだい読み込ませ」を参照。

### 本当の修正に必要なこと

影響を封じ込めるだけでなく、本文を信頼できないマークアップとして扱うこと — ksoup で `<script>` /
`<link>` / `<style>` / `<base>` / `<meta>` / `<noscript>` / `on*` 属性を除去し
`isJavaScriptEnabled = false` にする（SNS 埋め込みは埋め込みコード自身が持つ `<blockquote>`
フォールバックに退化する）か、本文を `<iframe sandbox srcdoc>` に隔離してリーダーの文書に
一切到達できないようにする。

SwiftUI リーダーの shadow DOM の穴に限れば、`WKNavigationAction` にはユーザーのクリックとスクリプトのクリックを
区別する公開 API がないので、イベントの経路から推測するのをやめ、本物のユーザー操作を記録する方式にする：ページ内
スクリプトが信頼された `click`/`pointerup`/キーイベントのたびにネイティブ側へ報告し（`WKScriptMessageHandler`）、
`ReaderNavigationGate` は、その報告が新しく（約1秒以内）未使用のときに限り `.mainFrameClick` を外部で開く。
代償として、DOM のクリックを伴わない操作——iOS の長押し「リンクを開く」、macOS のコンテキストメニューの
「リンクを開く」——まで取り消されないか、実機で確認する必要がある。

## Linux arm64: 記事リーダーのネイティブ WebView バイナリが存在せず、ウインドウがフリーズする

**状態**: 回避済み（未修正） — WebView ライブラリがバイナリを同梱していないプラットフォーム／
アーキテクチャの組み合わせでは、リーダーが Compose 描画の簡易表示にフォールバックする
（`platform/NativeWebViewSupport.kt` と `ui/article/ArticleContentView.kt`）。本当の修正は arm64
Linux バイナリを持つバックエンドへの移行だが、規模がまったく違う（後述）。**Linux arm64 は今では
サポート対象の出荷ターゲット**になっている（`build.md` の「リリース（CD）」に x86_64 と並んで
記載され、CI も `-linux-arm64.{zip,deb,rpm,snap}` 成果物を公開している）— そのため、この
回避策はもはや開発機だけの話ではなく、Linux arm64 の全ユーザーが出荷ビルドで簡易リーダーを
使うことになる（ソースからビルドする一部のユーザーだけではない）。

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
フォールバック（`ui/article/ArticleContentView.kt` + `ArticleBlockViews.kt`）はブロック構造
（`<figure>`/`<figcaption>` や `<dl>`/`<dt>`/`<dd>` を含む）・インライン装飾（記事側の `style=""`
が持つ色・サイズ・太さも `ui/article/InlineCss.kt` で解釈）・画像（`srcset`／遅延読み込み属性、
画像しか持たない段落やリンクの実ブロック画像への昇格を含む）を再現する。ブラウザエンジンを本当に
必要とする埋め込み（iframe、スクリプト駆動のウィジェット、動画）は、外部ブラウザで開くボタンになる。
文字サイズやブロックの余白は、このアプリの `MaterialTheme.typography` ではなく、ドキュメント自身の
UA 既定の等幅スケール（`ArticleTextStyles.kt`）から導く——理由は `app-architecture.md`「Article
Reader (native WebView)」を参照。

`-Dkeryx.reader.webview=false` を渡すと任意のデスクトップ環境でフォールバックを強制できる。
ネイティブ WebView が利用できる環境で見た目を作り込むには事実上これが最も手軽な手段である。
なお Linux arm64 はネイティブ WebView がないため、デフォルトでフォールバックが使われる。
逆に `-Dkeryx.reader.webview=true` は WebView を強制的に使わせる。
ライブラリ更新でプローブ対象のクラス名が変わり、プローブが恒常的な偽陰性になった場合に効く（その
ケースは専用の警告をログに出す）——ただしこれはプローブを再実行するのではなく完全に迂回するため、
実際にネイティブバイナリが存在する環境でしか安全ではない。Linux arm64 で渡した場合は同じネイティブ
初期化に到達し、上記で説明した `UnsatisfiedLinkError` とフリーズに見えるモーダルダイアログを再現する。
どちらのプロパティもデスクトップ（JVM）側の `actual`
（`NativeWebViewSupport.desktop.kt`）でしか読まれず、`./gradlew :composeApp:run` 自身の JVM から
そこへは `composeApp/build.gradle.kts` の `tasks.withType<JavaExec>().configureEach` ブロックが
中継している（`JavaExec` タスクは起動元 JVM のシステムプロパティを自動では引き継がないため、この
中継が無いと `-Dkeryx.reader.webview` は Gradle 自身に飲み込まれ、アプリまで届かない）。Android の
`actual` は常に WebView が利用可能だと報告する（`android.webkit.WebView` は OS 自身が提供するため）
ので、どちらのプロパティも Android には効果がない。

### 本当の修正に必要なこと

上流のライブラリは `dev.nucleusframework:composewebview` に移行しており、現行リリースには
`nucleus/native/linux-aarch64/libcompose_webview_linux.so` が**同梱されている**。ただし採用は
バージョン上げでは済まない。Wry のデスクトップバックエンドは削除され、README はデスクトップの
WebView に Nucleus Tao バックエンドが必須だと明記している — *"Desktop is Tao-only … Swing/Compose
Desktop without Tao will not host the WebView"* — アプリのエントリポイントは
`nucleusApplication(backend = NucleusBackend.Tao)` になる。これは Compose Desktop 自身の
`application` / `Window` を置き換えるものであり、`main.kt` はその上に SNI トレイ、AWT メニューバー、
`WindowChrome`、`FilePicker`、macOS の `Desktop` ハンドラを積み上げている。独立した案件としてのみ
着手する価値がある — Linux arm64 が出荷ターゲットになった今（上記「状態」参照）は簡易リーダーの
UX ギャップがより多くのユーザーの目に触れるようになるが、それだけでアプリのエントリポイント
置き換えが正当化されるわけではない。

## CI限定: macOS の `packageDmg` が `hdiutil detach` の「リソースがビジー」で失敗する

**状態**: 外部要因（macOS ランナー）による一過性の不具合。修正ではなくリトライで緩和している。
DMG 生成処理そのものは jpackage 内部にあり、アプリ側で制御できない。

### 症状

`macos-latest` の GitHub Actions ランナー上で `./gradlew packageDmg` が次のように失敗する:

```text
Execution failed for task ':composeApp:packageDmg' (registered by plugin 'org.jetbrains.compose').
> Command failed (exit 16): hdiutil detach /dev/disk31s1
```

断続的に起きる。最初に観測したときは、直前の実行が同じコードで成功しており、間にあったのはドキュメントのみのコミットだった。

### 除外した原因

- **コードの変更が原因ではない。** 最初に観測したブランチは、macOS のパッケージングに関わるもの
  （Compose プラグインのバージョン、パッケージング設定、macOS の CI ステップ）を何も変えていなかった。
- **同一ランナー上での `packageDmg` の同時実行でもない。** GitHub Actions のランナーは使い捨ての
  VM であるため、`ci.yml` の `concurrency:` 設定で並行して走る実行（v* の任意の 2 実行、別ブランチの
  実行）同士でもディスクは共有されず、別の実行によって残留マウントが生じることはあり得ない。

jpackage は DMG 生成時にスクラッチのディスクイメージをマウントし、アプリバンドルをコピーしてから
detach する。同じログに残っているオーファンプロセスの後始末（`Terminate orphan process: pid
(diskimages-help)`）は、ジョブ終了時点でディスクイメージのヘルパプロセスがまだ生きていたことを
示しており、detach が（おそらく Spotlight / `mdworker` によって）ボリュームを掴まれている状態と
競合したという見立てと整合する。

### 緩和策

`.github/workflows/ci.yml` の "Verify packaging (macOS)" ステップは `packageDmg` を最大3回まで
リトライする。
ただし失敗ログに実際に `hdiutil` が含まれている場合に限る — それ以外の失敗（本物の
パッケージング不具合）は1回目でそのまま失敗する。リトライ前に `/Volumes/Keryx*` 配下に残っている
マウントを強制 detach し、次の試行が別名のマウントポイントに当たらないようにしている。

### 本当の修正に必要なこと

アプリ側で打てる手はない — マウント/detach は jpackage のネイティブな DMG 生成処理の内部で完結して
おり、この競合は CI ランナーの環境（マウント直後のボリュームを Spotlight がインデックスしようと
すること）の性質であって、このアプリが設定している何かに起因するものではない。GitHub または JDK
側が上流で対処しない限り、リトライ以外に取れる緩和策はない。

## iOS: 記事一覧に戻ると `UICollectionView` でクラッシュした

**状態**: 構造上は起きなくなったはず、原因は未確定。記事一覧を `UICollectionView` にしてから、UIKit が後から
検証する保留のスクロールは残らない（下の「変えたこと」）。クラッシュは再現できていないので、それで止まったのかは
分かっていない。分かるまで、この項目は残す。

### 症状

v0.27.0、iPhone SE（第3世代）、iOS 26.5 で、プロセス起動の 32 秒後に
`-[UICollectionView _validateScrollingTargetIndexPath:raisingExceptionIfNecessary:]` の
`NSInternalInconsistencyException` で異常終了した。Reader からのナビゲーションの pop を始めるレイアウトの中で、
SwiftUI の `UpdateCoalescingCollectionView.updateContent()` が `layoutSubviews` からスクロールを要求している。
スタック上の Keryx のフレームは `main` のみで、レポートに例外メッセージがないため、index path と行数は不明。
Kotlin の GC スレッドは待機中だった。

Reader のスワイプのページャーは collection view ではなく `UIPageViewController` なので、このレイアウトにある
SwiftUI の collection view は、当時の記事一覧の `List` だけである。

### 除外できたこと

記事一覧がまだ SwiftUI の `List` だったときに、iOS 26.3 シミュレータで計測した。最初の 4 行は pop をコードから行い、最後の 2 行は UI テストで画面端のスワイプを
行った。

| シナリオ | 結果 |
| --- | --- |
| 一覧が Reader の下にある間の `scrollTo` | 発生する（選択の移動、復元した選択） |
| 51 行目への scroll が保留の状態で行を 60→9 に減らして戻る | クラッシュしない |
| 起動時に Reader を復元して戻る | クラッシュしない |
| 起動時に Reader を復元し、行を 59→34 に減らして戻る | クラッシュしない |
| scroll を保持した状態で、画面端のスワイプで一覧に戻る | クラッシュしない |
| scroll を保持した状態で、画面端のスワイプをキャンセルする | クラッシュしない |

したがって、行の減少で保留の scroll が古くなるだけでは足りない。

### 変えたこと

記事一覧は、サイドバーと同じ `UICollectionView` のリストになった（`Home/ArticleCollectionView.swift`。
[app-architecture.ja.md](app-architecture.ja.md) の「記事一覧（iOS）」）。`ScrollViewProxy.scrollTo` はもう
使わない。コントローラーは、一覧が画面に出ているときだけ、その時点でデータソースが持っている行に対して
`scrollToItem` でその場でスクロールする。それまでは最新のスクロールだけを待たせ、その時点で行がなくなっていれば
捨てる。後のレイアウトで検証される保留が、UIKit の中に残らない。

### 再確認

この変更を含むビルドで同じスタックのクラッシュが報告されたら、原因は別にある。新しいレポートをもとに、
この項目を見直す。

## macOS: フォルダーをフォルダーの間に落とすと SwiftUI の中でクラッシュした

**状態**: 回避済み（未修正） — 外部（SwiftUI）の不具合。フィードとフォルダーのドロップは、すべて SwiftUI の
ドロップ処理を通さずに決めて適用している（`Home/Sidebar/SidebarDropGuard.swift`）。

### 症状

ガードがないと、macOS 26 でサイドバーのフォルダーをフォルダーの間に落としたときに、トラップ（`EXC_BREAKPOINT`、
Swift のアサーション）で落ちる（v0.27.0、macOS 26.7.1 で観察）:
`-[NSOutlineView performDragOperation:]` → `OutlineListCoordinator.outlineView(_:acceptDrop:item:childIndex:)` →
`outlineView(_:performDropInsertion:with:at:)` → `ListOutlineItem.subscript` → `HeterogeneousViewIDs.subscript` →
`HeterogeneousCollection.element(at:)`。スタックに Keryx のフレームはない。フォルダーをすべて折りたたんだ状態を含め、
このドロップのたびに起きる。

### 原因

macOS の SwiftUI の `List` の不具合。同じ構造の単体アプリで再現した: サイドバースタイルの `List` の中の、
`.onInsert` を付けたフォルダー行の `ForEach`。フォルダー行に `DisclosureGroup`、`.badge`、コンテキストメニュー、
`.onDrop` のどれか 1 つでもあると、フォルダーをフォルダーの間に落としたときに落ちた。`.itemProvider` と `.tag` だけの
行（フォルダーに入っていないフィードの行と同じ）では落ちなかった。フィードをほかのフィードの間に入れる操作は、
別のフォルダーでも同じフォルダーでも落ちなかった。

### 除外できたこと

| 変更 | 結果 |
| --- | --- |
| Folders の `Section` を `isExpanded` なしにする、または `header:` のビューにする | 落ちる |
| 各フォルダーの中のフィードの `.onInsert` をなくす | 落ちる |
| フォルダー行の `.tag`、`.badge`、`.onDrop`/コンテキストメニューのどれかをなくす | 落ちる |
| フォルダー行を `DisclosureGroup` なしにする（行のほかの修飾子は残す） | 落ちる |
| フォルダーに `.onInsert` ではなく `.onMove` を使う | 落ちないが、フィードを別のフォルダーのフィードの間に入れられなくなる |
| SwiftUI のアウトラインのデータソースを転送用の代理で包む | SwiftUI 自身のドロップ処理が壊れる（挿入線が出ず、フォルダー行に落とせない） |

サイドバーに必要な行の要素はどれも単独で引き金になるので、ビューの構造を変えて避けることはできない。Swift の
アサーションは例外ではなくトラップなので、捕まえることもできない。

### 回避策

SwiftUI のドロップ処理は、クラッシュ以外にも、一部の位置（フォルダーの中のフィード行の上のフォルダー、フォルダー行の
中央）を断り、存在しない位置（フィード行の子。これも落ちる）を受け付け、断りながらドロップ先を動かし、「コピー」と
答えてカーソルに「+」を付ける。そこで `SidebarDropGuard` は、フィードとフォルダーのドロップから SwiftUI を完全に
外す。`List` には `.onInsert`/`.onDrop` を付けない。

- `List` の背後にある `NSOutlineView` のクラスと、そのデータソース（SwiftUI の `OutlineListCoordinator`）のクラスに
  メソッドを足す。クラスがメソッドを継承しているだけなら `class_addMethod`、自分で実装しているなら
  `method_setImplementation`（元の実装は保持する）を使う。どのメソッドも、ガードのハンドラーが付いたアウトラインで、
  フィードかフォルダーの型を運ぶドラッグのときだけ働く。それ以外は元の実装に渡す（差し替えていない場合は、`super` と
  同じく呼び出しのたびに親クラスのメソッドを引く）。
- データソースの `outlineView(_:validateDrop:proposedItem:proposedChildIndex:)` が行き先を決めて `setDropItem` で
  設定し、アウトラインの `performDragOperation:` が、その行き先がまだ同じアクションに解決されるときだけ適用する。
  これらのドラッグでは、SwiftUI の `validateDrop`/`acceptDrop` は呼ばれない。
- アウトラインの `expandItem:` は、フォルダーのドラッグ中は何も開かないよう断る。SwiftUI のアウトラインは
  `expandItem:` を上書きして、開く前に行を開いたと記録するので、そこで断れば（その次に呼ばれる
  `expandItem:expandChildren:` ではなく）記録は変わらない。SwiftUI が自分の開閉状態を反映する別の経路には
  手を付けない。
- SwiftUI のドロップ修飾子がもうないので、ガードが 2 つのドラッグの型を自分で登録する（`registerForDraggedTypes`）。
- タグの下のフィードのコピーには専用の ID を付ける。フィードの ID のままだと、アウトラインがコピーを本体と取り違えた。

項目がどこに入るかの規則は、[app-architecture.ja.md](app-architecture.ja.md) の「サイドバー（macOS）」にある。

インスタンスではなくクラスを変えるのは、インスタンスがすでにほかの誰かに差し替えられていることがあるため。テストの
ホストでは、ガードが最初に取り付けを試みる前に、KVO がアウトラインのクラスを差し替えていた。KVO のクラスのサブクラスを
作ると、KVO が元に戻すときに壊れる。Swift の `type(of:)` は KVO のクラスを飛ばすので、どちらの場合もメソッドは SwiftUI
自身のクラスに付く。

想定どおりにいかないときの結果:

| 場合 | 結果 |
| --- | --- |
| ガードの近くにアウトラインが見つからない | ガードを付けない。サイドバーのドラッグ＆ドロップは何も起きない（落ちない）。移動とタグ付けはコンテキストメニューで行える。fault のログは残さない。アウトラインは更新のたびに探し直すので、起動直後に一時的に見つからないのは普通のため。この場合は結合テストで気づく |
| アウトラインはあるが、データソースがない、またはクラスにガードが上書きするメソッドがない | ガードを付けない。結果は同じ。fault のログを 1 回だけ残す（`SidebarDropGuard` カテゴリー） |
| 提案された行に読めるマーカー（`SidebarRowMarker`）がない、または行の位置がサイドバー自身の並びと合わない | ガードは付くが、当たるドロップを拒否し、何も動かない。マーカーがあるはずの行にないときは fault のログを 1 回だけ残す（`SidebarDropGuard` カテゴリー） |
| ガードを付ける前か後に、KVO がアウトラインを観測する | 影響しない。メソッドは SwiftUI のクラスにあり、KVO のクラスはそれを継承する。テストで確かめている |
| アプリのほかの `List` が同じクラスを使う | そのアウトラインにはハンドラーが付いていないので、どのメソッドも元の実装に渡す |
| 差し替えたメソッドの SwiftUI 自身の実装が後から変わる | ほかのドラッグには、保持した元の実装か、呼び出しのたびに引いた親クラスのメソッドが使われる |

`KeryxTests/SidebarDropGuardTests.swift` は、サイドバーと同じ形の `List` を画面外のウィンドウに載せ、ガードが頼る前提を
確かめる。`List` が `NSOutlineView` で、そのデータソースが `validateDrop` に応えること、各行のマーカーをその行から読めること、
サイドバー自身のセクションの前や間にマーカーのないセクションや行があっても各行をその行として読めること、
マーカーのない行へのドロップを拒否すること、テスト用の `NSDraggingInfo` で送ったドロップをガードが決めて適用すること。CI が
macOS 26（`apple-release-toolchain`）と macOS 15（`apple-oldest-supported`）で実行するので、これらを変える新しい SDK や macOS はそこで落ちる。
利用者がまだ使える macOS 14 と、実際のマウスでのドラッグは確かめられない。新しい macOS ごとに手で確かめ直す: フォルダーをフォルダー行の上寄り・中央・下寄り、
フォルダーの中のフィードの上、先頭、末尾に落とし、フィードをフォルダー、「フォルダーなし」、タグ、タグの下のコピーに落とす。

macOS 26 より前は、サイドバーの検索フィールド（`.searchable(placement: .sidebar)`）もセクションの前にあるアウトラインの
ルート項目で、どのセクションとも違ってグループ項目ではない。そのためガードは、ルート項目やセクションの中の位置で行を見分けない。マーカーで読んだフォルダーやタグの行が、兄弟の中で自分の位置にあることを確かめるだけである。ガードが必要とする各行は、内容の背後に見えないマーカーのビュー
（`SidebarRowMarker`）を持ち、そこに `SidebarItemID` を載せる。ガードはそれをアウトラインに既にある行ビューから探す
（`makeIfNecessary: false`。作ったばかりの行には次のターンまで内容が入らない）。Folders のセクションはマーカーの
ある子で見分け、Tags のセクションにはマーカーのあるタグ行の親としてたどり着く。「フォルダーなし」のセクションは見出しのマーカーで、フィードのフォルダーは親のマーカーで見分ける。アクセシビリティ
識別子は使えない。SwiftUI は行ビューにもアウトラインのアクセシビリティ行にもそれを出さない。
テスト用のサイドバーにも同じ検索フィールドを付けているので、macOS 15 でテストを実行すればこの構造も確かめられる。
CI の `apple-oldest-supported` もそうしている。macOS 14 では、上に挙げた手でのドロップで確かめ直す。

### 本当の修正に必要なこと

SwiftUI 側の修正。ただし、SwiftUI のドロップ処理が上に書いたようにドロップを違う位置にしたり断ったりする間は、
ガードを残す。アウトラインを直接扱う方法（iOS のサイドバーが `UICollectionView` でしているように、`NSOutlineView` を
`NSViewRepresentable` で包む）なら SwiftUI の内部に頼らずに済むが、今 `List` が用意している行、選択、寸法を自前で
描くことになる。

## iOS: スワイプを始めると行がジャンプする

**状態**: 回避済み・未修正 — 外部（UIKit / SwiftUI）の不具合。コンテキストメニューとスワイプアクション
（どちらも仕様の一部。`external-spec.md` §9）を持つ行は、スワイプを始めるとジャンプする。Keryx では、
記事一覧とサイドバーのメニューの押下ハイライトを透明なビューに向けて避けている（下の「回避策」）。
不具合そのものは残っている。

### 症状

iOS で記事の行をスワイプすると、行が最初は指に追従しなかった。行の先頭側が削られていき（未読の点が
左から欠けていく）、残りは止まったまま、数フレーム後に行全体が一気に指の位置まで飛び、そこからは普通に
追従した。左にスワイプすると「左側が消えていき、それから行がスライドする」ように見えた。スワイプ自体は
効き、おかしいのは最初の数フレームだけだった。サイドバーの行（フィード、フォルダー、タグ）でも同じだった。

XCUITest でゆっくり（60 pt/秒）ドラッグし、`simctl io recordVideo` で録画して 60 fps でコマ送りして
計測した。行は約 200 ms 止まったあと、1 フレームで iOS 17.0 シミュレータ（iPhone 15 Pro）では約 30 pt、
iOS 26.5 シミュレータ（iPhone 17 Pro）では約 17 pt 動いた。サイドバーの行は 27 pt と 17〜18 pt だった。
iOS 26 では小さいため、最初は iOS 17 で気付かれたと考えられる。

### 診断

セルのビュー階層の一時ログで、スワイプの開始時に UIKit がしていることを見た。セルにマスクを付けてセルを
動かし、マスクは逆向きに動いて画面の先頭端に留まる。アプリ内で読んだ位置は、最初のフレームから
1 フレーム約 1 pt ずつ滑らかに動いていた。録画はそうではなかった。マスクは新しい位置に描かれる一方、
セルの中身は元の位置に描かれたままで、その間の帯が削られ、中身が 1 フレームで追いついた。

**原因は、コンテキストメニューの押下ハイライト。** 指を置いている間、メニューは行（ハイライト用
プレビューの対象のビュー）をハイライトする。このハイライトが、スワイプがマスクの下でセルを動かしている
間も、行を元の位置に描き続ける。タッチの一時計測で、ジャンプするかどうかはスワイプと認識された時刻だけで
決まることがわかった。指を置いてから約 375 ms より後に認識されたスワイプはジャンプし、それより前なら
ジャンプしない。直前にどれだけ放置していたかは関係ない。続けてスワイプすると直って見えたのは、指を
早く動かしていたからにすぎない。

要素を 1 つずつ外し、iOS 17.0 で各 4 回スワイプして、ドラッグ開始時に 1 フレームで動いた最大量を測った。

| 版 | 1 フレームの最大移動 |
| --- | --- |
| 回避策を入れる前のアプリ | 31〜35 pt |
| 行のコンテキストメニューなし | 2〜3 pt |
| 行を `Button` ではなくタップジェスチャーにし、コンテキストメニューは残す | 30〜35 pt |
| メニューを `List` に付ける（`contextMenu(forSelectionType:)`）。開くよう選択も持たせる | 15〜31 pt |

Keryx のコードを含まない最小の SwiftUI アプリ（両端に `.swipeActions`、1 項目の `.contextMenu` を付けた
行だけの素の `List`）でも同じことが起きる。メニューありでは iOS 17.0 で 20〜31 pt（4 回中 3 回）、
iOS 26.5 で 15〜16 pt（4 回すべて）。メニューなしでは、どちらでも最大 2.3 pt。UIKit 自身の
コンテキストメニューを使う素の `UICollectionView` のリストであるサイドバーでも起きていた。

### 除外したもの

- **このアプリ自身のコード。** 上の最小アプリでもジャンプし、計測用のコードを外しても変わらなかった。
- **行の `Button`。** タップジェスチャーに置き換えてもジャンプは残った。
- **一覧の作り直しや直前のスクロール**（`List` だったときの、ウインドウに入ったときとサイドバーから戻ったときの
  作り直しと、保留したスクロール。どれも `List` とともに消えた）、およびリターンハイライト。どれからも時間が
  たったあとでも、同じ頻度で起きた。
- **メニューを行から `List` に移す。** ジャンプが消えたのは、メニューが開けない間（一覧が選択を持たない間）
  だけだった。開けるようにするとジャンプが戻った。
- **スワイプ中の SwiftUI による行の再描画。** 計測したどのスワイプでも、行は再描画されなかった。
- **メニューの別の付け方。** 最小アプリで各 4 回試した。同じ回の素のメニューは、iOS 17.0 で 30 pt、
  iOS 26.5 で 11〜15 pt だった。

  | 版 | iOS 17.0 | iOS 26.5 |
  | --- | --- | --- |
  | `.contextMenu` を `.swipeActions` の後ではなく前に付ける | 7〜30 pt | 6〜15 pt |
  | `.contextMenu(menuItems:preview:)` のプレビュー | 7〜30 pt | 9〜15 pt |
  | `.contentShape(.contextMenuPreview, …)` | 30〜31 pt | 10〜16 pt |
  | セルに UIKit の `UIContextMenuInteraction` を付け、セルをハイライトする（UIKit の既定） | 30 pt | 2〜15 pt |

- **`UICollectionView` への移行。** サイドバーはすでに `UICollectionView` で、UIKit 自身の
  コンテキストメニューを使っているが、同じようにジャンプしていた。

### 回避策

各行のメニューは、押下ハイライトと閉じるときの対象を、セルと同じ大きさの透明なビューに向ける
（`ContextMenuPressHighlight`）。透明なビューは、セルが動いても何も残さず、行はメニュー自身のプレビューが
見せる。見えるハイライトがないので、メニューを出している間は行そのものを隠し、メニューが閉じるのに合わせて
戻す。隠さないと、背景がぼかされず暗くなるだけの場所（iPad）で、プレビューの横に行が見えてしまう。

- **記事一覧**（`ArticleCollectionViewController`）：コレクションビュー自身のメニューのまま、ハイライト用と
  閉じるときのプレビューをデリゲートから返し、メニューのプレビューには行の見た目（`ArticleRowLabel`）を
  載せる。SwiftUI の `.contextMenu` にはハイライトの対象を指定できない。一覧が SwiftUI の `List` だった間は、
  回避策のために `List` 自身のセルをビュー階層をたどって探す必要があった。macOS は SwiftUI の
  `.contextMenu` のまま。
- **サイドバー**（`SidebarCollectionViewController`）：同じ。メニューのプレビューにはセルの画像を使う。

見た目の違いは押したときだけで、メニューが開く前に、指を置いている間に行が少し膨らむ演出がなくなった。

回避策を入れた後は、計測したどのスワイプでも、ゆっくりのスワイプが 1 フレームで動いたのは 3 pt 以下だった。

| 行 | iOS 17.0 | iOS 26.5 |
| --- | --- | --- |
| 記事一覧 | 2.0〜3.0 pt | 1.3〜2.3 pt |
| サイドバー | 2.3〜2.7 pt | 1.3〜1.7 pt |

### 本当の修正に必要なこと

コンテキストメニューのある行で始まったスワイプが、メニューが何をハイライトしていても、最初のフレームから
行を動かすよう、UIKit が直すこと。そうなれば回避策は外せる。

### iOS や Xcode の更新後の再確認

新しい iOS が出るたびに、回避策がまだ要るかをシミュレータで確かめる。

1. ハイライト用と閉じるときのプレビューの指定を外す。両方のコレクションビュー（`ArticleCollectionView`、
   `SidebarCollectionView`）から `highlightPreviewForItemAt` / `dismissalPreviewForItemAt` を消す。
2. 行に触れてから 0.5 秒ほどたってから、ゆっくりスワイプする。または
   `xcrun simctl io <device> recordVideo` で録画してコマ送りする。
3. 先頭側が先に削られることなく、行が最初から指に追従すれば、その版では直っている。

サポートするすべての iOS で直ったら、回避策を外し、この項目を削除する。
