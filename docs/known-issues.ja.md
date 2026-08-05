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
