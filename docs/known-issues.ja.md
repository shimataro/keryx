# 既知の不具合

[English](known-issues.md)

原因は判明しているが、意図的に修正していない不具合と、その判断の根拠。
調査で除外した内容も記録してあるので、後から調べ直すときに同じ作業を繰り返さずに済む。

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
