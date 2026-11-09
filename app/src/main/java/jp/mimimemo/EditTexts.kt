package jp.mimimemo

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * キーボードの確定（送信・完了）か Enter キーで [action] を実行する。
 * ハードウェアキーボードの Enter は IME_NULL で届くので、それも受け付ける。
 */
fun EditText.onSubmit(action: () -> Unit) {
    fun submit() {
        action()
        // 続けて入力できるよう、フォーカスを入力欄に残す（Enter でカードへ移ってしまうことがある）
        post { requestFocus() }
    }
    setOnEditorActionListener { _, actionId, event ->
        when {
            actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE -> {
                submit()
                true
            }
            actionId == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER -> {
                // ACTION_DOWN で実行し、ACTION_UP も消費する
                if (event.action == KeyEvent.ACTION_DOWN) submit()
                true
            }
            else -> false
        }
    }
}
