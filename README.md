# ミミメモ

情報工学専門実験・演習B Android演習の課題アプリです。会話から自動で ToDo をつくる、学生向けのアプリです（仕様は第2回レポート）。

聞き取り画面を開いている間だけ、マイクで会話を1文ずつ文字に起こします。ToDo になりそうな文が見つかると候補のカードが並び、右にスワイプしたものだけを保存します。音声は保存しません。

## 画面

| # | 画面 | クラス |
|---|---|---|
| ① | ToDo 一覧（期限順） | `MainActivity` |
| ② | 聞き取り（文字起こし表示） | `ListeningActivity` |
| ③ | ToDo 候補の詳細ダイアログ（候補カードをタップしたとき） | `ListeningActivity#showCandidateDialog` |
| ④ | ToDo 編集 | `TodoEditActivity` |
| ⑤ | 今日のまとめ | `SummaryActivity` |
| ⑥ | 設定（キーワード辞書） | `SettingsActivity` |

① の「聞き取る」ボタンで ②、カードのタップで ④、メニューから ⑤ と ⑥ に移ります。

## 操作（画面の手間を少なく）

追加・完了・削除はスワイプかその場での入力で済ませ、別画面・ダイアログは詳細を直すときだけ使います。取り消しはスナックバーの「元に戻す」で行います（#12）。

| 画面 | 右スワイプ | 左スワイプ | タップ |
|---|---|---|---|
| ① 一覧 | 完了 | 削除 | 編集画面 ④ |
| ② 聞き取りの候補カード | 追加 | 却下 | 詳細ダイアログ ③ |

① の下の入力欄に「明日までにレポート提出」のように1行打つと、期限とタグも自動で埋めてすぐ追加します。スワイプの見た目と動きは `SwipeActions` にまとめてあり、どの一覧でも同じです。

## 処理を受け持つクラス

| クラス | 役割 |
|---|---|
| `SpeechController` | 端末内の音声認識（`createOnDeviceSpeechRecognizer`、Android 12 以降）。無音で止まると自動で再開します。端末内認識が使えない端末では、音声をサーバに送らないよう通常の認識には切り替えず、聞き取り画面は手入力のみになります（#9） |
| `TodoExtractor` | キーワードで文を絞り込み、ToDo のタイトル・期限・タグを取り出します |
| `LocalAiClient` | 端末内の生成AI。今は `UnavailableLocalAiClient`（使わない）で、ルール抽出だけで動きます（#7） |
| `DateParser` | 「明日」「来週月曜」「15日まで」「11/20」などを日付に変換します |
| `TodoDbHelper` | SQLite の `todos` テーブル |
| `ReminderSpeaker` | TextToSpeech での読み上げ。明日が期限の ToDo は、その日はじめて一覧を開いたときに1回だけ読み上げます（通知はしません、#8） |
| `KeywordStore` | キーワード辞書（SharedPreferences） |

## ビルド

- Kotlin 2.1.20 / AGP 8.9.2 / Gradle 8.13
- compileSdk 35 / minSdk 26 / package `jp.mimimemo`

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # DateParser・TodoExtractor・読み上げ文のユニットテスト
```

## 実機での確認

- 端末内の音声認識は Android 12 以降が必要です。日本語モデルが入っていない端末では、最初に聞き取り画面を開いたときにダウンロードの確認が出ます
- 端末内認識が使えない端末（Android 11 以前など）やエミュレータでは、聞き取り画面の下にある入力欄に文を打つと、同じ流れ（抽出 → 確認ダイアログ → 登録）で使えます
  - 例：「来週月曜までにレポート提出してください」「スライド作っといて」「次回、教科書を持ってきてね」

## 検討中の issue

- #7 ローカルAI（端末内の生成AI）の実装方式
- #10 科目・活動タグの候補
