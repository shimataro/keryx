# 既知の不具合

[English](known-issues.md)

原因は判明しているが、意図的に修正していない不具合と、その判断の根拠。
調査で除外した内容も記録してあるので、後から調べ直すときに同じ作業を繰り返さずに済む。

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

`@Ignore` を外して数回実行する。

```bash
./gradlew :composeApp:desktopTest --tests '*ArticleReuseCrashRepro*' --rerun-tasks
```

繰り返し成功するようになっていれば上流で修正済み。この項目を削除し、テストは通常のリグレッション
テストとして残すか破棄するかを判断する。まだ失敗するなら `@Ignore` を戻す。
