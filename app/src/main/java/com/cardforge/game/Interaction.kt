package com.cardforge.game

/**
 * エンジンがプレイヤーに選択を求めるための窓口。
 *
 * UI 側は suspend 関数を画面のダイアログに、AI 側は自動選択に対応付ける。
 * これによりエンジンは「誰が操作しているか」を意識せずに効果を解決できる。
 */
interface Interaction {

    /**
     * [candidates] から [min]〜[max] 枚を選ばせる。
     * [min] が 0 の場合はキャンセル（空リスト）を許す。
     */
    suspend fun chooseCards(
        playerIndex: Int,
        prompt: String,
        candidates: List<CardInstance>,
        min: Int,
        max: Int
    ): List<CardInstance>

    /** 空きゾーンの番号を選ばせる。キャンセルなら null。 */
    suspend fun chooseZone(
        playerIndex: Int,
        prompt: String,
        freeZones: List<Int>
    ): Int?

    suspend fun confirm(playerIndex: Int, prompt: String): Boolean

    /**
     * [subject] は、その問い合わせのきっかけになったカード。
     * 画面側で「何が発動されたのか」を確かめられるようにするために渡す。
     */
    suspend fun confirm(playerIndex: Int, prompt: String, subject: CardInstance?): Boolean =
        confirm(playerIndex, prompt)

    /** [subject] 付きの [chooseCards]。 */
    suspend fun chooseCards(
        playerIndex: Int,
        prompt: String,
        candidates: List<CardInstance>,
        min: Int,
        max: Int,
        subject: CardInstance?
    ): List<CardInstance> = chooseCards(playerIndex, prompt, candidates, min, max)

    /** [options] から1つ選ばせる。返り値はインデックス。 */
    suspend fun chooseOption(
        playerIndex: Int,
        prompt: String,
        options: List<String>
    ): Int

    /** 情報を伝えるだけのメッセージ（AI 実装では何もしない）。 */
    suspend fun notify(playerIndex: Int, message: String) {}
}
